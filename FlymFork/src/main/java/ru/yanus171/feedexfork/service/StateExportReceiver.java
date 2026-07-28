package ru.yanus171.feedexfork.service;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ru.yanus171.feedexfork.MainApplication;
import ru.yanus171.feedexfork.parser.OPML;
import ru.yanus171.feedexfork.utils.AutomationAuth;
import ru.yanus171.feedexfork.utils.Eximport;
import ru.yanus171.feedexfork.utils.StateZip;

/**
 * HandyRss: the 保存復元 state-export contract — 白い熊's 自由作業盤 automation app fires a
 * token-gated intent at every sister app, each exports itself headlessly and replies with the
 * written path and size, and 自由作業盤 collects the replies into one summary.
 *
 * Three actions, all exported and all gated by the automation switch + token (never by a manifest
 * permission — the caller cannot hold one):
 *
 *   shiroikuma.handyrss.action.LIST_CATEGORIES  -> OK: + one `id<TAB>label<TAB>parent<TAB>on|off` line per category
 *   shiroikuma.handyrss.action.EXPORT_STATE     -> writes ONE zip, replies OK:path|bytes|human|n categories
 *   shiroikuma.handyrss.action.CANCEL_EXPORT    -> unwinds the running export; answers NOTHING
 *
 * The cancel is fire-and-forget by design: its own broadcast gets no reply, and the terminal
 * `ERROR:cancelled` goes to the export request it stopped. Sending it when nothing is running, or
 * after the export already finished, is a silent no-op rather than an error. It routes through
 * this exported receiver precisely because a third-party app cannot reach a non-exported service.
 *
 * The reply is always a FRESH BROADCAST — never a ResultReceiver / PendingIntent / Messenger, and
 * never only the ordered-broadcast result: EMUI will not reliably carry a live Binder between
 * third-party apps, and it severs the ordered result channel (verified on the Mate XT, 2026-07-23).
 * FLAG_INCLUDE_STOPPED_PACKAGES matters too — without it a backgrounded caller never hears us.
 */
public class StateExportReceiver extends BroadcastReceiver {

    private static final String TAG = "HandyRssAutomation";

    public static final String ACTION_EXPORT_STATE    = "shiroikuma.handyrss.action.EXPORT_STATE";
    public static final String ACTION_LIST_CATEGORIES = "shiroikuma.handyrss.action.LIST_CATEGORIES";
    public static final String ACTION_CANCEL_EXPORT   = "shiroikuma.handyrss.action.CANCEL_EXPORT";

    private static final String EXTRA_TOKEN           = "token";
    private static final String EXTRA_PATH            = "path";
    private static final String EXTRA_ITEMS           = "items";
    private static final String EXTRA_PROGRESS_ACTION = "progress_action";
    private static final String EXTRA_REPLY_ACTION    = "reply_action";
    private static final String EXTRA_REPLY_PACKAGE   = "reply_package";
    private static final String EXTRA_REPLY_ID        = "reply_id";

    private static final long PROGRESS_MIN_INTERVAL_MS = 500;

    /**
     * The one export that may be in flight. Two at once are forbidden, which is exactly what makes
     * a CANCEL_EXPORT carrying no `reply_id` unambiguous — there is only ever one thing to cancel.
     */
    private static final AtomicReference<Running> sRunning = new AtomicReference<>( null );

    private static class Running {
        final String mReplyId;
        final StateZip.Canceller mCancel = new StateZip.Canceller();
        Running(String replyId) { mReplyId = replyId; }
    }

    @Override
    public void onReceive(Context context, final Intent intent) {
        final PendingResult pending = goAsync();
        final Context appContext = context.getApplicationContext();
        new Thread( () -> {
            try {
                handle( appContext, intent );
            } catch ( Throwable t ) {
                Log.e( TAG, "unhandled", t );
            } finally {
                pending.finish();
            }
        }, "handyrss-state-export" ).start();
    }

    // ---- request handling ----------------------------------------------------------------------

    private void handle(Context context, Intent intent) {
        final String action       = intent.getAction();
        final String replyAction  = intent.getStringExtra( EXTRA_REPLY_ACTION );
        final String replyPackage = intent.getStringExtra( EXTRA_REPLY_PACKAGE );
        final String replyId      = intent.getStringExtra( EXTRA_REPLY_ID );

        // CANCEL_EXPORT is fire-and-forget — it needs no reply address and is answered with
        // nothing at all, so it is handled ahead of the reply-address guard below. Still gated by
        // the same switch + token as everything else, and a silent no-op when nothing is running.
        if ( ACTION_CANCEL_EXPORT.equals( action ) ) {
            if ( AutomationAuth.isEnabled() && AutomationAuth.check( intent.getStringExtra( EXTRA_TOKEN ) ) )
                cancelExport( replyId );
            return;
        }

        // Without a reply address there is nobody to answer; refuse to do any work.
        if ( TextUtils.isEmpty( replyAction ) || TextUtils.isEmpty( replyPackage ) || TextUtils.isEmpty( replyId ) ) {
            Log.w( TAG, action + ": missing reply_action/reply_package/reply_id — ignored" );
            return;
        }

        final Replier replier = new Replier( context, replyAction, replyPackage, replyId );

        if ( !AutomationAuth.isEnabled() ) {
            replier.send( "ERROR:automation disabled" );
            return;
        }
        if ( !AutomationAuth.check( intent.getStringExtra( EXTRA_TOKEN ) ) ) {
            replier.send( "ERROR:bad token" );
            return;
        }

        if ( ACTION_LIST_CATEGORIES.equals( action ) ) {
            replier.send( "OK:" + StateZip.categoryLines( context ) );
            return;
        }
        if ( ACTION_EXPORT_STATE.equals( action ) ) {
            exportState( context, intent, replier );
            return;
        }
        replier.send( "ERROR:unknown action " + action );
    }

    /**
     * Signal the running export to unwind. Sends no reply of its own: the terminal
     * `ERROR:cancelled` belongs to the ORIGINAL request and is sent by the export thread as it
     * unwinds, through the same Replier whose AtomicBoolean keeps it from racing a success.
     *
     * Safe at any time — nothing running, or an export that already finished, is a silent no-op.
     */
    private static void cancelExport(String replyId) {
        final Running running = sRunning.get();
        if ( running == null ) {
            Log.i( TAG, "cancel: nothing running — no-op" );
            return;
        }
        if ( !TextUtils.isEmpty( replyId ) && !replyId.equals( running.mReplyId ) ) {
            Log.i( TAG, "cancel: " + replyId + " is not the running export — no-op" );
            return;
        }
        Log.i( TAG, "cancel: unwinding " + running.mReplyId );
        running.mCancel.cancel();
    }

    private void exportState(Context context, Intent intent, Replier replier) {
        // --- which categories -------------------------------------------------------------------
        final Set<Integer> cats = new LinkedHashSet<>();
        final String items = intent.getStringExtra( EXTRA_ITEMS );
        if ( TextUtils.isEmpty( items ) ) {
            for ( int cat : StateZip.ORDER )
                cats.add( cat );
        } else {
            final List<String> unknown = new ArrayList<>();
            for ( String raw : items.split( "," ) ) {
                final String id = raw.trim();
                if ( id.isEmpty() )
                    continue;
                final int cat = StateZip.catOf( id );
                if ( cat < 0 )
                    unknown.add( id );
                else
                    cats.add( cat );
            }
            if ( !unknown.isEmpty() ) {
                replier.send( "ERROR:unknown category in items: " + TextUtils.join( ",", unknown ) );
                return;
            }
            if ( cats.isEmpty() ) {
                replier.send( "ERROR:no categories selected" );
                return;
            }
        }

        // --- where it goes: path extra -> configured export directory -> error --------------------
        final String rawPath = intent.getStringExtra( EXTRA_PATH );
        final String safDir = OPML.getEximportDir();
        final boolean wantRawPath = !TextUtils.isEmpty( rawPath );
        if ( wantRawPath && !canWriteRawPath( context ) && safDir.isEmpty() ) {
            replier.send( "ERROR:no-storage-access" );
            return;
        }
        final boolean useRawPath = wantRawPath && canWriteRawPath( context );
        if ( !useRawPath && safDir.isEmpty() ) {
            replier.send( "ERROR:no-directory" );
            return;
        }

        final String fileName = Eximport.EXPORT_FILE_PREFIX
                + new SimpleDateFormat( "yyyy-MM-dd_HH-mm-ss", Locale.US ).format( new Date() )
                + Eximport.EXPORT_FILE_EXT;

        final ProgressSender progress = new ProgressSender( context,
                intent.getStringExtra( EXTRA_PROGRESS_ACTION ),
                intent.getStringExtra( EXTRA_REPLY_PACKAGE ),
                intent.getStringExtra( EXTRA_REPLY_ID ) );

        // One export at a time — the contract forbids two, and the cancel path relies on it.
        final Running running = new Running( intent.getStringExtra( EXTRA_REPLY_ID ) );
        if ( !sRunning.compareAndSet( null, running ) ) {
            replier.send( "ERROR:export already running" );
            return;
        }

        try {
            final int count;
            final String reportedPath;
            final long bytes;

            // Everything is written to `<final-name>.part` and renamed only once the archive is
            // whole, so a cancel or a failure leaves the directory exactly as it was found.
            if ( useRawPath ) {
                final File dir = new File( rawPath );
                if ( !dir.exists() && !dir.mkdirs() )
                    throw new Exception( "cannot create " + rawPath );
                final File out = new File( dir, fileName );
                final File part = new File( dir, fileName + Eximport.EXPORT_PART_SUFFIX );
                boolean complete = false;
                try {
                    final FileOutputStream fos = new FileOutputStream( part );
                    try {
                        count = StateZip.write( cats, fos, progress, running.mCancel );
                    } finally {
                        fos.close();
                    }
                    if ( !part.renameTo( out ) )
                        throw new Exception( "cannot rename " + part.getName() );
                    complete = true;
                } finally {
                    if ( !complete )
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                }
                reportedPath = out.getAbsolutePath();
                bytes = out.length();
            } else {
                final DocumentFile dirDoc = DocumentFile.fromTreeUri( context, Uri.parse( safDir ) );
                if ( dirDoc == null || !dirDoc.canWrite() )
                    throw new Exception( "export directory not writable" );
                // octet-stream keeps the exact name (a typed mime would append its own extension)
                final DocumentFile file = dirDoc.createFile( "application/octet-stream",
                        fileName + Eximport.EXPORT_PART_SUFFIX );
                if ( file == null )
                    throw new Exception( "cannot create export file" );
                boolean complete = false;
                try {
                    final OutputStream os = context.getContentResolver().openOutputStream( file.getUri() );
                    try {
                        count = StateZip.write( cats, os, progress, running.mCancel );
                    } finally {
                        if ( os != null )
                            os.close();
                    }
                    if ( !file.renameTo( fileName ) )
                        throw new Exception( "cannot rename " + fileName + Eximport.EXPORT_PART_SUFFIX );
                    complete = true;
                } finally {
                    if ( !complete )
                        file.delete();
                }
                reportedPath = file.getUri().toString();
                bytes = file.length();
            }

            progress.sendFinal( count );
            replier.send( "OK:" + reportedPath + "|" + bytes + "|" + humanSize( bytes ) + "|" + count + " categories" );
        } catch ( StateZip.CancelledException e ) {
            // The terminal reply for the ORIGINAL request; the partial file is already gone.
            Log.i( TAG, "export cancelled" );
            replier.send( "ERROR:cancelled" );
        } catch ( Throwable t ) {
            Log.e( TAG, "export failed", t );
            replier.send( "ERROR:" + shortReason( t ) );
        } finally {
            sRunning.compareAndSet( running, null );
        }
    }

    // ---- storage -------------------------------------------------------------------------------

    /** All-Files-Access on API 30+, the legacy runtime grant below it. */
    private static boolean canWriteRawPath(Context context) {
        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.R )
            return Environment.isExternalStorageManager();
        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.M )
            return context.checkSelfPermission( Manifest.permission.WRITE_EXTERNAL_STORAGE )
                    == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    /** `4.6 MB`, `1.20 GB` — the caller cannot stat the file, so we compute the display form too. */
    static String humanSize(long bytes) {
        if ( bytes >= 1024L * 1024L * 1024L )
            return String.format( Locale.US, "%.2f GB", bytes / (double) ( 1024L * 1024L * 1024L ) );
        if ( bytes >= 1024L * 1024L )
            return String.format( Locale.US, "%.1f MB", bytes / (double) ( 1024L * 1024L ) );
        if ( bytes >= 1024L )
            return String.format( Locale.US, "%.1f KB", bytes / (double) 1024L );
        return bytes + " B";
    }

    private static String shortReason(Throwable t) {
        final String msg = t.getMessage();
        return TextUtils.isEmpty( msg ) ? t.getClass().getSimpleName() : msg.replace( '\n', ' ' );
    }

    // ---- reply + progress ----------------------------------------------------------------------

    /** Exactly one terminal reply per request — an async success and a sync error cannot both fire. */
    private static class Replier {
        private final Context mContext;
        private final String mAction, mPackage, mId;
        private final AtomicBoolean mSent = new AtomicBoolean( false );

        Replier(Context context, String action, String pkg, String id) {
            mContext = context; mAction = action; mPackage = pkg; mId = id;
        }

        void send(String result) {
            if ( !mSent.compareAndSet( false, true ) ) {
                Log.w( TAG, "duplicate reply suppressed: " + result );
                return;
            }
            Log.i( TAG, mId + " -> " + result );
            mContext.sendBroadcast( new Intent( mAction )
                    .setPackage( mPackage )
                    .addFlags( Intent.FLAG_INCLUDE_STOPPED_PACKAGES )
                    .putExtra( "reply_id", mId )
                    .putExtra( "result", result ) );
        }
    }

    /** Real counts, never a percentage; throttled to one every 500 ms plus a final one. */
    private static class ProgressSender implements StateZip.Progress {
        private final Context mContext;
        private final String mAction, mPackage, mId;
        private long mLastSentAt = 0;

        ProgressSender(Context context, String action, String pkg, String id) {
            mContext = context; mAction = action; mPackage = pkg; mId = id;
        }

        @Override
        public void on(long current, long total, String unit, String text) {
            final long now = System.currentTimeMillis();
            if ( now - mLastSentAt < PROGRESS_MIN_INTERVAL_MS )
                return;
            mLastSentAt = now;
            send( current, total, unit, text );
        }

        void sendFinal(long total) {
            final String unit = mContext.getString( ru.yanus171.feedexfork.R.string.automation_unit_categories );
            send( total, total, unit, unit + " " + total + "/" + total );
        }

        private void send(long current, long total, String unit, String text) {
            if ( TextUtils.isEmpty( mAction ) )
                return;
            mContext.sendBroadcast( new Intent( mAction )
                    .setPackage( mPackage )
                    .addFlags( Intent.FLAG_INCLUDE_STOPPED_PACKAGES )
                    .putExtra( "reply_id", mId )
                    .putExtra( "app", MainApplication.getContext()
                            .getString( ru.yanus171.feedexfork.R.string.app_name ) )
                    .putExtra( "text", text )
                    .putExtra( "current", current )
                    .putExtra( "total", total )
                    .putExtra( "unit", unit ) );
        }
    }
}

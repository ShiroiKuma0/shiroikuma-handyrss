package ru.yanus171.feedexfork.service;

import static ru.yanus171.feedexfork.MainApplication.OPERATION_NOTIFICATION_CHANNEL_ID;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ru.yanus171.feedexfork.Constants;
import ru.yanus171.feedexfork.MainApplication;
import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.parser.OPML;
import ru.yanus171.feedexfork.utils.Eximport;
import ru.yanus171.feedexfork.utils.StateZip;
import ru.yanus171.feedexfork.view.StatusText;

/**
 * HandyRss: where the 保存復元 export actually RUNS.
 *
 * A BroadcastReceiver cannot hold it. `goAsync()` does not extend the broadcast window (~10 s
 * foreground, ~60 s background), and this app's export — thousands of images plus a full article
 * corpus — is orders of magnitude past that: the system raises an ANR against us and may kill the
 * process mid-export, so nothing replies, a `.part` is left behind, and the caller's batch waits on
 * a dead process. StateExportReceiver now only checks the gate, validates the request and starts
 * this service.
 *
 * Not exported: a third-party app cannot start it, which is precisely why CANCEL_EXPORT keeps
 * arriving at the receiver, which signals this service internally.
 */
public class StateExportService extends Service {

    private static final String TAG = "HandyRssAutomation";

    static final String ACTION_RUN    = "shiroikuma.handyrss.internal.RUN_EXPORT";
    static final String ACTION_CANCEL = "shiroikuma.handyrss.internal.CANCEL_EXPORT";

    static final String EXTRA_TOKEN           = "token";
    static final String EXTRA_PATH            = "path";
    static final String EXTRA_ITEMS           = "items";
    static final String EXTRA_CATS            = "cats";
    static final String EXTRA_PROGRESS_ACTION = "progress_action";
    static final String EXTRA_REPLY_ACTION    = "reply_action";
    static final String EXTRA_REPLY_PACKAGE   = "reply_package";
    static final String EXTRA_REPLY_ID        = "reply_id";

    private static final long PROGRESS_MIN_INTERVAL_MS = 500;
    /** A broadcast is the caller's proof we are alive; it presumes us dead well before this. */
    private static final long PROGRESS_HEARTBEAT_MS = 25_000;

    /**
     * The one export that may be in flight. Two at once are forbidden, which is what makes a
     * CANCEL_EXPORT carrying no `reply_id` unambiguous — there is only ever one thing to cancel.
     */
    private static final AtomicReference<Running> sRunning = new AtomicReference<>( null );

    private static class Running {
        final String mReplyId;
        final StateZip.Canceller mCancel = new StateZip.Canceller();
        Running(String replyId) { mReplyId = replyId; }
    }

    /**
     * Signal the running export to unwind. Sends no reply of its own: the terminal
     * `ERROR:cancelled` belongs to the ORIGINAL request and is sent by the export thread as it
     * unwinds, through the same Replier whose AtomicBoolean keeps it from racing a success.
     *
     * Safe at any time — nothing running, or an export that already finished, is a silent no-op.
     */
    static void cancelRunning(String replyId) {
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

    // ---- service lifecycle -------------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        // Within 5 s of the service starting, on every entry point — including a stale cancel.
        startForeground( Constants.NOTIFICATION_ID_EXPORT_SERVICE, notification( this ) );

        final String action = intent == null ? null : intent.getAction();

        if ( ACTION_CANCEL.equals( action ) ) {
            // The notification's own stop button, and the receiver's CANCEL_EXPORT, share this path.
            cancelRunning( intent.getStringExtra( EXTRA_REPLY_ID ) );
            if ( sRunning.get() == null )
                finish();   // nothing was running: do not linger in the foreground
            return START_NOT_STICKY;
        }
        if ( !ACTION_RUN.equals( action ) ) {
            finish();
            return START_NOT_STICKY;
        }

        // With the screen off EMUI dozes the CPU and the export simply stops part-way, with no
        // crash and nothing in the log. A partial wakelock is the difference between finishing and
        // silently truncating.
        final PowerManager pm = (PowerManager) getSystemService( Context.POWER_SERVICE );
        final PowerManager.WakeLock wakeLock = pm == null ? null
                : pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, "handyrss:state-export" );
        if ( wakeLock != null )
            wakeLock.acquire( 60 * 60 * 1000L );

        warnIfBatteryOptimised( this );

        final Intent request = intent;
        new Thread( () -> {
            try {
                export( request );
            } catch ( Throwable t ) {
                Log.e( TAG, "unhandled", t );
            } finally {
                if ( wakeLock != null && wakeLock.isHeld() )
                    wakeLock.release();
                finish();
            }
        }, "handyrss-state-export" ).start();

        return START_NOT_STICKY;
    }

    /**
     * Bare stopSelf(), not stopSelf(startId): a CANCEL arriving mid-export delivers a NEWER startId,
     * and stopSelf(startId) only stops on the most recent one — the service would linger in the
     * foreground for good after every cancel.
     */
    private void finish() {
        stopForeground( true );
        stopSelf();
    }

    private static Notification notification(Context context) {
        final int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        // A PendingIntent runs with OUR identity, so it may start this non-exported service.
        final PendingIntent cancelPI = PendingIntent.getService( context, 0,
                new Intent( context, StateExportService.class ).setAction( ACTION_CANCEL ), flags );
        return StatusText.GetNotification( "", context.getString( R.string.automation_export_title ),
                R.drawable.refresh, OPERATION_NOTIFICATION_CHANNEL_ID, cancelPI );
    }

    /**
     * A long export needs to survive the screen going off. The battery-optimisation exemption is
     * the part an app can ask for; on Huawei the decisive setting is additionally
     * アプリ起動管理 → 手動管理 with バックグラウンドで実行 ON, which no app can set for itself — hence
     * the wording. Offered as a notification, never a background activity start (which the system
     * would block anyway), and it never blocks the export.
     */
    public static void warnIfBatteryOptimised(Context context) {
        try {
            if ( Build.VERSION.SDK_INT < Build.VERSION_CODES.M )
                return;
            final PowerManager pm = (PowerManager) context.getSystemService( Context.POWER_SERVICE );
            if ( pm == null || pm.isIgnoringBatteryOptimizations( context.getPackageName() ) )
                return;
            final Intent settings = new Intent( Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS )
                    .setData( Uri.parse( "package:" + context.getPackageName() ) )
                    .addFlags( Intent.FLAG_ACTIVITY_NEW_TASK );
            if ( settings.resolveActivity( context.getPackageManager() ) == null )
                return;
            final int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            Constants.NOTIF_MGR.notify( Constants.NOTIFICATION_ID_EXPORT_BATTERY,
                    StatusText.GetNotification( context.getString( R.string.automation_battery_text ),
                            context.getString( R.string.automation_battery_title ),
                            R.drawable.refresh, OPERATION_NOTIFICATION_CHANNEL_ID,
                            PendingIntent.getActivity( context, 0, settings, flags ) ) );
        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }

    // ---- the export --------------------------------------------------------------------------

    private void export(Intent intent) {
        final Context context = getApplicationContext();
        final String replyId = intent.getStringExtra( EXTRA_REPLY_ID );
        final Replier replier = new Replier( context,
                intent.getStringExtra( EXTRA_REPLY_ACTION ),
                intent.getStringExtra( EXTRA_REPLY_PACKAGE ), replyId );

        final Set<Integer> cats = new LinkedHashSet<>();
        final int[] raw = intent.getIntArrayExtra( EXTRA_CATS );
        if ( raw != null )
            for ( int cat : raw )
                cats.add( cat );
        if ( cats.isEmpty() ) {
            replier.send( "ERROR:no categories selected" );
            return;
        }

        // --- where it goes: path extra -> configured export directory -> error ------------------
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
                intent.getStringExtra( EXTRA_REPLY_PACKAGE ), replyId );

        // One export at a time — the contract forbids two, and the cancel path relies on it.
        final Running running = new Running( replyId );
        if ( !sRunning.compareAndSet( null, running ) ) {
            replier.send( "ERROR:export already running" );
            return;
        }
        progress.startHeartbeat();

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
            progress.stopHeartbeat();
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
    public static String humanSize(long bytes) {
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

    /**
     * Exactly one terminal reply per request — an async success and a sync error cannot both fire.
     *
     * Public and shared with the automation data door (AutomationDataService), which correlates on
     * `job_id` rather than `reply_id`: one implementation of the EMUI-proven fresh-broadcast reply,
     * not two that drift apart.
     */
    public static class Replier {
        private final Context mContext;
        private final String mAction, mPackage, mId, mIdExtra;
        private final AtomicBoolean mSent = new AtomicBoolean( false );

        public Replier(Context context, String action, String pkg, String id) {
            this( context, action, pkg, id, "reply_id" );
        }

        public Replier(Context context, String action, String pkg, String id, String idExtra) {
            mContext = context; mAction = action; mPackage = pkg; mId = id; mIdExtra = idExtra;
        }

        public void send(String result) {
            if ( !mSent.compareAndSet( false, true ) ) {
                Log.w( TAG, "duplicate reply suppressed: " + result );
                return;
            }
            Log.i( TAG, mId + " -> " + result );
            // A caller with no reply address (the data door's reply_action/reply_package are both
            // optional) still gets its one-reply guard flipped, so nothing fires later either.
            if ( TextUtils.isEmpty( mAction ) || TextUtils.isEmpty( mPackage ) )
                return;
            final Intent reply = new Intent( mAction )
                    .setPackage( mPackage )
                    .addFlags( Intent.FLAG_INCLUDE_STOPPED_PACKAGES )
                    .putExtra( "reply_id", mId )
                    .putExtra( "result", result );
            // The data door correlates on job_id; every §1 caller reads reply_id. Carrying the same
            // id under both names costs one extra and spares each side a special case.
            if ( !"reply_id".equals( mIdExtra ) )
                reply.putExtra( mIdExtra, mId );
            mContext.sendBroadcast( reply );
        }
    }

    /**
     * Real counts, never a percentage. Throttled to one per 500 ms — but the throttle may never
     * suppress a heartbeat, so anything older than PROGRESS_HEARTBEAT_MS goes out regardless.
     *
     * `item` is what the panel highlights by; `current`/`total` stay the honest 1-based position of
     * the category being written, paired with the label in `text`.
     */
    public static class ProgressSender implements StateZip.Progress {
        private final Context mContext;
        private final String mAction, mPackage, mId, mIdExtra;
        private volatile long mLastSentAt = 0;
        private volatile long mBytes = -1, mBytesTotal = -1;
        private volatile boolean mBeating = false;

        // The last line sent, so the watchdog can repeat it verbatim.
        private volatile String mItem = "", mUnit = "", mText = "";
        private volatile long mCurrent = 0, mTotal = 0;

        public ProgressSender(Context context, String action, String pkg, String id) {
            this( context, action, pkg, id, "reply_id" );
        }

        public ProgressSender(Context context, String action, String pkg, String id, String idExtra) {
            mContext = context; mAction = action; mPackage = pkg; mId = id; mIdExtra = idExtra;
        }

        /**
         * The throttle alone cannot keep a heartbeat: a floor inside on() only helps when something
         * is still calling on(), and the ways this export goes quiet — one enormous file being
         * copied, a slow cursor — are exactly the ways nothing calls it. So a watchdog repeats the
         * last line whenever the channel has been silent, and the caller keeps hearing a pulse.
         */
        public void startHeartbeat() {
            if ( TextUtils.isEmpty( mAction ) || mBeating )
                return;
            mBeating = true;
            final Thread t = new Thread( () -> {
                while ( mBeating ) {
                    try {
                        Thread.sleep( 5000 );
                    } catch ( InterruptedException e ) {
                        return;
                    }
                    if ( mBeating && System.currentTimeMillis() - mLastSentAt >= PROGRESS_HEARTBEAT_MS )
                        send( mItem, mCurrent, mTotal, mUnit, mText );
                }
            }, "handyrss-export-heartbeat" );
            t.setDaemon( true );
            t.start();
        }

        public void stopHeartbeat() { mBeating = false; }

        @Override
        public void on(String item, long current, long total, String unit, String text) {
            mItem = item; mCurrent = current; mTotal = total; mUnit = unit; mText = text;
            if ( System.currentTimeMillis() - mLastSentAt < PROGRESS_MIN_INTERVAL_MS )
                return;
            send( item, current, total, unit, text );
        }

        @Override
        public void bytes(long done, long total) {
            mBytes = done;
            mBytesTotal = total;
        }

        public void sendFinal(long total) {
            final String unit = mContext.getString( R.string.automation_unit_categories );
            // Nothing is being written any more — an empty item moves no highlight.
            send( "", total, total, unit, unit + " " + total + "/" + total );
        }

        private synchronized void send(String item, long current, long total, String unit, String text) {
            if ( TextUtils.isEmpty( mAction ) )
                return;
            final Intent intent = new Intent( mAction )
                    .setPackage( mPackage )
                    .addFlags( Intent.FLAG_INCLUDE_STOPPED_PACKAGES )
                    .putExtra( "reply_id", mId )
                    .putExtra( "app", MainApplication.getContext().getString( R.string.app_name ) )
                    .putExtra( "text", text )
                    .putExtra( "current", current )
                    .putExtra( "total", total )
                    .putExtra( "unit", unit );
            if ( !"reply_id".equals( mIdExtra ) )
                intent.putExtra( mIdExtra, mId );
            if ( !TextUtils.isEmpty( item ) )
                intent.putExtra( "item", item );
            if ( mBytes >= 0 )
                intent.putExtra( "bytes", mBytes );
            if ( mBytesTotal > 0 )
                intent.putExtra( "bytes_total", mBytesTotal );
            mLastSentAt = System.currentTimeMillis();
            mContext.sendBroadcast( intent );
        }
    }
}

package ru.yanus171.feedexfork.automation;

import static ru.yanus171.feedexfork.MainApplication.OPERATION_NOTIFICATION_CHANNEL_ID;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ru.yanus171.feedexfork.Constants;
import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.service.StateExportService;
import ru.yanus171.feedexfork.utils.StateZip;
import ru.yanus171.feedexfork.view.StatusText;

/**
 * HandyRss: where a data-door export or import actually runs.
 *
 * <h3>Why a foreground service and not the provider call</h3>
 *
 * The call returns in milliseconds; this can run for minutes — this app's archive is a full article
 * corpus plus thousands of images. Two hard reasons it cannot be done anywhere cheaper:
 *
 * <ul>
 *   <li><b>A binder call holds the caller.</b> 応用管理 is drawing a list; a multi-minute
 *       synchronous call would freeze its UI, report no progress and refuse cancellation.</li>
 *   <li><b>A backgrounded app writing for minutes is frozen mid-stream on this phone</b>, which
 *       yields a truncated archive underneath a success reply — the worst possible failure, because
 *       it is indistinguishable from a good backup until the day it is restored. Hence the partial
 *       wakelock too: with the screen off EMUI dozes the CPU and the write simply stops part-way,
 *       with no crash and nothing in the log (the same lesson StateExportService paid for).</li>
 * </ul>
 *
 * <h3>The descriptor</h3>
 *
 * Already duplicated by {@link AutomationProvider} before it got here, because the original belongs
 * to the binder transaction and is closed the moment {@code call()} returns. This service owns the
 * copy and closes it in a {@code finally} — leaking one would hold the caller's file open
 * indefinitely, and the caller cannot checksum or encrypt a file that is still open.
 */
public class AutomationDataService extends Service {

    private static final String TAG = "HandyRssAutomation";

    private static final String ACTION_CANCEL = "shiroikuma.handyrss.internal.CANCEL_AUTOMATION_DATA";

    private static final String EXTRA_JOB       = "job";
    private static final String EXTRA_IMPORTING = "importing";

    /**
     * The descriptor's way across, because an Intent is the wrong vehicle for one: a
     * ParcelFileDescriptor in an Intent extra is duplicated by the system on delivery and the copy's
     * lifetime stops being ours to reason about. A map keyed by the job id keeps exactly one open
     * descriptor with exactly one owner — this service, which closes it in a finally.
     */
    private static final ConcurrentHashMap<String, ParcelFileDescriptor> HANDOVER = new ConcurrentHashMap<>();

    public static void start(Context context, String jobId, ParcelFileDescriptor fd,
                             boolean importing, Bundle extras) {
        HANDOVER.put( jobId, fd );
        final Intent intent = new Intent( context, AutomationDataService.class )
                .putExtra( EXTRA_JOB, jobId )
                .putExtra( EXTRA_IMPORTING, importing )
                .putExtra( AutomationProvider.KEY_ITEMS, extras == null ? null : extras.getString( AutomationProvider.KEY_ITEMS ) )
                .putExtra( AutomationProvider.KEY_PROGRESS_ACTION, extras == null ? null : extras.getString( AutomationProvider.KEY_PROGRESS_ACTION ) )
                .putExtra( AutomationProvider.KEY_REPLY_ACTION, extras == null ? null : extras.getString( AutomationProvider.KEY_REPLY_ACTION ) )
                .putExtra( AutomationProvider.KEY_REPLY_PACKAGE, extras == null ? null : extras.getString( AutomationProvider.KEY_REPLY_PACKAGE ) );
        try {
            if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O )
                context.startForegroundService( intent );
            else
                context.startService( intent );
        } catch ( Throwable t ) {
            HANDOVER.remove( jobId );   // never strand the caller's descriptor in the map
            throw t;
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Within 5 s of the service starting, on every entry path — a stale cancel included.
        startForeground( Constants.NOTIFICATION_ID_AUTOMATION_DATA, notification( this, false, null ) );

        if ( intent != null && ACTION_CANCEL.equals( intent.getAction() ) ) {
            AutomationJobs.cancel( intent.getStringExtra( EXTRA_JOB ) );
            // The running job sends its own terminal ERROR:cancelled and stops the service; if
            // nothing is running there is nothing to linger for.
            if ( HANDOVER.isEmpty() )
                finish();
            return START_NOT_STICKY;
        }

        final String jobId = intent == null ? null : intent.getStringExtra( EXTRA_JOB );
        final ParcelFileDescriptor fd = jobId == null ? null : HANDOVER.remove( jobId );
        if ( fd == null ) {
            finish();
            return START_NOT_STICKY;
        }

        final boolean importing = intent.getBooleanExtra( EXTRA_IMPORTING, false );

        // Built BEFORE the guarded window below, because nothing in here can throw and because a
        // failure in that window still has to be answered — the caller is waiting on a job id we
        // already handed it.
        final StateExportService.Replier replier = new StateExportService.Replier( getApplicationContext(),
                intent.getStringExtra( AutomationProvider.KEY_REPLY_ACTION ),
                intent.getStringExtra( AutomationProvider.KEY_REPLY_PACKAGE ),
                jobId, AutomationProvider.KEY_JOB_ID );

        // Same heartbeat machinery as the broadcast export — one implementation, so the 30 s
        // liveness promise cannot drift between the two doors. Correlated on job_id here.
        final StateExportService.ProgressSender progress = new StateExportService.ProgressSender(
                getApplicationContext(),
                intent.getStringExtra( AutomationProvider.KEY_PROGRESS_ACTION ),
                intent.getStringExtra( AutomationProvider.KEY_REPLY_PACKAGE ),
                jobId, AutomationProvider.KEY_JOB_ID );

        final String items = intent.getStringExtra( AutomationProvider.KEY_ITEMS );

        final PowerManager pm = (PowerManager) getSystemService( Context.POWER_SERVICE );
        final PowerManager.WakeLock wakeLock = pm == null ? null
                : pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, "handyrss:automation-data" );

        // ONE flag for the whole window between draining HANDOVER and the thread owning the
        // descriptor — not a guard per failure. The descriptor has already left the map by now, so
        // nothing else in the process would ever close it, and there is more than one way out of
        // here: startForeground can be refused on API 31+ (a provider call() start IS a background
        // start), wakeLock.acquire can throw SecurityException, Thread.start can throw. Whichever
        // it is, the caller's file must not be left open and the caller must not be left waiting.
        boolean handedOff = false;
        try {
            startForeground( Constants.NOTIFICATION_ID_AUTOMATION_DATA, notification( this, importing, jobId ) );
            if ( wakeLock != null )
                wakeLock.acquire( 60 * 60 * 1000L );

            new Thread( () -> {
                progress.startHeartbeat();
                try {
                    if ( importing )
                        runImport( fd, replier );
                    else
                        runExport( jobId, fd, items, replier, progress );
                } catch ( StateZip.CancelledException e ) {
                    replier.send( "ERROR:cancelled" );
                } catch ( Throwable t ) {
                    Log.e( TAG, "automation data job failed", t );
                    replier.send( "ERROR:" + shortReason( t ) );
                } finally {
                    progress.stopHeartbeat();
                    AutomationJobs.finish( jobId );
                    // Ours to close, always: a leaked descriptor holds the caller's file open, and
                    // the caller cannot checksum or encrypt a file that is still open.
                    try { fd.close(); } catch ( Exception ignored ) {}
                    if ( wakeLock != null && wakeLock.isHeld() )
                        wakeLock.release();
                    finish();
                }
            }, "handyrss-automation-data" ).start();
            handedOff = true;
        } catch ( Throwable t ) {
            Log.e( TAG, "cannot hand off automation data job", t );
            StateExportService.warnIfBatteryOptimised( this );
            replier.send( "ERROR:cannot start data job: " + t.getClass().getSimpleName() );
        } finally {
            if ( !handedOff ) {
                AutomationJobs.finish( jobId );
                try { fd.close(); } catch ( Exception ignored ) {}
                if ( wakeLock != null && wakeLock.isHeld() )
                    wakeLock.release();
                finish();
            }
        }

        return START_NOT_STICKY;
    }

    // ---- export ----------------------------------------------------------------------------------

    private void runExport(String jobId, ParcelFileDescriptor fd, String items,
                           StateExportService.Replier replier,
                           StateExportService.ProgressSender progress) throws Exception {
        final Set<Integer> cats = resolve( items );
        if ( cats == null ) {
            replier.send( "ERROR:unknown category in items: " + items );
            return;
        }

        // Counted as it goes rather than stat'ed afterwards: the caller owns the file and we may not
        // be able to see it at all — it can be an anonymous pipe, or a descriptor into a directory
        // this app cannot list. A named class rather than a capturing anonymous one: the family hit
        // an AGP lint analyser crash on the anonymous shape, and the boxed counter it needed was
        // the worse code anyway.
        final CountingStream counting =
                new CountingStream( new ParcelFileDescriptor.AutoCloseOutputStream( fd ) );
        final int count;
        try {
            count = StateZip.write( cats, counting, progress, AutomationJobs.canceller( jobId ) );
        } finally {
            counting.close();
        }

        progress.sendFinal( count );
        replier.send( "OK:" + counting.mWritten + "|" + StateExportService.humanSize( counting.mWritten )
                + "|" + count + " categories" );
    }

    // ---- import ----------------------------------------------------------------------------------

    /**
     * Spool the whole archive to a cache file before touching anything.
     *
     * The reference implementation reads it into a byte array; this app's archive carries the image
     * and article corpus and would not fit, so the bound is disk rather than RAM. The point is the
     * same and is worth the copy: a partial read that failed halfway would otherwise import half an
     * archive, and a half-restored app is worse than one that refused. It also lets the PK\003\004
     * sniff happen before the first file is written.
     */
    private void runImport(ParcelFileDescriptor fd, StateExportService.Replier replier) throws Exception {
        final File spool = new File( getCacheDir(), "automation-import.zip" );
        try {
            long total = 0;
            final InputStream in = new BufferedInputStream( new ParcelFileDescriptor.AutoCloseInputStream( fd ) );
            try {
                final FileOutputStream out = new FileOutputStream( spool );
                try {
                    final byte[] buf = new byte[8192];
                    int n;
                    while ( ( n = in.read( buf ) ) > 0 ) {
                        out.write( buf, 0, n );
                        total += n;
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if ( total == 0 ) {
                replier.send( "ERROR:empty archive" );
                return;
            }

            final byte[] head = new byte[4];
            final FileInputStream sniff = new FileInputStream( spool );
            try {
                //noinspection ResultOfMethodCallIgnored
                sniff.read( head );
            } finally {
                sniff.close();
            }
            if ( !StateZip.isZip( head ) ) {
                // The data door takes state ZIPs only. A legacy plain-OPML backup still imports
                // through the app's own Export/Import panel, which sniffs and routes for itself.
                replier.send( "ERROR:not a " + StateZip.FORMAT + " archive" );
                return;
            }

            // Every category we know about: StateZip.read skips whatever the archive does not carry,
            // so asking for all of them restores exactly what is in it and nothing else.
            final Set<Integer> cats = new LinkedHashSet<>();
            for ( int cat : StateZip.ORDER )
                cats.add( cat );

            final FileInputStream is = new FileInputStream( spool );
            try {
                StateZip.read( is, cats );
            } finally {
                is.close();
            }
            // FLUSH BEFORE ANSWERING. 応用管理 force-stops us the instant we reply success —
            // deliberately, and on its side, because a running process writes its cached
            // SharedPreferences back out at orderly shutdown and would silently undo the import.
            // But that force-stop is a SIGKILL, and StateZip.applyPrefs ends in apply(), which is
            // asynchronous: the queued disk write is normally flushed at a lifecycle transition
            // that a SIGKILL never reaches. Losing it means reporting success over settings that
            // were never persisted — invisible in testing, because a hand-run import is followed by
            // a normal lifecycle that flushes it. An empty commit() on the same preferences file
            // blocks until everything queued against it is on disk. (The same trick GeneralPrefs-
            // Fragment already uses before Process.killProcess.) The other restore writes are
            // synchronous already: extractTo closes each FileOutputStream, and the OPML import goes
            // through SQLite.
            PreferenceManager.getDefaultSharedPreferences( getApplicationContext() ).edit().commit();
            replier.send( "OK:" + total + "|" + StateExportService.humanSize( total ) + "|restored" );
        } finally {
            //noinspection ResultOfMethodCallIgnored
            spool.delete();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Counts what it forwards, so the reply can report real bytes for a file we cannot stat. */
    private static class CountingStream extends OutputStream {
        private final OutputStream mOut;
        long mWritten = 0;

        CountingStream(OutputStream out) { mOut = out; }

        @Override public void write(int b) throws java.io.IOException {
            mOut.write( b );
            mWritten++;
        }
        @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
            mOut.write( b, off, len );
            mWritten += len;
        }
        @Override public void flush() throws java.io.IOException { mOut.flush(); }
        @Override public void close() throws java.io.IOException { mOut.close(); }
    }

    /** null when `items` names a category this app does not have. Absent/empty = our default set. */
    private static Set<Integer> resolve(String items) {
        final Set<Integer> cats = new LinkedHashSet<>();
        if ( TextUtils.isEmpty( items ) ) {
            for ( int cat : StateZip.ORDER )
                cats.add( cat );
            return cats;
        }
        for ( String rawId : items.split( "," ) ) {
            final String id = rawId.trim();
            if ( id.isEmpty() )
                continue;
            final int cat = StateZip.catOf( id );
            if ( cat < 0 )
                return null;
            cats.add( cat );
        }
        return cats.isEmpty() ? null : cats;
    }

    private static String shortReason(Throwable t) {
        final String msg = t.getMessage();
        return TextUtils.isEmpty( msg ) ? t.getClass().getSimpleName() : msg.replace( '\n', ' ' );
    }

    private void finish() {
        stopForeground( true );
        // Bare stopSelf(), not stopSelf(startId): a cancel delivers a NEWER startId and the id-form
        // only stops on the most recent one, so the service would stay foreground for good after
        // every cancel (StateExportService paid for this one).
        stopSelf();
    }

    private static Notification notification(Context context, boolean importing, String jobId) {
        final int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        // A PendingIntent runs with OUR identity, so it may start this non-exported service.
        final PendingIntent cancelPI = jobId == null ? null : PendingIntent.getService( context, 1,
                new Intent( context, AutomationDataService.class )
                        .setAction( ACTION_CANCEL )
                        .putExtra( EXTRA_JOB, jobId ), flags );
        final int title = importing ? R.string.automation_data_import_title
                                    : R.string.automation_data_export_title;
        return StatusText.GetNotification( "", context.getString( title ),
                R.drawable.refresh, OPERATION_NOTIFICATION_CHANNEL_ID, cancelPI );
    }
}

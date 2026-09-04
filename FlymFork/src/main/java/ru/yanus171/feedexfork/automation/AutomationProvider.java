package ru.yanus171.feedexfork.automation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import ru.yanus171.feedexfork.service.StateExportService;
import ru.yanus171.feedexfork.utils.AutomationAuth;
import ru.yanus171.feedexfork.utils.StateZip;

/**
 * HandyRss: the data door — export this app's own state, and put it back, for a caller we can
 * identify. It sits <i>alongside</i> StateExportReceiver; it does not replace it.
 *
 * <h3>Why a provider and not the broadcast receiver next to it</h3>
 *
 * <b>A broadcast cannot tell you who sent it.</b> v1's answer to that was a shared secret, which
 * cannot survive the wipe this feature exists to recover from. A provider gets the caller's identity
 * from the framework for free — see {@link AutomationCallers} for what is actually checked and why a
 * package-name prefix would have been worse than the token it replaced.
 *
 * <b>And a list needs a synchronous answer.</b> 応用管理 draws a row per installed app before any
 * export exists; a broadcast round trip per app to fill a list is the wrong shape entirely.
 *
 * <h3>What does NOT happen here</h3>
 *
 * The payload. {@code call()} validates, starts a foreground service and returns — this app's export
 * is thousands of images and a full article corpus, so minutes inside a binder call would block the
 * caller, report no progress, refuse cancellation and die silently if this process were killed.
 *
 * <h3>Why a descriptor and not a path</h3>
 *
 * Because a backup is not a stable directory while it is being assembled. 応用管理 writes into a
 * temporary path and renames on commit, and it encrypts and checksums <b>per file it knows about</b>
 * — a file this app dropped in itself would be renamed out from under it, would sit in plaintext
 * inside an encrypted backup, and would be unverified rather than verified-and-failing. A descriptor
 * is also a capability that <b>expires when it is closed</b>. Consequence worth having: the
 * automation path no longer needs MANAGE_EXTERNAL_STORAGE (the EXPORT_STATE `path` extra still
 * does).
 *
 * <h3>import lives ONLY here</h3>
 *
 * It never gets a broadcast action. An import overwrites this app's data, and StateExportReceiver is
 * exported with no permission — an import there would let any app on the phone wipe any sister app.
 */
public class AutomationProvider extends ContentProvider {

    public static final String METHOD_DESCRIBE = "describe";
    public static final String METHOD_EXPORT   = "export";
    public static final String METHOD_IMPORT   = "import";
    public static final String METHOD_CANCEL   = "cancel";

    public static final String KEY_RESULT          = "result";
    public static final String KEY_FD              = "fd";
    public static final String KEY_TOKEN           = "token";
    public static final String KEY_JOB_ID          = "job_id";
    public static final String KEY_ITEMS           = "items";
    public static final String KEY_REPLY_ACTION    = "reply_action";
    public static final String KEY_REPLY_PACKAGE   = "reply_package";
    public static final String KEY_PROGRESS_ACTION = "progress_action";

    /**
     * The oldest archive this build can still read.
     *
     * Version skew has a direction: old data into a newer app is normally fine, because an app
     * migrates its own storage; newer data into an older app is not. This field is what lets a
     * caller refuse the second case at discovery time, before anything is streamed.
     */
    private static final int MIN_FORMAT_READABLE = 1;

    @Override
    public boolean onCreate() { return true; }

    /**
     * Every method answers a Bundle with {@link #KEY_RESULT} — {@code OK…} or {@code ERROR:…}, the
     * same vocabulary the broadcast contract uses, so a caller has one grammar to parse rather than
     * two.
     *
     * <b>A refusal is returned, never thrown</b>: an exception across a binder reaches the caller as
     * a RuntimeException with our stack trace in it, which tells 白い熊 nothing and tells a
     * misbehaving caller rather more than it should.
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        final Context ctx = getContext();
        if ( ctx == null )
            return result( "ERROR:not ready" );

        // WHO, before WHAT. A caller we cannot identify gets the same answer whatever it asked for.
        // getCallingPackage() is API 19; below that there is no identity to check and the only
        // honest answer is to refuse — this door is never opened blind.
        if ( Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT )
            return result( "ERROR:caller unknown" );
        final String refusedCaller = AutomationCallers.verify( ctx, getCallingPackage() );
        if ( refusedCaller != null )
            return result( refusedCaller );

        // Then this app's own switches — a token is ignored unless this app asks for one.
        final String refusedAuth = AutomationAuth.refuse( extras == null ? null : extras.getString( KEY_TOKEN ) );
        if ( refusedAuth != null )
            return result( refusedAuth );

        if ( METHOD_DESCRIBE.equals( method ) )
            return result( describe( ctx ) );
        if ( METHOD_EXPORT.equals( method ) )
            return start( ctx, extras, false );
        if ( METHOD_IMPORT.equals( method ) )
            return start( ctx, extras, true );
        if ( METHOD_CANCEL.equals( method ) ) {
            AutomationJobs.cancel( extras == null ? null : extras.getString( KEY_JOB_ID ) );
            return result( "OK:cancelled" );
        }
        return result( "ERROR:unknown method: " + method );
    }

    /**
     * What this app would export, answered without exporting anything.
     *
     * Returned from the call rather than written into the archive, deliberately: 応用管理 must draw a
     * row before an export exists, and at restore must judge compatibility <b>before</b> streaming
     * tens of megabytes into an app that would reject them — which it cannot do if the header is
     * buried inside an encrypted archive.
     */
    private String describe(Context ctx) {
        try {
            final PackageInfo info = ctx.getPackageManager().getPackageInfo( ctx.getPackageName(), 0 );
            final JSONArray contains = new JSONArray();
            // Every category defaults `on` in this app (StateZip.defaultOf), so `contains` is the
            // whole list — short human strings 応用管理 renders verbatim.
            for ( int cat : StateZip.ORDER )
                contains.put( StateZip.labelOf( ctx, cat ) );
            final JSONObject header = new JSONObject();
            header.put( "app_id", ctx.getPackageName() );
            header.put( "version_code", info.versionCode );
            header.put( "version_name", info.versionName == null ? "" : info.versionName );
            header.put( "format", StateZip.VERSION );
            header.put( "min_format_readable", MIN_FORMAT_READABLE );
            // This app writes its defaults lazily and StateZip merges per key, so an import lands
            // correctly on a freshly installed, never-launched install.
            header.put( "requires_launch_first", false );
            header.put( "contains", contains );
            return "OK:" + header.toString();
        } catch ( Exception e ) {
            return "ERROR:cannot describe: " + e.getClass().getSimpleName();
        }
    }

    /**
     * Hand the descriptor to a foreground service and get out of the way.
     *
     * The descriptor is <b>duplicated</b> before it leaves this method. The one in {@code extras}
     * belongs to the binder transaction and is closed when {@code call()} returns; a service reading
     * it afterwards would find it shut. That is a bug you only see under load, so it is not left to
     * the service to remember.
     */
    private Bundle start(Context ctx, Bundle extras, boolean importing) {
        if ( extras == null )
            return result( "ERROR:no descriptor" );
        final ParcelFileDescriptor fd = extras.getParcelable( KEY_FD );
        if ( fd == null )
            return result( "ERROR:no descriptor" );
        final ParcelFileDescriptor dup;
        try {
            dup = fd.dup();
        } catch ( Exception e ) {
            return result( "ERROR:descriptor unusable" );
        }
        final String jobId = AutomationJobs.begin();
        try {
            AutomationDataService.start( ctx, jobId, dup, importing, extras );
        } catch ( Throwable t ) {
            // A provider call() start IS a background start, and API 31+ refuses one with
            // ForegroundServiceStartNotAllowedException unless the app is battery-optimisation
            // exempt. Close our copy rather than leaking the caller's file open, raise the one
            // repair 白い熊 can actually perform, and say why.
            AutomationJobs.finish( jobId );
            try { dup.close(); } catch ( Exception ignored ) {}
            StateExportService.warnIfBatteryOptimised( ctx );
            return result( "ERROR:cannot start data service: " + t.getClass().getSimpleName() );
        }
        return result( "OK:" + jobId );
    }

    private static Bundle result(String value) {
        final Bundle out = new Bundle();
        out.putString( KEY_RESULT, value );
        return out;
    }

    // A provider that is only ever call()ed still has to answer these. Refusing loudly beats
    // returning an empty cursor, which reads downstream as "there is no data" rather than "wrong
    // door".
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException( "automation is call() only" );
    }
    @Override
    public String getType(Uri uri) { return null; }
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException( "automation is call() only" );
    }
    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException( "automation is call() only" );
    }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException( "automation is call() only" );
    }
}

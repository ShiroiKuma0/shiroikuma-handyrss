package ru.yanus171.feedexfork.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ru.yanus171.feedexfork.utils.AutomationAuth;
import ru.yanus171.feedexfork.utils.StateZip;

/**
 * HandyRss: the 保存復元 state-export contract — 白い熊's 自由作業盤 automation app fires a
 * token-gated intent at every sister app, each exports itself headlessly and replies with the
 * written path and size, and 自由作業盤 collects the replies into one summary.
 *
 * Three actions, all exported and gated by AutomationAuth.refuse() — the automation switch, plus
 * the token only when 「Use authorization token?」 is on (never by a manifest permission: the caller
 * cannot hold one). In v2 this receiver is deliberately the UNAUTHENTICATED half of the surface —
 * it only ever writes where it was told to and reports what it did. Everything that moves data
 * through a caller-supplied descriptor lives behind AutomationProvider, which knows who is calling.
 *
 *   shiroikuma.handyrss.action.LIST_CATEGORIES  -> OK: + one `id<TAB>label<TAB>parent<TAB>on|off` line per category
 *   shiroikuma.handyrss.action.EXPORT_STATE     -> writes ONE zip, replies OK:path|bytes|human|n categories
 *   shiroikuma.handyrss.action.CANCEL_EXPORT    -> unwinds the running export; answers NOTHING
 *
 * This receiver does NOT run the export. `goAsync()` does not extend the broadcast window, and an
 * export of thousands of files is orders of magnitude past it — so the gate, the request validation
 * and the hand-off happen here, and StateExportService does the work in the foreground with a
 * wakelock. CANCEL_EXPORT still arrives here because a third-party app cannot start a non-exported
 * service; it signals the service internally.
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

    @Override
    public void onReceive(Context context, final Intent intent) {
        // Everything here is short: the gate, a category-id check, and starting the service. The
        // only work that could outlive the broadcast window has moved to StateExportService.
        try {
            handle( context.getApplicationContext(), intent );
        } catch ( Throwable t ) {
            Log.e( TAG, "unhandled", t );
        }
    }

    // ---- request handling ----------------------------------------------------------------------

    private void handle(Context context, Intent intent) {
        final String action       = intent.getAction();
        final String replyAction  = intent.getStringExtra( StateExportService.EXTRA_REPLY_ACTION );
        final String replyPackage = intent.getStringExtra( StateExportService.EXTRA_REPLY_PACKAGE );
        final String replyId      = intent.getStringExtra( StateExportService.EXTRA_REPLY_ID );
        final String token        = intent.getStringExtra( StateExportService.EXTRA_TOKEN );

        // CANCEL_EXPORT is fire-and-forget — it needs no reply address and is answered with
        // nothing at all, so it is handled ahead of the reply-address guard below. Still gated by
        // the same switch + token, and a silent no-op when nothing is running.
        if ( ACTION_CANCEL_EXPORT.equals( action ) ) {
            if ( AutomationAuth.refuse( token ) == null )
                startService( context, new Intent( context, StateExportService.class )
                        .setAction( StateExportService.ACTION_CANCEL )
                        .putExtra( StateExportService.EXTRA_REPLY_ID, replyId ) );
            return;
        }

        // Without a reply address there is nobody to answer; refuse to do any work.
        if ( TextUtils.isEmpty( replyAction ) || TextUtils.isEmpty( replyPackage ) || TextUtils.isEmpty( replyId ) ) {
            Log.w( TAG, action + ": missing reply_action/reply_package/reply_id — ignored" );
            return;
        }

        final StateExportService.Replier replier =
                new StateExportService.Replier( context, replyAction, replyPackage, replyId );

        // One function, both checks: written out here they drift apart from the provider's copy,
        // and a token sent to an app that does not require one must be IGNORED, never refused.
        final String refusal = AutomationAuth.refuse( token );
        if ( refusal != null ) {
            replier.send( refusal );
            return;
        }

        if ( ACTION_LIST_CATEGORIES.equals( action ) ) {
            replier.send( "OK:" + StateZip.categoryLines( context ) );
            return;
        }
        if ( ACTION_EXPORT_STATE.equals( action ) ) {
            startExport( context, intent, replier );
            return;
        }
        replier.send( "ERROR:unknown action " + action );
    }

    /** Resolve `items` to category constants here, so the service starts with a valid request. */
    private void startExport(Context context, Intent intent, StateExportService.Replier replier) {
        final Set<Integer> cats = new LinkedHashSet<>();
        final String items = intent.getStringExtra( StateExportService.EXTRA_ITEMS );
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

        final int[] raw = new int[cats.size()];
        int i = 0;
        for ( int cat : cats )
            raw[i++] = cat;

        final Intent run = new Intent( context, StateExportService.class )
                .setAction( StateExportService.ACTION_RUN )
                .putExtra( StateExportService.EXTRA_CATS, raw )
                .putExtra( StateExportService.EXTRA_PATH, intent.getStringExtra( StateExportService.EXTRA_PATH ) )
                .putExtra( StateExportService.EXTRA_PROGRESS_ACTION, intent.getStringExtra( StateExportService.EXTRA_PROGRESS_ACTION ) )
                .putExtra( StateExportService.EXTRA_REPLY_ACTION, intent.getStringExtra( StateExportService.EXTRA_REPLY_ACTION ) )
                .putExtra( StateExportService.EXTRA_REPLY_PACKAGE, intent.getStringExtra( StateExportService.EXTRA_REPLY_PACKAGE ) )
                .putExtra( StateExportService.EXTRA_REPLY_ID, intent.getStringExtra( StateExportService.EXTRA_REPLY_ID ) );
        try {
            startService( context, run );
        } catch ( Throwable t ) {
            // Android 12+ refuses a background foreground-service start unless the app is exempt;
            // the battery-optimisation exemption is the part 白い熊 can grant, so surface it here
            // rather than only from inside a service that never got to run.
            Log.e( TAG, "cannot start export service", t );
            StateExportService.warnIfBatteryOptimised( context );
            replier.send( "ERROR:cannot start export service: " + t.getClass().getSimpleName() );
        }
    }

    private static void startService(Context context, Intent intent) {
        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.O )
            context.startForegroundService( intent );
        else
            context.startService( intent );
    }
}

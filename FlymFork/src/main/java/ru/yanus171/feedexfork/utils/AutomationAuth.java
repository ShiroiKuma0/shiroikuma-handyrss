package ru.yanus171.feedexfork.utils;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * HandyRss: the automation gate for the 保存復元 contract (see StateExportReceiver and the
 * automation package's provider).
 *
 * <h3>v2 — the switch is ON and the token is OFF</h3>
 *
 * v1 shipped this app closed: the switch defaulted to false and a caller also had to present a
 * 48-character secret 白い熊 had pasted from here into 自由作業盤. That is the wrong shape for where
 * the family went. <b>A pasted secret cannot survive a wipe</b>, and the case the contract now
 * exists to serve is 応用管理 restoring apps <i>and their data</i> onto a clean phone, where nothing
 * has been configured and nobody has pasted anything. A gate that only works once the phone is
 * already set up is no gate for setting the phone up.
 *
 * So {@link #PREF_ENABLED} now defaults to <b>true</b> and {@link #PREF_REQUIRE_TOKEN} is a new,
 * default-<b>false</b> opt-in. The switch stays a switch rather than being deleted because it is
 * the only way to close this one app off, and a feature that can be turned on but never off is one
 * 白い熊 cannot retreat from. A value 白い熊 has already stored still wins over either default.
 *
 * <h3>A token sent to an app that does not want one is IGNORED, never refused</h3>
 *
 * Tokens live in task arguments and workspace variables that outlive the setting they were pasted
 * for. A caller still sending one — because it was configured last year, or because another app on
 * the batch does want one — must be served. Refusing it would turn "白い熊 turned a switch off"
 * into "half the batch mysteriously fails", which is exactly the friction the switch exists to
 * remove.
 *
 * <h3>One function, not two checks per entry point</h3>
 *
 * {@link #refuse(String)} is the whole gate. Written out separately at each entry point, "disabled"
 * and "bad token" drift apart — and they debug differently, so the caller is told which it was.
 *
 * All three keys live in the default SharedPreferences but are listed in Eximport.EXPORT_EXCLUDE,
 * so none of them ever travels inside a backup ZIP.
 */
public class AutomationAuth {

    public static final String PREF_ENABLED        = "automation_enabled";
    public static final String PREF_REQUIRE_TOKEN  = "automation_require_token";
    public static final String PREF_TOKEN          = "automation_token";
    /** The settings row itself — not a stored value; the fragment shows/hides it by key. */
    public static final String PREF_TOKEN_ROW      = "automation_token_row";

    private static final int TOKEN_BYTES = 24;

    /**
     * Default ON (v2). Keep this in lockstep with general_preferences.xml's own defaultValue — the
     * two disagreeing is precisely the bug that made the delete-old purge charger-only while its
     * checkbox showed unticked (commit 42).
     */
    public static boolean isEnabled() {
        return PrefUtils.getBoolean( PREF_ENABLED, true );
    }

    /** Default OFF (v2): the token is an extra a caller MAY be asked for, not the gate. */
    public static boolean isTokenRequired() {
        return PrefUtils.getBoolean( PREF_REQUIRE_TOKEN, false );
    }

    /**
     * The gate, in one place.
     *
     * @return null to proceed, otherwise the exact {@code ERROR:} line to answer with.
     */
    public static String refuse(String candidate) {
        if ( !isEnabled() )
            return "ERROR:automation disabled";
        // Note the order: a token is only ever LOOKED at when this app asks for one. Sent to an app
        // that does not, it is ignored — never an error.
        if ( isTokenRequired() && !check( candidate ) )
            return "ERROR:bad token";
        return null;
    }

    /** Generated lazily on first read, so the settings row always shows a value. */
    public static synchronized String getToken() {
        String token = PrefUtils.getString( PREF_TOKEN, "" );
        if ( token.isEmpty() ) {
            token = generate();
            PrefUtils.putString( PREF_TOKEN, token );
        }
        return token;
    }

    public static synchronized String regenerate() {
        final String token = generate();
        PrefUtils.putString( PREF_TOKEN, token );
        return token;
    }

    /**
     * Constant-time comparison — never use String.equals on a secret. Kept for the case where the
     * token IS required; everywhere else {@link #refuse(String)} never reaches it.
     */
    public static boolean check(String candidate) {
        if ( candidate == null || candidate.isEmpty() )
            return false;
        // Hex ASCII on both sides, so the platform default charset is safe here.
        return MessageDigest.isEqual( candidate.getBytes(), getToken().getBytes() );
    }

    private static String generate() {
        final byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes( bytes );
        final StringBuilder sb = new StringBuilder( TOKEN_BYTES * 2 );
        for ( byte b : bytes )
            sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
        return sb.toString();
    }
}

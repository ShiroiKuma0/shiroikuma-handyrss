package ru.yanus171.feedexfork.utils;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * HandyRss: the automation gate for the 保存復元 state-export contract (see StateExportReceiver).
 *
 * A master switch (default OFF) plus a per-install token that 白い熊 pastes into 自由作業盤. Both
 * live in the default SharedPreferences but are listed in Eximport.EXPORT_EXCLUDE, so neither ever
 * travels inside a backup ZIP.
 */
public class AutomationAuth {

    public static final String PREF_ENABLED = "automation_enabled";
    public static final String PREF_TOKEN   = "automation_token";

    private static final int TOKEN_BYTES = 24;

    public static boolean isEnabled() {
        return PrefUtils.getBoolean( PREF_ENABLED, false );
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

    /** Constant-time comparison — never use String.equals on a secret. */
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

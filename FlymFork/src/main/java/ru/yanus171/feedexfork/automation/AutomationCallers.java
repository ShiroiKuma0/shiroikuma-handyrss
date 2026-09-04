package ru.yanus171.feedexfork.automation;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Binder;
import android.os.Build;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * HandyRss: who is allowed through the automation data door, and how that is decided.
 *
 * <h3>Why not a token</h3>
 *
 * The token this replaces was a 48-character secret 白い熊 pasted from one app's settings into
 * another's. It cannot survive a wipe, which is fatal for the case the whole family now exists to
 * serve: 応用管理 restoring apps and their data onto a clean phone, where nothing is configured yet.
 *
 * <h3>Why not a {@code shiroikuma.*} prefix</h3>
 *
 * Because that is not an identity. What makes {@code getCallingPackage()} worth anything is that a
 * package name <b>cannot be taken while the real package is installed</b> — package names are not a
 * namespace anyone owns, so any sideloaded app may call itself {@code shiroikuma.evil} and pass a
 * prefix test. Since the caller supplies the file descriptor an export is written into, a prefix
 * check would hand such an app the complete data of every sister app in turn: strictly weaker than
 * the token it replaces.
 *
 * <h3>What is actually checked, in order</h3>
 *
 * <ol>
 *   <li><b>An exact name</b> from {@link #CALLERS}. Two callers exist and both are known here; a
 *       third is a one-line change.</li>
 *   <li><b>The uid agrees.</b> {@code getCallingPackage()} reflects the caller's <i>declared</i>
 *       attribution, and packages sharing a uid are not distinguished by it, so it is confirmed
 *       against the uid the kernel reports — an answer that cannot be borrowed.</li>
 *   <li><b>The signing certificate matches a pinned hash.</b> This is the one that closes the real
 *       gap: <i>whichever caller package is absent from the device is a name anyone can take</i>,
 *       and the clean-phone case this contract exists for is precisely a device where not
 *       everything is installed yet — the moment the assumption is weakest is the moment it is most
 *       needed.</li>
 * </ol>
 *
 * Ported verbatim in behaviour from 自由作業盤's {@code core/automation/AutomationCallers.kt}; the
 * class is deliberately app-independent, so the pins below are the family's, not this fork's.
 */
public class AutomationCallers {

    /**
     * The apps allowed to drive this one's data door.
     *
     * 応用管理 backs up and restores; 自由作業盤 runs the 保存復元 batch. Nothing else has any
     * business exporting another app's data, and an entry added here is a deliberate act.
     *
     * <p>Where the hashes come from, so the next person can re-derive them rather than trust them:
     * <pre>apksigner verify --print-certs &lt;the app's signed release APK&gt; | grep 'SHA-256 digest'</pre>
     * Every app in the family has <b>its own keystore</b> — there is no shared signing key to
     * compare against, which is also why a {@code protectionLevel="signature"} permission was never
     * an option here. <b>If a caller's key is ever rotated its calls stop working and the fix is
     * these constants</b> — the intended failure, because a signing key changing without anyone
     * noticing is exactly what a pin exists to catch.
     */
    private static final Map<String, String> CALLERS = new HashMap<>();
    static {
        CALLERS.put( "shiroikuma.oyokanri",     "9c585f4d118cb97ff653f949a8872875548403b9083ce6b9baa2e8f0c55ac6cc" );
        CALLERS.put( "shiroikuma.jiyusagyoban", "efd0d352192651593a92288ecdc64fc87262ec8648c24ed8f51a5587d46ac602" );
    }

    /**
     * Why this answers a STRING and not a boolean: a refusal that says only "no" is a refusal
     * nobody can debug from the other side of an IPC boundary. Each of these is a different mistake
     * with a different fix, and the caller shows them to 白い熊 verbatim.
     *
     * @return null when the caller is allowed, otherwise the exact {@code ERROR:} line.
     */
    public static String verify(Context context, String declared) {
        if ( declared == null || declared.isEmpty() )
            return "ERROR:caller unknown";
        final String pin = CALLERS.get( declared );
        if ( pin == null )
            return "ERROR:caller not permitted: " + declared;

        // The kernel's answer, not the caller's. A package may declare an attribution it does not
        // own; the uid cannot be borrowed.
        String[] real = null;
        try {
            real = context.getPackageManager().getPackagesForUid( Binder.getCallingUid() );
        } catch ( Throwable ignored ) {}
        if ( real == null || !Arrays.asList( real ).contains( declared ) )
            return "ERROR:caller uid mismatch: " + declared;

        final String signature = signingSha256( context, declared );
        if ( signature == null )
            return "ERROR:caller signature unreadable: " + declared;
        // Constant-time, like the token compare it replaces — the value is a public hash, but the
        // habit is worth keeping and costs nothing.
        if ( !MessageDigest.isEqual( signature.getBytes(), pin.getBytes() ) )
            return "ERROR:caller signature mismatch: " + declared;
        return null;
    }

    /**
     * The SHA-256 of the caller's current signing certificate, lower-case hex.
     *
     * {@code signingInfo} rather than the deprecated {@code signatures} where it exists: a rotated
     * key reports its whole history and we want the certificate actually in force. Below API 28 the
     * deprecated array is the correct answer rather than a compromise — before key rotation
     * existed, {@code signatures} WAS the signing certificate — and this app's minSdk is 14, so
     * without that branch the door would refuse every caller on an older device.
     *
     * <p>Exactly one signer, or we decline to guess: "several signers, one of which matches" is a
     * question about key rotation that nothing in this family needs to answer.
     */
    @SuppressWarnings( "deprecation" )
    private static String signingSha256(Context context, String pkg) {
        try {
            final PackageManager pm = context.getPackageManager();
            final Signature[] certs;
            if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ) {
                final android.content.pm.SigningInfo info =
                        pm.getPackageInfo( pkg, PackageManager.GET_SIGNING_CERTIFICATES ).signingInfo;
                certs = info == null ? null : info.getApkContentsSigners();
            } else {
                certs = pm.getPackageInfo( pkg, PackageManager.GET_SIGNATURES ).signatures;
            }
            if ( certs == null || certs.length != 1 )
                return null;
            final byte[] digest = MessageDigest.getInstance( "SHA-256" ).digest( certs[0].toByteArray() );
            final StringBuilder sb = new StringBuilder( digest.length * 2 );
            for ( byte b : digest )
                sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
            return sb.toString();
        } catch ( Throwable t ) {
            return null;
        }
    }
}

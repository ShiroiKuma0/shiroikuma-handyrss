package ru.yanus171.feedexfork.utils;

import static ru.yanus171.feedexfork.utils.Eximport.CAT_COLORS;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_FEEDS;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_FONTS;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_LIST;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_OTHER;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_READING;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ru.yanus171.feedexfork.MainApplication;
import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.parser.OPML;

/**
 * HandyRss: the one-ZIP state archive behind both the Export/Import panel and the headless
 * 保存復元 automation receiver — the single export core, with the UI panel and StateExportReceiver
 * as two thin callers.
 *
 * ONE zip per export, named `shiroikuma-handyrss_<yyyy-MM-dd_HH-mm-ss>.zip`:
 *
 *   manifest.json   format / version / app / appVersion / createdTs / categories
 *   feeds.opml      the feed + group outlines, still Thunderbird-native (unzip and import)
 *   fonts.json      one type-tagged pref dump per settings category, named by its stable id
 *   colors.json
 *   list.json
 *   reading.json
 *   other.json
 *
 * The category ids here are exactly the ids LIST_CATEGORIES advertises and EXPORT_STATE accepts in
 * `items`, and they are the zip entry names — the three stay in lockstep by construction.
 */
public class StateZip {

    public static final String FORMAT = "handyrss-state";
    public static final int VERSION = 1;

    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String FEEDS_ENTRY = "feeds.opml";

    /** Export/import order — feeds first, then the settings categories. */
    public static final int[] ORDER = { CAT_FEEDS, CAT_FONTS, CAT_COLORS, CAT_LIST, CAT_READING, CAT_OTHER };

    /** Reports real counts (never a percentage) while the archive is written. */
    public interface Progress {
        void on(long current, long total, String unit, String text);
    }

    // ---- category id / label mapping -----------------------------------------------------------

    public static String idOf(int cat) {
        switch ( cat ) {
            case CAT_FEEDS:   return "feeds";
            case CAT_FONTS:   return "fonts";
            case CAT_COLORS:  return "colors";
            case CAT_LIST:    return "list";
            case CAT_READING: return "reading";
            case CAT_OTHER:   return "other";
            default:          return "";
        }
    }

    /** -1 when the id is not one of ours (EXPORT_STATE reports it back as an unknown category). */
    public static int catOf(String id) {
        for ( int cat : ORDER )
            if ( idOf( cat ).equals( id ) )
                return cat;
        return -1;
    }

    public static String labelOf(Context context, int cat) {
        switch ( cat ) {
            case CAT_FEEDS:   return context.getString( R.string.eim_cat_feeds );
            case CAT_FONTS:   return context.getString( R.string.eim_cat_fonts );
            case CAT_COLORS:  return context.getString( R.string.eim_cat_colors );
            case CAT_LIST:    return context.getString( R.string.eim_cat_list );
            case CAT_READING: return context.getString( R.string.eim_cat_reading );
            case CAT_OTHER:   return context.getString( R.string.eim_cat_other );
            default:          return "";
        }
    }

    public static String entryNameOf(int cat) {
        return cat == CAT_FEEDS ? FEEDS_ENTRY : idOf( cat ) + ".json";
    }

    /** The `id<TAB>label` lines LIST_CATEGORIES replies with. Flat — no sub-options in this app. */
    public static String categoryLines(Context context) {
        final StringBuilder sb = new StringBuilder();
        for ( int cat : ORDER ) {
            if ( sb.length() > 0 )
                sb.append( "\n" );
            sb.append( idOf( cat ) ).append( "\t" ).append( labelOf( context, cat ) );
        }
        return sb.toString();
    }

    private static List<Integer> ordered(Set<Integer> cats) {
        final List<Integer> out = new ArrayList<>();
        for ( int cat : ORDER )
            if ( cats.contains( cat ) )
                out.add( cat );
        return out;
    }

    // ---- export --------------------------------------------------------------------------------

    /**
     * The headless export core. Writes exactly one zip into `os` (which the caller owns and closes)
     * and returns the number of categories written.
     */
    public static int write(Set<Integer> cats, OutputStream os, Progress progress) throws IOException {
        final Context context = MainApplication.getContext();
        final List<Integer> selected = ordered( cats );
        final ZipOutputStream zos = new ZipOutputStream( new BufferedOutputStream( os ) );
        final String unit = context.getString( R.string.automation_unit_categories );

        zos.putNextEntry( new ZipEntry( MANIFEST_ENTRY ) );
        zos.write( manifest( context, selected ).getBytes( "UTF-8" ) );
        zos.closeEntry();

        int done = 0;
        for ( int cat : selected ) {
            done++;
            if ( progress != null )
                progress.on( done, selected.size(), unit,
                        unit + " " + done + "/" + selected.size() + " — " + labelOf( context, cat ) );
            zos.putNextEntry( new ZipEntry( entryNameOf( cat ) ) );
            if ( cat == CAT_FEEDS ) {
                // Thunderbird-native outlines, no prefs — the feeds half of the old plain export.
                final Writer w = new OutputStreamWriter( zos, "UTF-8" );
                OPML.exportSelected( w, true, Collections.<Integer>emptySet() );
                w.flush(); // NOT close() — that would close the zip stream
            } else
                zos.write( prefsJson( context, cat ).getBytes( "UTF-8" ) );
            zos.closeEntry();
        }

        zos.finish();
        zos.flush();
        return selected.size();
    }

    private static String manifest(Context context, List<Integer> selected) throws IOException {
        try {
            final JSONObject root = new JSONObject();
            root.put( "format", FORMAT );
            root.put( "version", VERSION );
            root.put( "app", context.getPackageName() );
            root.put( "appVersion", appVersion( context ) );
            root.put( "createdTs", System.currentTimeMillis() );
            final org.json.JSONArray ids = new org.json.JSONArray();
            for ( int cat : selected )
                ids.put( idOf( cat ) );
            root.put( "categories", ids );
            return root.toString( 2 );
        } catch ( Exception e ) {
            throw new IOException( "cannot build manifest: " + e.getMessage() );
        }
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo( context.getPackageName(), 0 ).versionName;
        } catch ( Exception e ) {
            return "";
        }
    }

    /** Type-tagged pref dump: {"key": {"t":"s|b|i|l|f", "v": …}} for one category. */
    private static String prefsJson(Context context, int cat) throws IOException {
        try {
            final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences( context );
            final JSONObject root = new JSONObject();
            for ( Map.Entry<String, ?> entry : settings.getAll().entrySet() ) {
                final String key = entry.getKey();
                final Object value = entry.getValue();
                if ( value == null || Eximport.isExcludedFromExport( key ) )
                    continue;
                if ( Eximport.categoryOf( key ) != cat )
                    continue;
                final String type;
                if ( value instanceof String )       type = "s";
                else if ( value instanceof Boolean ) type = "b";
                else if ( value instanceof Integer ) type = "i";
                else if ( value instanceof Long )    type = "l";
                else if ( value instanceof Float )   type = "f";
                else continue; // string sets etc. are not part of the settings surface
                final JSONObject cell = new JSONObject();
                cell.put( "t", type );
                cell.put( "v", value );
                root.put( key, cell );
            }
            return root.toString( 2 );
        } catch ( Exception e ) {
            throw new IOException( "cannot serialise " + idOf( cat ) + ": " + e.getMessage() );
        }
    }

    // ---- import --------------------------------------------------------------------------------

    /** True when the stream starts with the `PK\003\004` local-file header. */
    public static boolean isZip(byte[] head) {
        return head != null && head.length >= 4
                && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
    }

    /**
     * Merge-import the selected categories from a state ZIP. Absent categories are skipped, and
     * anything the caller did not tick is ignored even when present in the archive.
     */
    public static void read(InputStream is, Set<Integer> cats) throws Exception {
        final Context context = MainApplication.getContext();
        final ZipInputStream zis = new ZipInputStream( new BufferedInputStream( is ) );
        final Set<String> seen = new LinkedHashSet<>();
        try {
            ZipEntry entry;
            while ( ( entry = zis.getNextEntry() ) != null ) {
                final String name = entry.getName();
                seen.add( name );
                if ( MANIFEST_ENTRY.equals( name ) ) {
                    checkManifest( readAll( zis ) );
                } else if ( FEEDS_ENTRY.equals( name ) ) {
                    if ( cats.contains( CAT_FEEDS ) )
                        importFeeds( context, zis );
                } else if ( name.endsWith( ".json" ) ) {
                    final int cat = catOf( name.substring( 0, name.length() - ".json".length() ) );
                    if ( cat >= 0 && cats.contains( cat ) )
                        applyPrefs( context, readAll( zis ) );
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        if ( !seen.contains( MANIFEST_ENTRY ) )
            throw new IOException( "not a " + FORMAT + " archive (no " + MANIFEST_ENTRY + ")" );
    }

    private static void checkManifest(byte[] bytes) throws IOException {
        try {
            final JSONObject root = new JSONObject( new String( bytes, "UTF-8" ) );
            final String format = root.optString( "format", "" );
            if ( !FORMAT.equals( format ) )
                throw new IOException( "unexpected archive format '" + format + "'" );
        } catch ( IOException e ) {
            throw e;
        } catch ( Exception e ) {
            throw new IOException( "unreadable " + MANIFEST_ENTRY + ": " + e.getMessage() );
        }
    }

    /**
     * The OPML importer owns (and closes) the stream it is given, so the feeds entry is spooled to
     * a cache file first rather than handing it the live ZipInputStream.
     */
    private static void importFeeds(Context context, InputStream zis) throws Exception {
        final File tmp = new File( context.getCacheDir(), "state-feeds.opml" );
        final FileOutputStream fos = new FileOutputStream( tmp );
        try {
            copy( zis, fos );
        } finally {
            fos.close();
        }
        final Set<Integer> noPrefs = Collections.emptySet();
        final Set<Integer> prevCats = OPML.sImportPrefCats;
        final boolean prevFeeds = OPML.sImportFeeds;
        try {
            OPML.sImportPrefCats = noPrefs; // feeds.opml carries no prefs; be explicit anyway
            OPML.sImportFeeds = true;
            OPML.importFromFile( tmp.getAbsolutePath(), false );
        } finally {
            OPML.sImportPrefCats = prevCats;
            OPML.sImportFeeds = prevFeeds;
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Merge per key — absent keys keep their current value. */
    private static void applyPrefs(Context context, byte[] bytes) throws Exception {
        final JSONObject root = new JSONObject( new String( bytes, "UTF-8" ) );
        final SharedPreferences.Editor editor =
                PreferenceManager.getDefaultSharedPreferences( context ).edit();
        for ( Iterator<String> it = root.keys(); it.hasNext(); ) {
            final String key = it.next();
            if ( Eximport.isExcludedFromExport( key ) )
                continue; // never let a foreign archive plant a directory URI or a token
            final JSONObject cell = root.optJSONObject( key );
            if ( cell == null )
                continue;
            final String type = cell.optString( "t", "" );
            if ( "s".equals( type ) )      editor.putString(  key, cell.optString( "v", "" ) );
            else if ( "b".equals( type ) ) editor.putBoolean( key, cell.optBoolean( "v", false ) );
            else if ( "i".equals( type ) ) editor.putInt(     key, cell.optInt( "v", 0 ) );
            else if ( "l".equals( type ) ) editor.putLong(    key, cell.optLong( "v", 0L ) );
            else if ( "f".equals( type ) ) editor.putFloat(   key, (float) cell.optDouble( "v", 0d ) );
        }
        editor.apply();
    }

    // ---- small io helpers ----------------------------------------------------------------------

    private static byte[] readAll(InputStream is) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy( is, out );
        return out.toByteArray();
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        final byte[] buf = new byte[8192];
        int n;
        while ( ( n = is.read( buf ) ) > 0 )
            os.write( buf, 0, n );
    }
}

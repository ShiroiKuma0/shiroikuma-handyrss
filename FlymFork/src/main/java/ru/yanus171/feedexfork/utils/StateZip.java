package ru.yanus171.feedexfork.utils;

import static ru.yanus171.feedexfork.utils.Eximport.CAT_ARTICLES;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_ARTICLES_TEXT;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_COLORS;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_FEEDS;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_FONTS;
import static ru.yanus171.feedexfork.utils.Eximport.CAT_IMAGES;
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
import java.io.FileInputStream;
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
 *   manifest.json     format / version / app / appVersion / createdTs / categories
 *   feeds.opml        the feed + group outlines, still Thunderbird-native (unzip and import)
 *   articles.backup   every entry with its read/starred/scroll state, each feed's own settings,
 *                     the per-feed filters, the labels and the external-link pseudo-feed —
 *                     stock .backup shape MINUS its <pref> block (see OPML.exportArticles)
 *   html/<md5>        the downloaded article text (`articles.text`)
 *   images/<name>     the downloaded article images
 *   fonts/<name>      the font FILES the user installed; without them fonts.json restores names
 *                     pointing at nothing and the app silently falls back to the default
 *   fonts.json        one type-tagged pref dump per settings category, named by its stable id
 *   colors.json
 *   list.json
 *   reading.json
 *   other.json
 *
 * The category ids here are exactly the ids LIST_CATEGORIES advertises and EXPORT_STATE accepts in
 * `items`, and they name the zip entries — the three stay in lockstep by construction.
 */
public class StateZip {

    public static final String FORMAT = "handyrss-state";
    public static final int VERSION = 1;

    public static final String MANIFEST_ENTRY = "manifest.json";
    public static final String FEEDS_ENTRY = "feeds.opml";
    public static final String ARTICLES_ENTRY = "articles.backup";

    // Folder-backed categories. `fonts/` cannot collide with `fonts.json` — one is a prefix.
    private static final String HTML_PREFIX = "html/";
    private static final String IMAGES_PREFIX = "images/";
    private static final String FONTS_PREFIX = "fonts/";

    /** Mirrors NetworkUtils.TEMP_PREFIX (private there): a half-downloaded image, never exported. */
    private static final String TEMP_IMAGE_PREFIX = "TEMP__";

    /** Export/import order — content first, then the settings categories. */
    public static final int[] ORDER = { CAT_FEEDS, CAT_ARTICLES, CAT_ARTICLES_TEXT, CAT_IMAGES,
                                        CAT_FONTS, CAT_COLORS, CAT_LIST, CAT_READING, CAT_OTHER };

    /** Reports real counts (never a percentage) while the archive is written. */
    public interface Progress {
        /**
         * @param item    the category id being written (see idOf) — the 保存復元 panel highlights
         *                that row rather than counting to it, so a sub-option like `articles.text`
         *                sends itself, never its parent. Empty means nothing is being written.
         * @param current 1-based position of the category, paired with the label in `text`
         */
        void on(String item, long current, long total, String unit, String text);

        /**
         * Source bytes stored so far / to be stored, for the file categories. Sticky: the pair
         * stands until replaced, so a category with nothing measurable (the OPML documents, the
         * prefs JSON) leaves the last real numbers alone rather than inventing a zero.
         */
        void bytes(long done, long total);
    }

    /** Running byte tally shared across the folder categories of one export. */
    private static class Bytes {
        long mDone = 0, mTotal = 0;
    }

    /**
     * A cancel signal the write loops POLL at every entry boundary — never a thread interrupt, so
     * nothing is ever torn apart mid-write(). Set by CANCEL_EXPORT; the export unwinds at the next
     * boundary and its caller deletes the partial file.
     */
    public static class Canceller {
        private volatile boolean mCancelled = false;
        public void cancel() { mCancelled = true; }
        public boolean isCancelled() { return mCancelled; }
    }

    /** Thrown out of write() once a Canceller fires — the caller deletes its `.part` and replies. */
    public static class CancelledException extends IOException {
        public CancelledException() { super( "cancelled" ); }
    }

    private static void throwIfCancelled(Canceller cancel) throws CancelledException {
        if ( cancel != null && cancel.isCancelled() )
            throw new CancelledException();
    }

    // ---- category id / label mapping -----------------------------------------------------------

    public static String idOf(int cat) {
        switch ( cat ) {
            case CAT_FEEDS:         return "feeds";
            case CAT_ARTICLES:      return "articles";
            case CAT_ARTICLES_TEXT: return "articles.text";
            case CAT_IMAGES:        return "images";
            case CAT_FONTS:         return "fonts";
            case CAT_COLORS:        return "colors";
            case CAT_LIST:          return "list";
            case CAT_READING:       return "reading";
            case CAT_OTHER:         return "other";
            default:                return "";
        }
    }

    /** The parent item id in the 保存復元 picker, or "" for a top-level item. */
    public static String parentOf(int cat) {
        return cat == CAT_ARTICLES_TEXT ? idOf( CAT_ARTICLES ) : "";
    }

    /**
     * Whether the item starts ticked, in this app's own panel and in 保存復元's picker alike.
     * Everything here is `on`: nothing this app exports is both derived and re-creatable, so there
     * is nothing that earns an `off` default (白い熊, 2026-07-28 — including the two large but
     * genuinely irreplaceable ones, `articles.text` and `images`).
     */
    public static boolean defaultOf(int cat) {
        return true;
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
            case CAT_FEEDS:         return context.getString( R.string.eim_cat_feeds );
            case CAT_ARTICLES:      return context.getString( R.string.eim_cat_articles );
            case CAT_ARTICLES_TEXT: return context.getString( R.string.eim_cat_articles_text );
            case CAT_IMAGES:        return context.getString( R.string.eim_cat_images );
            case CAT_FONTS:         return context.getString( R.string.eim_cat_fonts );
            case CAT_COLORS:        return context.getString( R.string.eim_cat_colors );
            case CAT_LIST:          return context.getString( R.string.eim_cat_list );
            case CAT_READING:       return context.getString( R.string.eim_cat_reading );
            case CAT_OTHER:         return context.getString( R.string.eim_cat_other );
            default:                return "";
        }
    }

    /**
     * The LIST_CATEGORIES reply: `id<TAB>label<TAB>parent<TAB>on|off` per line. The last two fields
     * are positional and optional in the contract, but both are always written here — a top-level
     * item still needs the empty third field so the fourth lands in the right column.
     */
    public static String categoryLines(Context context) {
        final StringBuilder sb = new StringBuilder();
        for ( int cat : ORDER ) {
            if ( sb.length() > 0 )
                sb.append( "\n" );
            sb.append( idOf( cat ) ).append( "\t" ).append( labelOf( context, cat ) )
              .append( "\t" ).append( parentOf( cat ) )
              .append( "\t" ).append( defaultOf( cat ) ? "on" : "off" );
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
        return write( cats, os, progress, null );
    }

    /**
     * As above, but pollable: `cancel` is checked at every category and every file boundary, and
     * handed to OPML so the long `articles.backup` write unwinds at its next feed/entry boundary
     * too. Throws CancelledException — the caller is responsible for the partial file.
     */
    public static int write(Set<Integer> cats, OutputStream os, Progress progress, Canceller cancel) throws IOException {
        final Context context = MainApplication.getContext();
        final List<Integer> selected = ordered( cats );
        final ZipOutputStream zos = new ZipOutputStream( new BufferedOutputStream( os ) );
        final String unit = context.getString( R.string.automation_unit_categories );

        OPML.sExportCancel = cancel;
        try {
            return writeEntries( context, zos, selected, unit, progress, cancel );
        } finally {
            OPML.sExportCancel = null;
        }
    }

    private static int writeEntries(Context context, ZipOutputStream zos, List<Integer> selected,
                                    String unit, Progress progress, Canceller cancel) throws IOException {
        zos.putNextEntry( new ZipEntry( MANIFEST_ENTRY ) );
        zos.write( manifest( context, selected ).getBytes( "UTF-8" ) );
        zos.closeEntry();

        // One listFiles() pass over the three flat folders, up front, so the byte total is known
        // before the first file is stored rather than growing as we go.
        final Bytes bytes = new Bytes();
        for ( int cat : selected )
            for ( File f : storedFiles( folderOf( cat ) ) )
                bytes.mTotal += f.length();
        if ( progress != null && bytes.mTotal > 0 )
            progress.bytes( 0, bytes.mTotal );

        int done = 0;
        for ( int cat : selected ) {
            throwIfCancelled( cancel );
            done++;
            final String item = idOf( cat );
            final String head = unit + " " + done + "/" + selected.size() + " — " + labelOf( context, cat );
            if ( progress != null )
                progress.on( item, done, selected.size(), unit, head );
            switch ( cat ) {
                case CAT_FEEDS:
                    // Thunderbird-native outlines, no prefs — the feeds half of the old plain export.
                    writeOpml( zos, FEEDS_ENTRY, false, cancel );
                    break;
                case CAT_ARTICLES:
                    writeArticles( zos, progress, item, done, selected.size(), unit, head, cancel );
                    break;
                case CAT_ARTICLES_TEXT:
                case CAT_IMAGES:
                    writeFolder( zos, cat, progress, item, done, selected.size(), unit, head, cancel, bytes );
                    break;
                case CAT_FONTS:
                    // The only category that is both files and settings: the installed font files
                    // plus the prefs naming them.
                    writeFolder( zos, cat, progress, item, done, selected.size(), unit, head, cancel, bytes );
                    writePrefs( zos, context, cat );
                    break;
                default:
                    writePrefs( zos, context, cat );
            }
        }

        zos.finish();
        zos.flush();
        return selected.size();
    }

    /** The data folder a category stores, or null for the ones that are not file-backed. */
    private static File folderOf(int cat) {
        switch ( cat ) {
            case CAT_ARTICLES_TEXT: return FileUtils.INSTANCE.GetHTMLFolder();
            case CAT_IMAGES:        return FileUtils.INSTANCE.GetImagesFolder();
            case CAT_FONTS:         return FileUtils.INSTANCE.getFontsFolder();
            default:                return null;
        }
    }

    private static String prefixOf(int cat) {
        switch ( cat ) {
            case CAT_ARTICLES_TEXT: return HTML_PREFIX;
            case CAT_IMAGES:        return IMAGES_PREFIX;
            case CAT_FONTS:         return FONTS_PREFIX;
            default:                return "";
        }
    }

    /**
     * The files a folder category will ACTUALLY store — never the raw directory listing, which
     * carries `.nomedia` and half-downloaded `TEMP__` images. Counting stored files against the raw
     * length gave a denominator the counter could never reach.
     */
    private static File[] storedFiles(File dir) {
        final File[] all = dir == null ? null : dir.listFiles();
        if ( all == null )
            return new File[0];
        final List<File> out = new ArrayList<>();
        for ( File f : all ) {
            final String name = f.getName();
            if ( f.isFile() && !name.startsWith( "." ) && !name.startsWith( TEMP_IMAGE_PREFIX ) )
                out.add( f );
        }
        return out.toArray( new File[0] );
    }

    private static void writeOpml(ZipOutputStream zos, String entryName, boolean articles, Canceller cancel) throws IOException {
        zos.putNextEntry( new ZipEntry( entryName ) );
        final Writer w = new OutputStreamWriter( zos, "UTF-8" );
        if ( articles )
            OPML.exportArticles( w );
        else
            OPML.exportSelected( w, true, Collections.<Integer>emptySet() );
        w.flush(); // NOT close() — that would close the zip stream
        zos.closeEntry();
        // OPML breaks its own loops on the shared flag rather than throwing through the SAX-era
        // code, so the document just ends early — turn that back into a real unwind here.
        throwIfCancelled( cancel );
    }

    private static void writePrefs(ZipOutputStream zos, Context context, int cat) throws IOException {
        zos.putNextEntry( new ZipEntry( idOf( cat ) + ".json" ) );
        zos.write( prefsJson( context, cat ).getBytes( "UTF-8" ) );
        zos.closeEntry();
    }

    /**
     * Every storable file directly in the category's folder. All three folders are flat by
     * construction (md5-hashed names), so there is nothing to recurse into.
     *
     * Progress keeps `current`/`total` on the category spine — the panel highlights by `item`, not
     * by counting — and moves the file count and the byte tally, which are the parts that change.
     */
    private static void writeFolder(ZipOutputStream zos, int cat, Progress progress, String item,
                                    long catDone, long catTotal, String unit, String head,
                                    Canceller cancel, Bytes bytes) throws IOException {
        final File[] files = storedFiles( folderOf( cat ) );
        final String prefix = prefixOf( cat );
        int n = 0;
        for ( File f : files ) {
            throwIfCancelled( cancel );
            n++;
            if ( progress != null )
                progress.on( item, catDone, catTotal, unit, head + " " + n + "/" + files.length );
            // A file that vanished or turned unreadable since the listing is skipped, never waited
            // on — the broadcast is a heartbeat, and one bad file must not stall the whole batch.
            try {
                zos.putNextEntry( new ZipEntry( prefix + f.getName() ) );
                final FileInputStream fis = new FileInputStream( f );
                try {
                    copy( fis, zos );
                } finally {
                    fis.close();
                }
                zos.closeEntry();
            } catch ( IOException e ) {
                e.printStackTrace();
            }
            bytes.mDone += f.length();
            if ( progress != null && bytes.mTotal > 0 )
                progress.bytes( bytes.mDone, bytes.mTotal );
        }
    }

    /**
     * The longest single stretch of the whole archive — minutes on a real corpus. OPML gets a
     * progress sink for the length of the write, the same way it already gets the cancel flag, so
     * the run keeps a heartbeat instead of going silent and being presumed dead.
     */
    private static void writeArticles(ZipOutputStream zos, Progress progress, String item,
                                      long catDone, long catTotal, String unit, String head,
                                      Canceller cancel) throws IOException {
        final String entriesUnit = MainApplication.getContext().getString( R.string.automation_unit_entries );
        OPML.sExportProgress = progress == null ? null : ( entriesDone, entriesTotal ) ->
                progress.on( item, catDone, catTotal, unit,
                        head + " — " + entriesUnit + " " + entriesDone + "/" + entriesTotal );
        try {
            writeOpml( zos, ARTICLES_ENTRY, true, cancel );
        } finally {
            OPML.sExportProgress = null;
        }
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
                        importOpml( context, zis, "state-feeds.opml" );
                } else if ( ARTICLES_ENTRY.equals( name ) ) {
                    if ( cats.contains( CAT_ARTICLES ) )
                        importOpml( context, zis, "state-articles.opml" );
                } else if ( name.startsWith( HTML_PREFIX ) ) {
                    if ( cats.contains( CAT_ARTICLES_TEXT ) )
                        extractTo( FileUtils.INSTANCE.GetHTMLFolder(), name.substring( HTML_PREFIX.length() ), zis );
                } else if ( name.startsWith( IMAGES_PREFIX ) ) {
                    if ( cats.contains( CAT_IMAGES ) )
                        extractTo( FileUtils.INSTANCE.GetImagesFolder(), name.substring( IMAGES_PREFIX.length() ), zis );
                } else if ( name.startsWith( FONTS_PREFIX ) ) {
                    if ( cats.contains( CAT_FONTS ) )
                        extractTo( FileUtils.INSTANCE.getFontsFolder(), name.substring( FONTS_PREFIX.length() ), zis );
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
     * The OPML importer owns (and closes) the stream it is given, so the entry is spooled to a
     * cache file first rather than handing it the live ZipInputStream. Serves both `feeds.opml`
     * and `articles.backup` — the parser reads the same document shape either way.
     */
    private static void importOpml(Context context, InputStream zis, String tmpName) throws Exception {
        final File tmp = new File( context.getCacheDir(), tmpName );
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
            OPML.sImportPrefCats = noPrefs; // neither entry carries prefs; be explicit anyway
            OPML.sImportFeeds = true;
            OPML.importFromFile( tmp.getAbsolutePath(), false );
        } finally {
            OPML.sImportPrefCats = prevCats;
            OPML.sImportFeeds = prevFeeds;
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /**
     * Restore one file into a data folder. The name is the zip entry's last component and must
     * stay that way: anything carrying a separator or `..` is dropped rather than allowed to
     * escape the target folder (a state ZIP can come from anywhere).
     */
    private static void extractTo(File dir, String name, InputStream zis) throws IOException {
        if ( name.isEmpty() || name.contains( "/" ) || name.contains( "\\" ) || name.contains( ".." ) )
            return;
        if ( !dir.exists() && !dir.mkdirs() )
            throw new IOException( "cannot create " + dir.getAbsolutePath() );
        final FileOutputStream fos = new FileOutputStream( new File( dir, name ) );
        try {
            copy( zis, fos );
        } finally {
            fos.close();
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

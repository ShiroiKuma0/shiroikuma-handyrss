package ru.yanus171.feedexfork.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ru.yanus171.feedexfork.MainApplication;
import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.activity.GeneralPrefsActivity;
import ru.yanus171.feedexfork.parser.OPML;

/**
 * HandyRss: the Export/Import panel opened from the top of the 白い熊 Handy RSS UI screen
 * (Kōjiki-style flow: one settable SAF export directory + "last export" line queried from it,
 * category checkboxes covering every settable item, ArcaneChat-style pill button row —
 * Cancel alone left, Import/Export grouped right). All chrome is black with yellow borders.
 */
public class Eximport {

    // Category ids — each exported pref key is classified into exactly one (feeds are outlines).
    public static final int CAT_FEEDS = 0, CAT_FONTS = 1, CAT_COLORS = 2,
                            CAT_LIST = 3, CAT_READING = 4, CAT_OTHER = 5;

    private static final int WARN_COLOR = 0xFFFF5252;

    // Family naming convention (白い熊, 2026-07-25): <english-dash-separated-app-name>_<ts>.zip —
    // no version, no "-export" infix, no "-ui"/"-settings" suffix, because every sister app's
    // backups live in one directory and must sort and read uniformly. The pre-2026-07-25 name is
    // still recognised when looking for the last export, so older backups stay visible.
    public static final String EXPORT_FILE_PREFIX = "shiroikuma-handyrss_";
    public static final String EXPORT_FILE_EXT = ".zip";
    private static final String EXPORT_FILE_PREFIX_LEGACY = "shiroikuma-handyrss-export_";

    // Device-local keys that must never travel to another install. The automation switch + token
    // belong here too: a backup must never carry the credential that unlocks the export receiver.
    private static final Set<String> EXPORT_EXCLUDE = new HashSet<>(Arrays.asList(
            OPML.EXPORT_DIR, OPML.EXPORT_DIR_SETTINGS, OPML.EXPORT_DIR_FEEDS, OPML.EXPORT_DIR_BACKUP,
            OPML.EXPORT_LAST_SETTINGS, OPML.EXPORT_LAST_FEEDS, "data_folder",
            AutomationAuth.PREF_ENABLED, AutomationAuth.PREF_TOKEN ));

    private static final Set<String> COLOR_KEYS = new HashSet<>(Arrays.asList(
            "theme", "customColors", "lighttheme", "textColor", "toolBarColor", "feedListColor",
            "textColorRead", "linkColor", "loadedLinkColor", "textColor_background",
            "text_color_brightness" ));
    private static final Set<String> LIST_KEYS = new HashSet<>(Arrays.asList(
            "show_mark_all_as_read_button", "show_read_checkbox", "show_unstarred_checkbox",
            "show_full_text_indicator", "show_new_icon", "display_oldest_first",
            "article_list_restore_type", "vibrate_on_article_list_entry_swype",
            "article_list_size_progressbar_maxsize" ));
    private static final Set<String> READING_KEYS = new HashSet<>(Arrays.asList(
            "display_images", "display_entries_fullscreen", "underline_links",
            "setting_article_text_buttons_layout", "remember_last_entry", "main_font_css_text",
            "custom_css_text", "article_tap_enabled", "volume_buttons_action",
            "volume_buttons_action_page_in_article_list", "page_up_down_90_pct",
            "disable_tap_actions_when_video", "brightness_gesture_enabled",
            "brightness_programmatic_dim_max", "settings_brightness_read_from_system_period_min",
            "label_setup_by_tap_on_date" ));

    public static int categoryOf(String key) {
        if (key.startsWith("font") || key.equals("fontFamily") || key.equals("fontsize"))
            return CAT_FONTS;
        if (COLOR_KEYS.contains(key) || key.startsWith("chrome_") || key.startsWith("quote_") || key.startsWith("subtitle_"))
            return CAT_COLORS;
        if (LIST_KEYS.contains(key) || key.startsWith("list_")
                || key.startsWith("setting_show_article") || key.startsWith("settings_show_article"))
            return CAT_LIST;
        if (READING_KEYS.contains(key) || key.startsWith("article_text_") || key.startsWith("entry_")
                || key.startsWith("tap_") || key.startsWith("settings_tap_"))
            return CAT_READING;
        return CAT_OTHER;
    }

    public static boolean isExcludedFromExport(String key) { return EXPORT_EXCLUDE.contains(key); }

    // ---- panel state -------------------------------------------------------------------------

    private static Eximport sInstance = null;

    private final Activity mActivity;
    private AlertDialog mPanel = null;
    private TextView mDirValue = null;
    private TextView mStatus = null;
    private final Map<Integer, CheckBox> mChecks = new LinkedHashMap<>();

    private Eximport(Activity activity) { mActivity = activity; }

    private int dp(int v) { return UiUtils.dpToPixel(v); }
    private float dpf(float v) { return v * mActivity.getResources().getDisplayMetrics().density; }
    private int fg() { return Theme.GetChromeFgInt(); }
    private int bg() { return Theme.GetChromeBgInt(); }

    // ---- entry points ------------------------------------------------------------------------

    public static void show(Activity activity) {
        sInstance = new Eximport(activity);
        sInstance.buildAndShow();
    }

    /** Called from GeneralPrefsActivity.onActivityResult after the SAF directory pick. */
    public static void onDirPicked() {
        if (sInstance != null)
            sInstance.refreshStatus();
    }

    /** Called from GeneralPrefsActivity.onActivityResult with the picked import file. */
    public static void onImportFileChosen(Activity activity, Uri uri) {
        if (sInstance != null)
            sInstance.doImport(activity, uri);
    }

    /** Summary for the UI-screen row: description + the last-export line (queried on page open). */
    public static String pageSummary(Context ctx) {
        String desc = ctx.getString(R.string.eim_desc);
        String dir = OPML.getEximportDir();
        if (dir.isEmpty())
            return desc + "\n" + ctx.getString(R.string.eim_warn_nodir);
        DocumentFile newest = newestExport(ctx, dir);
        return desc + "\n" + (newest == null ? ctx.getString(R.string.eim_warn_none)
                : ctx.getString(R.string.eim_last, fmtTs(newest.lastModified())));
    }

    // ---- the panel ---------------------------------------------------------------------------

    private void buildAndShow() {
        final Context ctx = mActivity;
        final int fg = fg(), bg = bg();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(20));
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setCornerRadius(dpf(16));
        boxBg.setColor(bg);
        boxBg.setStroke(dp(2), fg);
        box.setBackground(boxBg);

        TextView title = text(ctx, ctx.getString(R.string.settings_export_section), 18, fg, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(2), 0, dp(6));
        box.addView(title);
        TextView desc = text(ctx, ctx.getString(R.string.eim_desc), 13, fg, false);
        desc.setAlpha(0.85f);
        desc.setPadding(0, 0, 0, dp(10));
        box.addView(desc);

        // Settable export directory — a bordered, clearly-tappable box.
        LinearLayout dirBox = new LinearLayout(ctx);
        dirBox.setOrientation(LinearLayout.VERTICAL);
        dirBox.setClickable(true);
        dirBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable dirBg = new GradientDrawable();
        dirBg.setCornerRadius(dpf(10));
        dirBg.setColor(bg);
        dirBg.setStroke(dp(2), fg);
        dirBox.setBackground(dirBg);
        dirBox.setOnClickListener(v -> GeneralPrefsActivity.startDirPick(OPML.EXPORT_DIR));
        dirBox.addView(text(ctx, ctx.getString(R.string.eim_dir_label), 12, fg, false));
        mDirValue = text(ctx, "", 15, fg, true);
        dirBox.addView(mDirValue);
        LinearLayout.LayoutParams dirLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dirLp.topMargin = dp(6);
        dirLp.bottomMargin = dp(6);
        box.addView(dirBox, dirLp);

        mStatus = text(ctx, "", 14, fg, false);
        mStatus.setPadding(dp(2), 0, 0, dp(8));
        box.addView(mStatus);

        box.addView(divider(ctx, fg, 1, 0));

        CheckBox selectAll = checkbox(ctx, ctx.getString(R.string.eim_select_all), true);
        box.addView(selectAll);
        addCat(box, CAT_FEEDS, R.string.eim_cat_feeds);
        addCat(box, CAT_FONTS, R.string.eim_cat_fonts);
        addCat(box, CAT_COLORS, R.string.eim_cat_colors);
        addCat(box, CAT_LIST, R.string.eim_cat_list);
        addCat(box, CAT_READING, R.string.eim_cat_reading);
        addCat(box, CAT_OTHER, R.string.eim_cat_other);
        selectAll.setOnCheckedChangeListener((b, isChecked) -> {
            for (CheckBox cb : mChecks.values())
                cb.setChecked(isChecked);
        });

        box.addView(divider(ctx, fg, 1, dp(8)));

        // ArcaneChat-style pill row: Cancel alone left, weighted spacer, Import + Export right.
        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(14), 0, 0);
        Button bCancel = pill(ctx, ctx.getString(android.R.string.cancel));
        bCancel.setOnClickListener(v -> dismissPanel());
        buttons.addView(bCancel);
        buttons.addView(new View(ctx), new LinearLayout.LayoutParams(0, 0, 1f));
        Button bImport = pill(ctx, ctx.getString(R.string.settings_export_btn_import));
        LinearLayout.LayoutParams impLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        impLp.rightMargin = dp(8);
        bImport.setOnClickListener(v -> onImportClicked());
        buttons.addView(bImport, impLp);
        Button bExport = pill(ctx, ctx.getString(R.string.settings_export_btn_export));
        bExport.setOnClickListener(v -> onExportClicked());
        buttons.addView(bExport);
        box.addView(buttons);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setPadding(dp(10), dp(10), dp(10), dp(10));
        scroll.setClipToPadding(false);
        scroll.addView(box);

        mPanel = new AlertDialog.Builder(mActivity).setView(scroll).create();
        mPanel.setOnDismissListener(d -> { if (sInstance == Eximport.this) sInstance = null; });
        mPanel.show();
        // Transparent window so only the yellow-bordered box shows.
        if (mPanel.getWindow() != null)
            mPanel.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        refreshStatus();
    }

    private void addCat(LinearLayout box, int cat, int labelRes) {
        CheckBox cb = checkbox(mActivity, mActivity.getString(labelRes), false);
        mChecks.put(cat, cb);
        box.addView(cb);
    }

    private Set<Integer> selectedCats() {
        Set<Integer> result = new HashSet<>();
        for (Map.Entry<Integer, CheckBox> e : mChecks.entrySet())
            if (e.getValue().isChecked())
                result.add(e.getKey());
        return result;
    }

    private void refreshStatus() {
        final Context ctx = mActivity;
        String dir = OPML.getEximportDir();
        String friendly = friendlyDir(dir);
        if (mDirValue != null) {
            mDirValue.setText(friendly != null ? friendly : ctx.getString(R.string.eim_dir_unset));
            mDirValue.setTextColor(friendly != null ? fg() : WARN_COLOR);
        }
        if (mStatus != null) {
            if (dir.isEmpty()) {
                mStatus.setText(ctx.getString(R.string.eim_warn_nodir));
                mStatus.setTextColor(WARN_COLOR);
                mStatus.setAlpha(1f);
            } else {
                DocumentFile newest = newestExport(ctx, dir);
                if (newest == null) {
                    mStatus.setText(ctx.getString(R.string.eim_warn_none));
                    mStatus.setTextColor(WARN_COLOR);
                    mStatus.setAlpha(1f);
                } else {
                    mStatus.setText(ctx.getString(R.string.eim_last, fmtTs(newest.lastModified())));
                    mStatus.setTextColor(fg());
                    mStatus.setAlpha(0.8f);
                }
            }
        }
    }

    // ---- export ------------------------------------------------------------------------------

    private void onExportClicked() {
        final Set<Integer> cats = selectedCats();
        if (cats.isEmpty()) {
            UiUtils.styledToast(mActivity, R.string.eim_none_selected, Toast.LENGTH_SHORT);
            return;
        }
        final String dir = OPML.getEximportDir();
        if (dir.isEmpty()) {
            UiUtils.styledToast(mActivity, R.string.settings_export_choose_folder, Toast.LENGTH_SHORT);
            return;
        }
        final Activity activity = mActivity;
        new WaitDialog(activity, R.string.exportingToFile, () -> {
            try {
                final String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
                final String fileName = EXPORT_FILE_PREFIX + ts + EXPORT_FILE_EXT;
                final DocumentFile dirDoc = DocumentFile.fromTreeUri(MainApplication.getContext(), Uri.parse(dir));
                if (dirDoc == null || !dirDoc.canWrite())
                    throw new Exception("export directory not writable");
                // octet-stream keeps the exact name (a typed mime would append its own extension)
                final DocumentFile file = dirDoc.createFile("application/octet-stream", fileName);
                if (file == null)
                    throw new Exception("cannot create export file");
                // Same core the headless automation receiver calls — one ZIP, never two callers'
                // worth of duplicated export logic.
                OutputStream os = MainApplication.getContext().getContentResolver().openOutputStream(file.getUri());
                try {
                    StateZip.write(cats, os, null);
                } finally {
                    if (os != null)
                        os.close();
                }
                activity.runOnUiThread(() -> {
                    refreshStatus();
                    showResultDialog(activity.getString(R.string.eim_export_done, fileName), null);
                });
            } catch (Exception e) {
                e.printStackTrace();
                activity.runOnUiThread(() -> UiUtils.styledToast(activity,
                        activity.getString(R.string.eim_export_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG));
            }
        }).execute();
    }

    // ---- import ------------------------------------------------------------------------------

    private void onImportClicked() {
        if (selectedCats().isEmpty()) {
            UiUtils.styledToast(mActivity, R.string.eim_none_selected, Toast.LENGTH_SHORT);
            return;
        }
        GeneralPrefsActivity.startImport(GeneralPrefsActivity.REQ_IMPORT_EXIMPORT);
    }

    private void doImport(final Activity activity, final Uri uri) {
        final Set<Integer> cats = selectedCats();
        if (cats.isEmpty()) {
            UiUtils.styledToast(activity, R.string.eim_none_selected, Toast.LENGTH_SHORT);
            return;
        }
        new WaitDialog(activity, R.string.eim_importing, () -> {
            try {
                if (isZip(uri))
                    // A state ZIP written by this app (or by the automation receiver).
                    StateZip.read(MainApplication.getContext().getContentResolver().openInputStream(uri), cats);
                else {
                    // A plain OPML — every backup written before 2026-07-25, and any feed list
                    // exported from another reader.
                    OPML.sImportPrefCats = cats;
                    OPML.sImportFeeds = cats.contains(CAT_FEEDS);
                    OPML.sImportShowToast = false;
                    OPML.importFromFile(uri, false);
                }
                activity.runOnUiThread(() -> showImportResult(activity));
            } catch (Exception e) {
                e.printStackTrace();
                activity.runOnUiThread(() -> UiUtils.styledToast(activity,
                        activity.getString(R.string.eim_import_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG));
            } finally {
                OPML.sImportPrefCats = null;
                OPML.sImportFeeds = true;
                OPML.sImportShowToast = true;
            }
        }).execute();
    }

    // ---- result dialogs + close chain --------------------------------------------------------

    /** Yellow-bordered info dialog. onOk == null -> export variant (single OK closing the chain). */
    private void showResultDialog(String message, Runnable onRestart) {
        final Context ctx = mActivity;
        final int fg = fg(), bg = bg();
        LinearLayout resultBox = new LinearLayout(ctx);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setPadding(dp(22), dp(20), dp(22), dp(16));
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setCornerRadius(dpf(16));
        boxBg.setColor(bg);
        boxBg.setStroke(dp(2), fg);
        resultBox.setBackground(boxBg);
        resultBox.addView(text(ctx, message, 15, fg, true));

        final AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setView(new ScrollView(ctx) {{ setPadding(dp(10), dp(10), dp(10), dp(10)); setClipToPadding(false); addView(resultBox); }})
                .setCancelable(false)
                .create();

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        btns.setPadding(0, dp(16), 0, 0);
        if (onRestart == null) {
            Button ok = pill(ctx, ctx.getString(android.R.string.ok));
            ok.setOnClickListener(v -> { dialog.dismiss(); closeChain(); });
            btns.addView(ok);
        } else {
            Button later = pill(ctx, ctx.getString(R.string.eim_restart_later));
            LinearLayout.LayoutParams laterLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            laterLp.rightMargin = dp(10);
            later.setOnClickListener(v -> { dialog.dismiss(); closeChain(); });
            btns.addView(later, laterLp);
            Button now = pill(ctx, ctx.getString(R.string.eim_restart_now));
            now.setOnClickListener(v -> onRestart.run());
            btns.addView(now);
        }
        resultBox.addView(btns);

        dialog.show();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    private void showImportResult(final Activity activity) {
        showResultDialog(activity.getString(R.string.eim_import_done_title) + "\n\n"
                        + activity.getString(R.string.eim_import_done_body),
                () -> restartApp(activity));
    }

    /** Success chain: the info dialog is already gone — close the panel and exit Settings fully. */
    private void closeChain() {
        dismissPanel();
        if (!mActivity.isFinishing())
            mActivity.finish();
    }

    private void dismissPanel() {
        if (mPanel != null && mPanel.isShowing())
            mPanel.dismiss();
        mPanel = null;
    }

    private static void restartApp(Activity activity) {
        Context appCtx = activity.getApplicationContext();
        PackageManager pm = appCtx.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(appCtx.getPackageName());
        if (intent == null)
            return;
        appCtx.startActivity(Intent.makeRestartActivityTask(intent.getComponent()));
        Runtime.getRuntime().exit(0);
    }

    // ---- small view helpers ------------------------------------------------------------------

    private static TextView text(Context ctx, String s, int sizeSp, int color, boolean bold) {
        TextView tv = new TextView(ctx);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setTextColor(color);
        if (bold)
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private View divider(Context ctx, int color, int heightDp, int topMargin) {
        View v = new View(ctx);
        v.setBackgroundColor(color);
        v.setAlpha(0.4f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        lp.topMargin = topMargin;
        v.setLayoutParams(lp);
        return v;
    }

    private CheckBox checkbox(Context ctx, String label, boolean bold) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(label);
        cb.setTextColor(fg());
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (bold)
            cb.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= 21)
            cb.setButtonTintList(ColorStateList.valueOf(fg()));
        cb.setPadding(dp(8), dp(7), 0, dp(7));
        cb.setChecked(true);
        return cb;
    }

    /** ArcaneChat pill: fully-round black button with a 1.5dp yellow stroke and yellow text. */
    private Button pill(Context ctx, String label) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg());
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dpf(100));
        d.setColor(bg());
        d.setStroke((int) dpf(1.5f), fg());
        b.setBackground(d);
        if (Build.VERSION.SDK_INT >= 21)
            b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(20), dp(10), dp(20), dp(10));
        return b;
    }

    /** Decode a SAF tree URI to a friendly "volume:/path" for display; null when no dir chosen. */
    static String friendlyDir(String uriStr) {
        if (uriStr == null || uriStr.isEmpty())
            return null;
        try {
            Uri u = Uri.parse(uriStr);
            String docId = DocumentsContract.getTreeDocumentId(u);
            if (docId == null)
                return uriStr;
            int colon = docId.indexOf(':');
            String vol = colon >= 0 ? docId.substring(0, colon) : "";
            String path = colon >= 0 ? docId.substring(colon + 1) : docId;
            if (path.isEmpty())
                path = "/";
            return ("primary".equals(vol) || vol.isEmpty()) ? path : (vol + ":/" + path);
        } catch (Exception e) {
            return uriStr;
        }
    }

    /** Newest export file in the chosen directory (by lastModified), or null. */
    private static DocumentFile newestExport(Context ctx, String treeUri) {
        try {
            DocumentFile dir = DocumentFile.fromTreeUri(ctx, Uri.parse(treeUri));
            if (dir == null || !dir.isDirectory())
                return null;
            DocumentFile newest = null;
            for (DocumentFile f : dir.listFiles()) {
                String name = f.getName();
                if (!f.isFile() || name == null
                        || !(name.startsWith(EXPORT_FILE_PREFIX) || name.startsWith(EXPORT_FILE_PREFIX_LEGACY)))
                    continue;
                if (!name.endsWith(EXPORT_FILE_EXT) && !name.endsWith(".opml") && !name.endsWith(".backup"))
                    continue;
                if (newest == null || f.lastModified() > newest.lastModified())
                    newest = f;
            }
            return newest;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sniff the `PK\003\004` header so a picked file is routed to the right reader. */
    private static boolean isZip(Uri uri) {
        InputStream is = null;
        try {
            is = MainApplication.getContext().getContentResolver().openInputStream(uri);
            if (is == null)
                return false;
            byte[] head = new byte[4];
            int read = 0;
            while (read < head.length) {
                int n = is.read(head, read, head.length - read);
                if (n < 0)
                    break;
                read += n;
            }
            return read == head.length && StateZip.isZip(head);
        } catch (Exception e) {
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
        }
    }

    private static String fmtTs(long t) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(t));
    }
}

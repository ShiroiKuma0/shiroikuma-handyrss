package ru.yanus171.feedexfork.view;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.preference.Preference;
import android.provider.DocumentsContract;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;

import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.activity.GeneralPrefsActivity;
import ru.yanus171.feedexfork.parser.OPML;
import ru.yanus171.feedexfork.service.AutoWorker;
import ru.yanus171.feedexfork.utils.PrefUtils;
import ru.yanus171.feedexfork.utils.Theme;
import ru.yanus171.feedexfork.utils.UiUtils;

/**
 * HandyRss: one boxed "Export / Import" block on the 白い熊 Handy RSS UI screen.
 * The block kind (settings / feeds / backup) is derived from android:key. Renders a title,
 * a tappable bordered directory box (persisted SAF tree URI), a "Last export/backup" line and
 * Export/Import (or Backup now/Restore) buttons. Builds its view in onCreateView (the legacy
 * android.preference framework) and refreshes its fields directly via refresh().
 */
public class ExportImportPreference extends Preference {

    private TextView mDirText;
    private TextView mLastText;

    public ExportImportPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSelectable(false);
    }

    public ExportImportPreference(Context context) {
        super(context);
        setSelectable(false);
    }

    private int kind() {
        final String key = getKey();
        if (OPML.EXPORT_DIR_FEEDS.equals(key)) return OPML.KIND_FEEDS;
        if (OPML.EXPORT_DIR_BACKUP.equals(key)) return OPML.KIND_BACKUP;
        return OPML.KIND_SETTINGS;
    }

    private String dirUri() { return PrefUtils.getString(getKey(), ""); }

    private long lastTime() {
        switch (kind()) {
            case OPML.KIND_FEEDS:  return PrefUtils.getLong(OPML.EXPORT_LAST_FEEDS, 0);
            case OPML.KIND_BACKUP: return PrefUtils.getLong(AutoWorker.LAST_JOB_OCCURRED + PrefUtils.AUTO_BACKUP_INTERVAL, 0);
            default:               return PrefUtils.getLong(OPML.EXPORT_LAST_SETTINGS, 0);
        }
    }

    private int dp(int v) { return UiUtils.dpToPixel(v); }

    @SuppressLint("MissingSuperCall")
    @Override
    protected View onCreateView(ViewGroup parent) {
        final Context ctx = getContext();
        final int fg = Theme.GetChromeFgInt();
        final int bg = Theme.GetChromeBgInt();

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(10), 0, dp(14));

        // Indent to mirror the font hierarchy: box title at sub-head level (50dp),
        // its subitems (directory box / last line / buttons) at row level (100dp).
        final int titleIndent = dp(50), itemIndent = dp(100), rightInset = dp(16);

        TextView title = new TextView(ctx);
        title.setText(getTitle());
        title.setTextColor(fg);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin = titleIndent;
        root.addView(title, titleLp);

        mDirText = new TextView(ctx);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(bg);
        boxBg.setStroke(dp(2), fg);
        boxBg.setCornerRadius(dp(6));
        mDirText.setBackground(boxBg);
        mDirText.setTextColor(fg);
        mDirText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mDirText.setPadding(dp(12), dp(10), dp(12), dp(10));
        mDirText.setOnClickListener(v -> GeneralPrefsActivity.startDirPick(getKey()));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = dp(8);
        boxLp.leftMargin = itemIndent;
        boxLp.rightMargin = rightInset;
        root.addView(mDirText, boxLp);

        mLastText = new TextView(ctx);
        mLastText.setTextColor(fg);
        mLastText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lastLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lastLp.topMargin = dp(6);
        lastLp.leftMargin = itemIndent;
        root.addView(mLastText, lastLp);

        final boolean backup = kind() == OPML.KIND_BACKUP;
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(10);
        rowLp.leftMargin = itemIndent;
        rowLp.rightMargin = rightInset;

        Button bExport = makeButton(ctx, ctx.getString(backup ? R.string.settings_export_btn_backup : R.string.settings_export_btn_export), fg, bg);
        Button bImport = makeButton(ctx, ctx.getString(backup ? R.string.settings_export_btn_restore : R.string.settings_export_btn_import), fg, bg);
        bExport.setOnClickListener(v -> onExport());
        bImport.setOnClickListener(v -> onImport());
        LinearLayout.LayoutParams bLp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bLp1.rightMargin = dp(8);
        btnRow.addView(bExport, bLp1);
        btnRow.addView(bImport, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btnRow, rowLp);

        refresh();
        return root;
    }

    private Button makeButton(Context ctx, String text, int fg, int bg) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(fg);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setStroke(dp(2), fg);
        d.setCornerRadius(dp(6));
        b.setBackground(d);
        return b;
    }

    // Re-read the chosen directory + last-export time and update the row in place
    // (notifyChanged() would not re-run a code-built onCreateView).
    public void refresh() {
        if (mDirText != null) {
            String f = friendlyDir(dirUri());
            mDirText.setText(f != null ? f : getContext().getString(R.string.settings_export_dir_hint));
        }
        if (mLastText != null) {
            long t = lastTime();
            String when = t > 0 ? new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(t))
                                : getContext().getString(R.string.never);
            int label = kind() == OPML.KIND_BACKUP ? R.string.settings_backup_last : R.string.settings_export_last;
            mLastText.setText(getContext().getString(label, when));
        }
    }

    private void onExport() {
        Activity a = GeneralPrefsActivity.mActivity;
        if (a == null) return;
        String dir = dirUri();
        if (dir == null || dir.isEmpty()) {
            Toast.makeText(getContext(), R.string.settings_export_choose_folder, Toast.LENGTH_SHORT).show();
            return;
        }
        switch (kind()) {
            case OPML.KIND_FEEDS:  OPML.ExportFeedsToDir(a, dir); break;
            case OPML.KIND_BACKUP: OPML.ExportBackupToDir(a, dir); break;
            default:               OPML.ExportSettingsToDir(a, dir); break;
        }
    }

    private void onImport() {
        if (kind() == OPML.KIND_SETTINGS)
            GeneralPrefsActivity.startImport(GeneralPrefsActivity.REQ_IMPORT_SETTINGS);
        else // feeds + backup restore share the feed-import path (remove-existing prompt)
            GeneralPrefsActivity.startImport(GeneralPrefsActivity.REQ_IMPORT_FEEDS);
    }

    // Decode a SAF tree URI to a friendly "volume:/path" for display; null when no dir chosen.
    static String friendlyDir(String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return null;
        try {
            Uri u = Uri.parse(uriStr);
            String docId = DocumentsContract.getTreeDocumentId(u);
            if (docId == null) return uriStr;
            int colon = docId.indexOf(':');
            String vol = colon >= 0 ? docId.substring(0, colon) : "";
            String path = colon >= 0 ? docId.substring(colon + 1) : docId;
            if (path.isEmpty()) path = "/";
            return ("primary".equals(vol) || vol.isEmpty()) ? path : (vol + ":/" + path);
        } catch (Exception e) {
            return uriStr;
        }
    }
}

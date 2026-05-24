package ru.yanus171.feedexfork.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import ru.yanus171.feedexfork.R;
import ru.yanus171.feedexfork.utils.Theme;
import ru.yanus171.feedexfork.utils.FontUtil;
import ru.yanus171.feedexfork.utils.UiUtils;

public class FontSizePreference extends Preference {
    private static final int MAX = 48;
    public FontSizePreference(Context context, AttributeSet attrs) { super(context, attrs); }
    public FontSizePreference(Context context) { super(context); }
    private String cat() { String k = getKey(); int i = k.lastIndexOf('_'); return i >= 0 ? k.substring(i + 1) : k; }
    private int getValue() { try { return Integer.parseInt(getPersistedString("0")); } catch (Exception e) { return 0; } }
    private void update(TextView label, TextView valueView, TextView preview, int value) {
        String t = getTitle() == null ? "" : getTitle().toString();
        label.setText(t);
        valueView.setText(value > 0 ? value + " sp" : "Default");
        preview.setTypeface(FontUtil.typeface(cat()));
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, value > 0 ? value : 18);
    }
    @SuppressLint("MissingSuperCall")
    @Override
    protected View onCreateView(ViewGroup parent) {
        Context ctx = getContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiUtils.dpToPixel(32), UiUtils.dpToPixel(2), UiUtils.dpToPixel(8), UiUtils.dpToPixel(2));
        final TextView label = new TextView(ctx);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        label.setTextColor(0xFF989898);
        root.addView(label);
        final TextView valueView = new TextView(ctx);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        valueView.setTextColor(Theme.GetChromeFgInt());
        root.addView(valueView);
        final TextView preview = new TextView(ctx);
        preview.setTextColor(Theme.GetChromeFgInt());
        preview.setText("AaIiMmOoQqWw 012 \u767d\u3044\u718a\u76f8\u64b2\u9053 \u00e1\u00c1\u010d\u010c\u010f\u010e\u00e9\u00c9\u011b\u011a\u00ed\u00cd\u0148\u0147\u00f3\u00d3r\u0158\u0160\u0160\u0165\u0164\u00fa\u00da\u016f\u016e\u00dd\u00dd\u017e\u017d");
        root.addView(preview);
        final SeekBar bar = new SeekBar(ctx);
        bar.setMax(MAX);
        int value = Math.max(0, Math.min(MAX, getValue()));
        bar.setProgress(value);
        root.addView(bar);
        update(label, valueView, preview, value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { persistString(String.valueOf(progress)); update(label, valueView, preview, progress); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return root;
    }
}

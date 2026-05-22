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

import ru.yanus171.feedexfork.utils.FontUtil;

public class FontSizePreference extends Preference {
    private static final int MAX = 48;
    public FontSizePreference(Context context, AttributeSet attrs) { super(context, attrs); }
    public FontSizePreference(Context context) { super(context); }
    private String cat() { String k = getKey(); int i = k.lastIndexOf('_'); return i >= 0 ? k.substring(i + 1) : k; }
    private int getValue() { try { return Integer.parseInt(getPersistedString("0")); } catch (Exception e) { return 0; } }
    private void update(TextView label, TextView preview, int value) {
        String t = getTitle() == null ? "" : getTitle().toString();
        label.setText(value > 0 ? t : t + " \u2014 Default");
        preview.setTypeface(FontUtil.typeface(cat()));
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, value > 0 ? value : 18);
    }
    @SuppressLint("MissingSuperCall")
    @Override
    protected View onCreateView(ViewGroup parent) {
        Context ctx = getContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 24, 40, 24);
        final TextView label = new TextView(ctx);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        root.addView(label);
        final TextView preview = new TextView(ctx);
        preview.setText("Aa Bb Cc  0123  P\u0159\u00edklad  \u3042\u6f22\u5b57");
        root.addView(preview);
        final SeekBar bar = new SeekBar(ctx);
        bar.setMax(MAX);
        int value = Math.max(0, Math.min(MAX, getValue()));
        bar.setProgress(value);
        root.addView(bar);
        update(label, preview, value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { persistString(String.valueOf(progress)); update(label, preview, progress); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        return root;
    }
}

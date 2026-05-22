package ru.yanus171.feedexfork.utils;

import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.widget.TextView;

import ru.yanus171.feedexfork.view.FontSelectPreference;

/**
 * Per-surface font (family + weight + size). Each surface is fully independent:
 * if unset it falls back ONLY to the app-wide font ("fontFamily") / the caller's
 * default size - never to any other surface. No global "default" cascade.
 */
public class FontUtil {
    public static final String TITLE     = "title";
    public static final String DRAWER    = "drawer";
    public static final String LIST_TEXT = "listtext";
    public static final String BODY      = "body";
    public static final String MENU      = "menu";

    private static String key(String base, String cat) { return base + "_" + cat; }

    private static String familyName(String cat) {
        String v = PrefUtils.getString(key("font_family", cat), "");
        if (v.isEmpty()) v = PrefUtils.getString(FontSelectPreference.KEY, "");
        return v;
    }
    private static int weight(String cat) { return PrefUtils.getIntFromText(key("font_weight", cat), 0); }
    private static int sizeSp(String cat) { return PrefUtils.getIntFromText(key("font_size", cat), 0); }

    public static Typeface typeface(String cat) {
        Typeface base = FontSelectPreference.GetTypeFaceByName(familyName(cat));
        int w = weight(cat);
        if (w > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) base = Typeface.create(base, w, false);
            else base = Typeface.create(base, w >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        }
        return base;
    }
    public static void apply(TextView tv, String cat, float fallbackSizeDip) {
        tv.setTypeface(typeface(cat));
        int sp = sizeSp(cat);
        if (sp > 0) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        else tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, fallbackSizeDip);
    }
}

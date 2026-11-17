package site.navatv.companion;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;

class Surface {
    static final int PAPER = 0xfff6f4ed,
            INK = 0xff232e28,
            MUTED = 0xff536158,
            LINE = 0xffd8dbd1,
            PALE = 0xffe8ebe2,
            GREEN = 0xff244337,
            WHITE = 0xfffffcf4,
            ACCENT = 0xff9c442a,
            APRICOT = 0xfff0af8f,
            WRAP = -2,
            MATCH = -1;
    final Context context;
    final Typeface serif, sans;

    Surface(Context c) {
        context = c;
        serif = face("fonts/InstrumentSerif-Regular.ttf", Typeface.SERIF);
        sans = face("fonts/Manrope.ttf", Typeface.SANS_SERIF);
    }

    private Typeface face(String name, Typeface fallback) {
        try {
            return Typeface.createFromAsset(context.getAssets(), name);
        } catch (RuntimeException absent) {
            return fallback;
        }
    }

    int d(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(context);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    void pad(View v, int l, int t, int r, int b) {
        v.setPadding(d(l), d(t), d(r), d(b));
    }

    LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(
                width < 0 ? width : d(width), height < 0 ? height : d(height));
    }

    LinearLayout.LayoutParams weight(int height) {
        return new LinearLayout.LayoutParams(0, height < 0 ? height : d(height), 1f);
    }

    TextView text(String s, float size, int color, int weight) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        t.setTypeface(sans);
        t.setFontVariationSettings("'wght' " + weight);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    TextView title(String s, float size, int color) {
        TextView t = text(s, size, color, 400);
        t.setFontVariationSettings(null);
        t.setTypeface(serif);
        t.setLetterSpacing(-.03f);
        return t;
    }

    GradientDrawable shape(int color, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(d(radius));
        if (stroke != 0) g.setStroke(d(1), stroke);
        return g;
    }

    void action(View view, int color, int radius, int stroke, Runnable click) {
        view.setBackground(
                new RippleDrawable(
                        ColorStateList.valueOf(0x259e9f8e), shape(color, radius, stroke), null));
        view.setOnClickListener(v -> click.run());
        view.setFocusable(true);
    }

    void space(LinearLayout l, int n) {
        l.addView(new Space(context), lp(MATCH, n));
    }

    void gap(LinearLayout l, int n) {
        l.addView(new Space(context), lp(n, 1));
    }

    View rule() {
        View v = new View(context);
        v.setBackgroundColor(LINE);
        return v;
    }

    void addHomeBar(LinearLayout page, Runnable home) {
        LinearLayout bar = column();
        bar.setGravity(Gravity.CENTER);
        pad(bar, 14, 4, 14, 5);
        LinearLayout button = button("Home", "home", false, home);
        bar.addView(button, lp(152, WRAP));
        page.addView(bar, lp(MATCH, WRAP));
    }

    LinearLayout button(String name, String icon, boolean primary, Runnable click) {
        LinearLayout b = row();
        b.setGravity(Gravity.CENTER);
        pad(b, 12, 0, 12, 0);
        b.setMinimumHeight(d(48));
        if (icon != null) {
            b.addView(new Art(context, icon, primary ? WHITE : INK), lp(20, 20));
            gap(b, 7);
        }
        b.addView(text(name, 14, primary ? WHITE : INK, 650));
        action(b, primary ? GREEN : PALE, 16, primary ? 0 : LINE, click);
        b.setContentDescription(name);
        return b;
    }
}

package site.navatv.rustpreview;

import android.app.Activity;
import android.app.ActivityManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import site.navatv.launcher.RegionStore;

/** Asks once more, then puts OpenNava on this television back to how it was when installed. */
public final class ResetActivity extends Activity {
    private static final int WHITE = 0xfffbfaff, SOFT = 0xffb9bbd0, LILAC = 0xffcabaff, INK = 0xff17122b,
            RED = 0xffff6b74;
    private Typeface sans, serif;
    private boolean english, working;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        sans = font("assets/fonts/Manrope.ttf", Typeface.SANS_SERIF);
        serif = font("assets/fonts/InstrumentSerif-Regular.ttf", Typeface.SERIF);
        english = RegionStore.english(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(90), dp(30), dp(90), dp(30));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xff241c47, 0xff0c0b18, 0xff2a1424}));

        TextView title = text(say("OpenNava zurücksetzen?", "Reset OpenNava?"), 52, WHITE, 400);
        title.setTypeface(serif);
        title.setFontVariationSettings(null);
        page.addView(title, wrap(0));
        TextView what = text(say(
                "Gelöscht werden: Einrichtung, Sprache und Land, eigene Mediatheken und Streams, Hintergrundfotos,\n"
                        + "Startseite, Merkliste, Aufnahmen und die YouTube-Anmeldung.",
                "This deletes: setup, language and country, your own libraries and streams, background photos,\n"
                        + "home screen, watchlist, recordings and the YouTube sign-in."),
                16, SOFT, 540);
        what.setGravity(Gravity.CENTER);
        what.setLineSpacing(dp(5), 1);
        page.addView(what, wrap(20));
        TextView note = text(say("Das lässt sich nicht rückgängig machen. Andere Apps und der Fernseher bleiben unverändert.",
                "This cannot be undone. Your other apps and the TV itself stay as they are."), 15, 0xffffb4a8, 700);
        note.setGravity(Gravity.CENTER);
        page.addView(note, wrap(16));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        TextView cancel = button(say("Abbrechen", "Cancel"), LILAC, this::finish);
        buttons.addView(cancel);
        TextView reset = button(say("Alles zurücksetzen", "Reset everything"), RED, this::reset);
        LinearLayout.LayoutParams gap = wrap(0);
        gap.leftMargin = dp(22);
        buttons.addView(reset, gap);
        page.addView(buttons, wrap(38));
        setContentView(page);
        // The harmless answer is selected first, so an extra press of OK deletes nothing.
        cancel.post(cancel::requestFocus);
    }

    private void reset() {
        if (working) return;
        working = true;
        // Android removes this app's data and ends it; the next start begins with the setup.
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager == null || !manager.clearApplicationUserData()) {
            working = false;
            android.widget.Toast.makeText(this, say("Zurücksetzen nicht möglich.", "Could not reset."),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private String say(String german, String englishText) { return english ? englishText : german; }

    private TextView button(String label, int focus, Runnable run) {
        TextView button = text(label, 16, WHITE, 780);
        button.setSingleLine();
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(30), dp(15), dp(30), dp(15));
        button.setFocusable(true);
        button.setBackground(shape(0x1fffffff));
        button.setOnFocusChangeListener((v, focused) -> {
            button.setBackground(shape(focused ? focus : 0x1fffffff));
            button.setTextColor(focused ? INK : WHITE);
            button.animate().scaleX(focused ? 1.07f : 1).scaleY(focused ? 1.07f : 1).setDuration(160).start();
        });
        button.setOnClickListener(v -> run.run());
        return button;
    }

    private TextView text(String value, float size, int color, int weight) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTypeface(sans);
        view.setFontVariationSettings("'wght' " + weight);
        view.setIncludeFontPadding(false);
        return view;
    }

    private Typeface font(String asset, Typeface fallback) {
        try { return Typeface.createFromAsset(getAssets(), asset); }
        catch (RuntimeException absent) { return fallback; }
    }

    private GradientDrawable shape(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(28));
        return shape;
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = dp(top);
        return p;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

package site.navatv.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import site.navatv.updates.LauncherProfile;

public final class LauncherChoiceActivity extends Activity {
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setBackgroundColor(0xff14161d);
        TextView title = new TextView(this);
        title.setText("Startoberfläche");
        title.setTextSize(32);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        page.addView(title);
        TextView hint = new TextView(this);
        hint.setText("Für die Home-Taste und die Handy-App");
        hint.setTextSize(16);
        hint.setTextColor(0xffc5c2ce);
        hint.setPadding(0,dp(6),0,dp(16));
        page.addView(hint);

        String current = LauncherChoice.selected(this);
        if (current.isEmpty()) current = getIntent().getStringExtra("current");
        if (current == null) current = getPackageName().equals(LauncherChoice.RUST_APP) ? LauncherProfile.RUST : LauncherProfile.CLASSIC;
        RadioButton focus = null;
        String[][] modes = {{LauncherProfile.RUST,"Neuer Launcher"},{LauncherProfile.CLASSIC,"Classic"},{LauncherProfile.SENIOR,"Seniorenansicht"}};
        for (String[] option : modes) {
            String mode = option[0];
            RadioButton button = new RadioButton(this);
            boolean available = LauncherChoice.available(this, mode);
            button.setChecked(mode.equals(current));
            button.setText(available ? option[1] : option[1] + " · TV-App installieren oder aktualisieren");
            button.setTextSize(18);
            button.setPadding(dp(12),0,dp(12),0);
            button.setEnabled(available);
            button.setOnClickListener(v -> {
                if (LauncherChoice.choose(this, mode) && LauncherChoice.openSelected(this)) {
                    finish();
                } else {
                    button.setChecked(mode.equals(LauncherChoice.selected(this)));
                    Toast.makeText(this, "Die Oberfläche konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show();
                }
            });
            button.setOnFocusChangeListener((v, active) -> style(button, active));
            style(button, false);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(370),dp(51));
            size.topMargin = dp(7);
            page.addView(button,size);
            if (available && (focus == null || mode.equals(current))) focus = button;
        }
        setContentView(page);
        if (focus != null) focus.requestFocus();
    }

    private void style(RadioButton button, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(focused ? 0xffded3f1 : 0xff292b34);
        button.setBackground(bg);
        int ink = focused ? 0xff211e2a : button.isEnabled() ? Color.WHITE : 0xffadaab1;
        button.setTextColor(ink);
        button.setButtonTintList(android.content.res.ColorStateList.valueOf(ink));
    }
}

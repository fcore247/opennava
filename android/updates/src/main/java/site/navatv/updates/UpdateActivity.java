package site.navatv.updates;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;
import java.lang.ref.WeakReference;

public final class UpdateActivity extends Activity {
    private static WeakReference<UpdateActivity> active = new WeakReference<>(null);
    private TextView status;
    private Button action;
    private Switch automatic;
    private ReleaseFeed release;
    private boolean busy, awaitingSettings;
    static UpdateActivity foreground() { return active.get(); }
    void message(String text) { status.setText(text); }
    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff152620);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        scroll.addView(panel);
        TextView title = new TextView(this);
        title.setText("Software updates");
        title.setTextColor(Color.WHITE); title.setTextSize(30);
        panel.addView(title);
        status = new TextView(this);
        status.setTextSize(17); status.setTextColor(0xffe3ece0);
        status.setPadding(0, dp(14), 0, dp(16)); panel.addView(status);
        automatic = new Switch(this);
        automatic.setText("Automatic updates"); automatic.setTextColor(Color.WHITE);
        automatic.setMinHeight(dp(52)); automatic.setChecked(Updates.enabled(this));
        automatic.setOnCheckedChangeListener((v, enabled) -> {
            Updates.prefs(this).edit().putBoolean("automatic", enabled).apply();
            Updates.schedule(this);
        });
        panel.addView(automatic);
        action = new Button(this); action.setAllCaps(false); panel.addView(action);
        action.setOnClickListener(v -> { if (release == null) check(); else install(); });
        Button close = new Button(this); close.setAllCaps(false); close.setText("Back");
        close.setOnClickListener(v -> finish()); panel.addView(close);
        if (ReleaseFeed.PHONE.equals(getPackageName())) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setBackgroundColor(0xff152620);
            page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            Button home = new Button(this);
            home.setText("Home");
            home.setAllCaps(false);
            home.setMinHeight(dp(50));
            home.setOnClickListener(v -> {
                setResult(RESULT_OK);
                finish();
            });
            LinearLayout.LayoutParams footer = new LinearLayout.LayoutParams(-1, -2);
            footer.setMargins(dp(24), dp(4), dp(24), dp(12));
            page.addView(home, footer);
            setContentView(page);
        } else {
            setContentView(scroll);
        }
        check();
    }

    @Override protected void onResume() {
        super.onResume(); active = new WeakReference<>(this);
        if (awaitingSettings) {
            awaitingSettings = false;
            if (getPackageManager().canRequestPackageInstalls()) install();
            else message("To install from this app, allow OpenNava under Install unknown apps, or use the paired update tool.");
        }
    }

    @Override protected void onPause() {
        if (foreground() == this) active.clear();
        super.onPause();
    }

    private void check() {
        busy = true; action.setEnabled(false); action.setText("Checking…"); message("Checking the latest stable release…");
        Updates.WORK.execute(() -> {
            try {
                ReleaseFeed latest = ReleaseFeed.latest(getPackageName());
                boolean newer = latest.versionCode > Updates.current(this);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    release = newer ? latest : null;
                    message(newer ? "OpenNava " + latest.version + " is ready to install." :
                            "You are up to date. Your settings and recordings stay on your device.");
                    action.setText(newer ? "Install update" : "Check again");
                    action.setEnabled(true); busy = false;
                });
            } catch (Exception failure) { problem(failure); }
        });
    }

    private void install() {
        if (busy || release == null) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            try {
                awaitingSettings = true;
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException unavailable) {
                awaitingSettings = false; message("Use the paired update tool to install on this device.");
            }
            return;
        }
        busy = true; action.setEnabled(false); message("Downloading and verifying the signed update…");
        Updates.WORK.execute(() -> {
            try {
                Updates.install(this, release, true);
                runOnUiThread(() -> { if (!isDestroyed()) { busy = false; action.setEnabled(true); message("Installation sent to Android."); } });
            } catch (Exception failure) { problem(failure); }
        });
    }

    private void problem(Exception failure) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            release = null; busy = false; action.setText("Check again"); action.setEnabled(true);
            message("Update unavailable. " + (failure.getMessage() == null ? "Try again later." : failure.getMessage()));
        });
    }
}

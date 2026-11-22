package site.navatv.updates;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.ref.WeakReference;

/** Explicit TV-only installation and same-task launch of the separately installed release preview. */
public final class PreviewActivity extends Activity {
    private static final int PREVIEW = 51;
    private static final int INSTALL_PERMISSION = 52;
    private static WeakReference<PreviewActivity> owner = new WeakReference<>(null);
    private TextView status;
    private Button primary, useHome, update;
    private boolean busy, installed, visible;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ReleaseFeed.TV.equals(getPackageName())) { finish(); return; }
        Updates.prefs(this).edit().putBoolean("rustPreviewHintHandled", true).apply();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff152620);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        scroll.addView(panel);
        TextView title = new TextView(this);
        title.setText("Rust preview"); title.setTextSize(30); title.setTextColor(Color.WHITE);
        panel.addView(title);
        TextView summary = new TextView(this);
        summary.setText(getIntent().getBooleanExtra(RustHome.FULL_SETTINGS, false)
                ? "Try Rust once or use it each time you open OpenNava. Choose “Zur klassischen Ansicht” in Rust to switch back."
                : "Try the new TV interface as a separate app. Back returns to your usual OpenNava view.");
        summary.setTextSize(17); summary.setTextColor(0xffe3ece0);
        summary.setPadding(0, dp(12), 0, dp(8)); panel.addView(summary);
        status = new TextView(this);
        status.setTextSize(16); status.setTextColor(0xffe3ece0);
        status.setPadding(0, dp(4), 0, dp(12)); panel.addView(status);
        primary = button(panel, "Install preview", v -> { if (installed) launch(); else requestInstall(); });
        useHome = button(panel, "Use Rust as my launcher", v -> chooseHome());
        update = button(panel, "Check for preview update", v -> requestInstall());
        button(panel, "Back", v -> finish());
        setContentView(scroll);
        owner = new WeakReference<>(this);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private Button button(LinearLayout panel, String text, View.OnClickListener action) {
        Button button = new Button(this);
        button.setAllCaps(false); button.setText(text); button.setOnClickListener(action);
        panel.addView(button); return button;
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        if (!busy && status != null) render(null);
    }

    @Override protected void onStop() {
        visible = false;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (owner.get() == this) owner.clear();
        super.onDestroy();
    }

    private void render(String message) {
        if (isFinishing() || isDestroyed()) return;
        try {
            PackageInfo info = RustHome.installed(this);
            installed = info != null;
            primary.setText(installed ? "Open Rust preview" : "Install Rust preview");
            primary.setEnabled(!busy); update.setEnabled(!busy);
            update.setVisibility(installed ? View.VISIBLE : View.GONE);
            useHome.setVisibility(installed && getIntent().getBooleanExtra(RustHome.FULL_SETTINGS, false) ? View.VISIBLE : View.GONE);
            useHome.setText(RustHome.preferred(this) ? "Use classic launcher" : "Use Rust as my launcher");
            useHome.setEnabled(!busy);
            status.setText(message != null ? message : installed ? "Installed version " + info.versionName : "Optional download from OpenNava Releases");
        } catch (Exception problem) {
            installed = false; primary.setEnabled(false); update.setVisibility(View.GONE); useHome.setVisibility(View.GONE);
            status.setText("Cannot use this preview. " + reason(problem));
        }
    }

    private void launch() {
        try {
            if (RustHome.installed(this) == null) { render(null); return; }
            Intent intent = RustHome.intent().putExtra("opennava.fromClassic", !RustHome.preferred(this));
            startActivityForResult(intent, PREVIEW);
        } catch (Exception problem) { render("Could not open the preview. " + reason(problem)); }
    }

    private void chooseHome() {
        if (busy || !getIntent().getBooleanExtra(RustHome.FULL_SETTINGS, false)) return;
        try {
            if (RustHome.preferred(this)) {
                if (!RustHome.useClassic(this)) throw new IllegalStateException("Your TV could not save the launcher selection.");
            } else {
                RustHome.choose(this);
            }
            setResult(RESULT_OK);
            finish();
        } catch (Exception failure) {
            render("Could not change the launcher. " + reason(failure));
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() == null ? "Please try again." : e.getMessage();
    }

    private void requestInstall() {
        if (busy) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION);
            } catch (RuntimeException unavailable) { render("Allow OpenNava to install apps in your TV settings."); }
            return;
        }
        busy = true; render("Checking the official release…");
        Updates.WORK.execute(() -> {
            try {
                ReleaseFeed release = ReleaseFeed.latest(ReleaseFeed.PREVIEW);
                PackageInfo existing = RustHome.installed(this);
                if (existing != null && release.versionCode <= ReleaseFeed.code(existing)) {
                    runOnUiThread(() -> complete("The installed preview is up to date."));
                    return;
                }
                runOnUiThread(() -> render("Downloading and verifying the preview…"));
                Updates.install(this, release, true);
            } catch (Exception failure) { runOnUiThread(() -> complete("Installation unavailable. " + reason(failure))); }
        });
    }

    private void complete(String message) {
        busy = false;
        render(message);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PREVIEW) {
            setResult(RESULT_OK); finish();
        } else if (request == INSTALL_PERMISSION) {
            if (getPackageManager().canRequestPackageInstalls()) requestInstall();
            else render("Installation needs permission under Install unknown apps.");
        }
    }

    static void installResult(Context context, Intent result, int status) {
        PreviewActivity activity = owner.get();
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
            if (activity != null && activity.visible && !activity.isFinishing() && confirmation != null) {
                try {
                    activity.startActivity(confirmation);
                    return;
                } catch (RuntimeException ignored) { /* Fall back to a new interactive attempt. */ }
            }
            int id = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
            if (id >= 0) {
                try { context.getPackageManager().getPackageInstaller().abandonSession(id); }
                catch (RuntimeException ignored) { /* Already closed. */ }
            }
            if (activity != null) activity.complete("Open the preview settings to confirm installation.");
        } else if (activity != null) {
            activity.complete(status == PackageInstaller.STATUS_SUCCESS
                    ? "Preview installed." : "Installation did not complete. You can try again.");
        }
    }
}

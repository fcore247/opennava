package site.navatv.updates;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.lang.ref.WeakReference;

public final class UpdateActivity extends Activity {
    private static volatile WeakReference<UpdateActivity> active = new WeakReference<>(null);
    private static final int PREVIEW = 41, INSTALL_PERMISSION = 42;
    private TextView status;
    private Button action, refresh, download;
    private ReleaseFeed release;
    private boolean busy, autoStart, german;
    private volatile boolean automaticInstall;

    static UpdateActivity foreground() { return active.get(); }

    static boolean allowsInstallation(Context origin) {
        UpdateActivity screen = foreground();
        return screen == origin && screen != null && (!screen.automaticInstall || Updates.enabled(screen));
    }

    private String label(String en, String de) { return german ? de : en; }
    private String checkLabel() {
        return ReleaseFeed.PHONE.equals(getPackageName()) ? label("Check again", "Erneut prüfen")
                : label("Check for updates", "Nach Updates suchen");
    }
    void message(String en, String de) {
        status.setText(label(en, de));
    }
    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        german = "de".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff152620);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(32), dp(24), dp(32), dp(24));
        scroll.addView(panel);
        TextView title = new TextView(this);
        title.setText(label("Software updates", "Softwareupdates"));
        title.setTextColor(Color.WHITE); title.setTextSize(30);
        panel.addView(title);
        status = new TextView(this);
        status.setTextSize(17); status.setTextColor(0xffe3ece0);
        status.setPadding(0, dp(14), 0, dp(16)); panel.addView(status);
        Switch automatic = new Switch(this);
        automatic.setText(label("Automatic updates", "Automatische Updates")); automatic.setTextColor(Color.WHITE);
        automatic.setMinHeight(dp(52)); automatic.setChecked(Updates.enabled(this));
        automatic.setOnCheckedChangeListener((v, enabled) -> {
            Updates.prefs(this).edit().putBoolean("automatic", enabled).apply();
            Updates.schedule(this);
        });
        panel.addView(automatic);
        action = button(panel, "", () -> {
            if (release == null) check(); else install(false);
        });
        download = button(panel, label("Download only", "Nur herunterladen"), this::download);
        refresh = button(panel, checkLabel(), this::check);
        if (ReleaseFeed.TV.equals(getPackageName())) {
            button(panel, label("Try the Rust preview", "Rust-Vorschau ausprobieren"),
                    () -> startActivityForResult(new Intent(this, PreviewActivity.class)
                            .putExtra(RustHome.FULL_SETTINGS, getIntent().getBooleanExtra(RustHome.FULL_SETTINGS, false)), PREVIEW));
        }
        button(panel, label("Back", "Zurück"), this::finish);
        if (ReleaseFeed.PHONE.equals(getPackageName())) {
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setBackgroundColor(0xff152620);
            page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            Button home = button(page, label("Home", "Startseite"), () -> { setResult(RESULT_OK); finish(); });
            home.setMinHeight(dp(50));
            LinearLayout.LayoutParams footer = new LinearLayout.LayoutParams(-1, -2);
            footer.setMargins(dp(24), dp(4), dp(24), dp(12));
            home.setLayoutParams(footer);
            setContentView(page);
        } else {
            setContentView(scroll);
        }
        release = Updates.cached(this);
        automaticInstall = state != null && state.getBoolean("automaticInstall");
        boolean fromStartup = getIntent().hasExtra(Updates.STARTUP_CODE);
        if (fromStartup && (release == null || !Updates.enabled(this))) { finish(); return; }
        if (release == null) {
            check();
        } else {
            ready();
            autoStart = fromStartup && state == null && release.versionCode == getIntent().getLongExtra(Updates.STARTUP_CODE, 0);
            if (autoStart) automaticInstall = true;
        }
    }

    private Button button(LinearLayout panel, String text, Runnable press) {
        Button b = new Button(this);
        b.setAllCaps(false); b.setText(text);
        b.setOnClickListener(v -> press.run());
        panel.addView(b);
        return b;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PREVIEW && result == RESULT_OK) finish();
        if (request == INSTALL_PERMISSION && release != null) {
            if (getPackageManager().canRequestPackageInstalls()) autoStart = true;
            else {
                Updates.deferAutomatic(this, release.versionCode);
                message("Allow installation from OpenNava to install this update.", "Erlaube die Installation durch OpenNava, um das Update zu installieren.");
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        active = new WeakReference<>(this);
        if (autoStart) {
            autoStart = false;
            boolean auto = automaticInstall;
            if (auto && !Updates.enabled(this)) return;
            if (getPackageManager().canRequestPackageInstalls()) install(auto);
            else message("An update is available. Select Install update to allow installation from OpenNava.",
                    "Ein Update ist verfügbar. Wähle „Update installieren“, um OpenNava die Installation zu erlauben.");
        } else if (Updates.prefs(this).contains("installResult")) {
            boolean success = Updates.prefs(this).getBoolean("installResult", false);
            Updates.prefs(this).edit().remove("installResult").apply();
            installFinished(success);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("automaticInstall", automaticInstall);
    }

    @Override protected void onPause() {
        if (foreground() == this) active.clear();
        super.onPause();
    }

    private void ready() {
        busy = false;
        action.setEnabled(true); refresh.setEnabled(true);
        boolean staged = release != null && release.downloaded(this);
        download.setVisibility(release != null && !staged && !ReleaseFeed.PHONE.equals(getPackageName()) ? View.VISIBLE : View.GONE);
        download.setEnabled(true);
        if (release == null) {
            action.setText(checkLabel()); refresh.setVisibility(View.GONE);
            message("You are up to date.", "Du verwendest die aktuelle Version.");
        } else {
            action.setText(label("Install update", "Update installieren")); refresh.setVisibility(View.VISIBLE);
            if (staged) message("OpenNava " + release.version + " is downloaded and ready to install offline.",
                    "OpenNava " + release.version + " ist heruntergeladen und kann offline installiert werden.");
            else message("OpenNava " + release.version + " is ready to install.", "OpenNava " + release.version + " ist zur Installation bereit.");
        }
    }

    private void check() {
        if (busy) return;
        busy = true; action.setEnabled(false); refresh.setEnabled(false);
        download.setVisibility(View.GONE);
        refresh.setVisibility(View.GONE); action.setText(label("Checking…", "Prüfe…"));
        message("Checking the latest stable release…", "Suche nach der aktuellen Version…");
        Updates.WORK.execute(() -> {
            try {
                ReleaseFeed latest = Updates.check(this);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    release = latest;
                    ready();
                });
            } catch (Exception failure) { problem(failure); }
        });
    }

    private void download() {
        if (busy || release == null) return;
        ReleaseFeed target = release;
        busy = true; action.setEnabled(false); download.setEnabled(false); refresh.setEnabled(false);
        message("Downloading and verifying the signed update…", "Das signierte Update wird geladen und geprüft…");
        Updates.WORK.execute(() -> {
            try {
                target.prepare(this);
                Updates.prefs(this).edit().remove("startupAttemptCode").remove("startupAttemptAt").apply();
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) ready(); });
            } catch (Exception failure) { problem(failure); }
        });
    }

    private void install(boolean automatic) {
        if (busy || release == null || (automatic && !Updates.enabled(this))) return;
        automaticInstall = automatic;
        if (!getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION);
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                message("Allow OpenNava to install apps in Android settings, or use the paired update tool.",
                        "Erlaube OpenNava die App-Installation in den Android-Einstellungen oder nutze das gekoppelte Update-Werkzeug.");
            }
            return;
        }
        ReleaseFeed target = release;
        busy = true; action.setEnabled(false); refresh.setEnabled(false); download.setEnabled(false);
        if (release.downloaded(this)) message("Verifying the downloaded update…", "Das heruntergeladene Update wird geprüft…");
        else message("Downloading and verifying the signed update…", "Das signierte Update wird geladen und geprüft…");
        Updates.WORK.execute(() -> {
            try {
                Updates.install(this, target, true);
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing() || !busy) return;
                    ready();
                    message("Installation sent to Android.", "Installation an Android übergeben.");
                });
            } catch (Exception failure) { problem(failure); }
        });
    }

    void installFinished(boolean success) {
        if (success) release = Updates.cached(this);
        ready();
        if (success) message("Update installed.", "Update installiert.");
        else message("Installation did not complete. You can try again.", "Die Installation wurde nicht abgeschlossen. Du kannst sie erneut starten.");
    }

    private void problem(Exception failure) {
        runOnUiThread(() -> {
            if (isDestroyed() || isFinishing()) return;
            release = Updates.cached(this);
            ready();
            String detail = failure.getMessage();
            message("Update unavailable. " + (detail == null ? "Try again later." : detail),
                    "Update momentan nicht möglich. " + (detail == null ? "Versuche es später erneut." : detail));
        });
    }
}

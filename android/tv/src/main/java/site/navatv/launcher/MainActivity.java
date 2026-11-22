package site.navatv.launcher;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String START = "https://nava.local/index.html";
    private static final int PLAYER = 10;
    private WebView web;
    private String liveLibrary;
    private String visibleProvider = "", visiblePage = "home", visibleQuery = "";
    private MediathekService service;
    private volatile boolean ready;
    private boolean pendingHome, pendingResume, playerOpen;
    private JSONObject pendingCompanion;
    private boolean resumed, waitingForIntroFrame;
    private volatile boolean helpOpen, guidePractice, homeShortcuts = true;
    private final DirectionShortcut upShortcut = new DirectionShortcut();
    private boolean helpEnterDown, helpEnterHeld;
    private final Runnable helpHold = () -> {
        if (resumed && helpOpen && helpEnterDown && !helpEnterHeld) {
            helpEnterHeld = true;
            sendKey("EnterHold");
        }
    };

    private StartupSignals startup;
    private final IdleTimer idle = new IdleTimer(this, () -> ready && !playerOpen && !waitingForIntroFrame);

    String initialLibrary() { return null; }

    String companionProvider() { return visibleProvider; }
    String companionPage() { return helpOpen ? "help" : visiblePage; }
    String companionQuery() { return visibleQuery; }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        site.navatv.updates.Updates.home(this);
        startup = (StartupSignals)getApplication();
        liveLibrary = initialLibrary();
        if (liveLibrary == null) {
            if (routeEntry(getIntent(), state == null)) return;
            startup.attach(this);
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        service = new MediathekService(getApplicationContext(), this::deliverResult);
        web = new WebView(this);
        web.setBackgroundColor(0xff10211b);
        if (liveLibrary != null || startup.hasPending()) web.setAlpha(0f);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setTextZoom(100);
        web.setWebViewClient(new LocalClient());
        web.addJavascriptInterface(new Bridge(), "NavaTV");
        web.addJavascriptInterface(new WeatherBridge(getApplicationContext()), "SeniorWeather");
        setContentView(web);
        web.loadUrl(START);
    }

    private final class LocalClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return LocalPage.response(MainActivity.this, request, "index.html", service);
        }
        @Override public void onPageFinished(WebView view, String url) {
            ready = START.equals(url);
            if (!ready) return;
            if (liveLibrary != null) {
                view.evaluateJavascript("window.navaOpenLiveLibrary(" + JSONObject.quote(liveLibrary) + ")",
                        result -> view.setAlpha(1f));
            } else {
                dispatchNavigation();
                dispatchStartup();
            }
            idle.refresh();
        }
    }

    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,128}"); }
    private boolean canCallPage() { return ready && !isFinishing() && !isDestroyed() && web != null && START.equals(web.getUrl()); }
    private void deliverResult(String id, String json) {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,96}")) return;
        runOnUiThread(() -> {
            if (canCallPage()) web.evaluateJavascript("window.navaMediathekResult?.(" + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")", null);
        });
    }
    private final class Bridge {
        @android.webkit.JavascriptInterface public void openUpdates() {
            runOnUiThread(() -> site.navatv.updates.Updates.open(MainActivity.this));
        }
        @JavascriptInterface public void reportCatalogue(String provider, String page, String query) {
            if (!("".equals(provider) || "ard".equals(provider) || "zdf".equals(provider) || "arte".equals(provider))) return;
            runOnUiThread(() -> {
                visibleProvider = provider;
                visiblePage = "search".equals(page) ? "search" : "home";
                visibleQuery = "search".equals(visiblePage) && query != null ? query.substring(0, Math.min(220, query.length())) : "";
            });
        }
        @JavascriptInterface public void setHelpOpen(boolean open) {
            helpOpen = open;
            if (!open) guidePractice = false;
        }
        @JavascriptInterface public void setGuidePractice(boolean practice) { guidePractice = practice; }
        @JavascriptInterface public void setHomeShortcuts(boolean enabled) { homeShortcuts = enabled; }
        @JavascriptInterface public boolean openTelevision(String section) {
            final Class<? extends Activity> activity;
            if ("live-tv".equals(section)) activity = LiveTvActivity.class;
            else if ("recordings".equals(section)) activity = RecordingsActivity.class;
            else if ("programme".equals(section)) activity = TvProgrammeActivity.class;
            else return false;
            if (!ready) return false;
            runOnUiThread(() -> {
                if (!canCallPage() || !resumed || playerOpen) return;
                playerOpen = true;
                idle.refresh();
                startup.clear();
                startActivityForResult(new Intent(MainActivity.this, activity), PLAYER);
            });
            return true;
        }

        @JavascriptInterface public boolean openOfficialMediathek(String provider) {
            if (!ready || provider == null) return false;
            final String packageName;
            switch (provider) {
                case "ard": packageName = "de.swr.avp.ard.tv"; break;
                case "zdf": packageName = "com.zdf.android.mediathek"; break;
                case "arte": packageName = "tv.arte.plus7"; break;
                default: return false;
            }
            runOnUiThread(() -> {
                if (!canCallPage() || !resumed || playerOpen) return;
                openStationApp(packageName);
            });
            return true;
        }

        @JavascriptInterface public boolean openVisitorMode() {
            if (!ready || !VisitorMode.available(MainActivity.this)) return false;
            runOnUiThread(() -> {
                if (!canCallPage() || playerOpen) return;
                if (VisitorMode.enter(MainActivity.this)) { startup.clear(); finish(); }
                else Toast.makeText(MainActivity.this, "Full launcher is currently unavailable.", Toast.LENGTH_SHORT).show();
            });
            return true;
        }

        @JavascriptInterface public void mediathekRequest(String id, String json) {
            if (ready) service.request(id, json);
        }
        @JavascriptInterface public boolean playMediathek(String id) {
            if (!ready || !validId(id) || !MediathekService.contains(id)) return false;
            runOnUiThread(() -> {
                if (!canCallPage() || playerOpen) return;
                playerOpen = true;
                idle.refresh();
                startup.clear();
                startActivityForResult(new Intent(MainActivity.this, PlayerActivity.class).putExtra("id", id), PLAYER);
            });
            return true;
        }
        @JavascriptInterface public void exitApp() {
            runOnUiThread(() -> {
                if (!canCallPage()) return;
                if (liveLibrary == null && isDefaultHome()) web.evaluateJavascript("window.navaNativeHome?.()", null);
                else finish();
            });
        }
    }
    private void openStationApp(String packageName) {
        Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
        if (app != null) {
            try {
                startActivity(app);
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // The package may have been removed or disabled since resolution.
            }
        }
        Intent store = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName))
                .setPackage("com.android.vending");
        try {
            startActivity(store);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, "Could not open the broadcaster’s app or its Play Store listing.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean isDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager manager = getSystemService(RoleManager.class);
            if (manager != null && manager.isRoleAvailable(RoleManager.ROLE_HOME) && manager.isRoleHeld(RoleManager.ROLE_HOME)) return true;
        }
        ResolveInfo chosen = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return chosen != null && chosen.activityInfo != null && getPackageName().equals(chosen.activityInfo.packageName);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        idle.interaction();
        setIntent(intent);
        if (!routeEntry(intent, true)) dispatchNavigation();
    }

    private boolean routeEntry(Intent intent, boolean freshEntry) {
        if (intent == null) return false;
        if ("site.navatv.launcher.SHOW_SELECTION".equals(intent.getAction())) {
            if (intent.getComponent() == null || !(getPackageName() + ".ShowSelection").equals(intent.getComponent().getClassName()))
                return false;
            if (!freshEntry) return false;
            String provider = intent.getStringExtra("provider");
            if (!("ard".equals(provider) || "zdf".equals(provider) || "arte".equals(provider))) return false;
            String operation = intent.getStringExtra("operation");
            String value = intent.getStringExtra("value");
            if (!("open".equals(operation) || "live".equals(operation) || "search".equals(operation))) return false;
            if (value != null && value.length() > 300) return false;
            try {
                pendingCompanion = new JSONObject().put("provider", provider).put("operation", operation)
                        .put("value", value == null ? "" : value);
            } catch (org.json.JSONException invalid) { return false; }
            pendingHome = pendingResume = playerOpen = false;
            startup.clear();
            return false;
        }
        if ("site.opennava.action.OPEN_CLASSIC".equals(intent.getAction())) {
            if (VisitorMode.launchIfSelected(this, false)) { finish(); return true; }
            pendingHome = true;
            return false;
        }
        boolean main = Intent.ACTION_MAIN.equals(intent.getAction());
        boolean home = main && intent.hasCategory(Intent.CATEGORY_HOME);
        boolean appList = main && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
                && (intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                    || (intent.getComponent() != null && intent.getCategories() == null));
        if (home || (appList && VisitorMode.integrated(this))) {
            if (VisitorMode.launchIfSelected(this)) {
                finish();
                return true;
            }
        } else if (freshEntry && (VisitorMode.isReturn(intent) || appList)) {
            if (VisitorMode.isReturn(intent) || VisitorMode.isVisitor(this)) startup.clear();
            if (!VisitorMode.selectPersonal(this))
                Toast.makeText(this, "Could not save your preferred home screen.", Toast.LENGTH_SHORT).show();
        } else return false;
        if (home && freshEntry && HomeProgrammeShortcut.homeIntent(this, intent.getBooleanExtra("from_fire_home_redirect", false))) {
            pendingHome = false;
            return false;
        }
        pendingCompanion = null;
        pendingHome = true;
        pendingResume = false;
        playerOpen = false;
        return false;
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PLAYER) {
            playerOpen = false;
            if (result == RESULT_OK) pendingResume = true;
        }
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        dispatchNavigation();
        dispatchStartup();
        idle.resume();
    }
    @Override protected void onPause() {
        resumed = false;
        upShortcut.cancel();
        HomeProgrammeShortcut.leftLauncher();
        if (web != null) web.removeCallbacks(helpHold);
        helpEnterDown = helpEnterHeld = false;
        idle.pause();
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) upShortcut.cancel();
        if (focused) dispatchStartup();
        idle.refresh();
    }

    /** Called only when Android reports a cold entry or a real transition out of standby. */
    void dispatchStartup() {
        if (liveLibrary != null || !resumed || !hasWindowFocus() || playerOpen || !canCallPage() || waitingForIntroFrame) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) return;
        if (!startup.take()) {
            if (!startup.hasPending()) web.setAlpha(1f);
            return;
        }
        final WebView page = web;
        waitingForIntroFrame = true;
        // Alpha 0 keeps the view attached and measurable while its first intro frame renders.
        page.setAlpha(0f);
        final Runnable reveal = () -> {
            if (web != page || isDestroyed()) return;
            waitingForIntroFrame = false;
            page.setAlpha(1f);
            idle.refresh();
            if (startup.hasPending()) dispatchStartup();
        };
        // A renderer failure must not leave the otherwise usable home page invisible.
        page.postDelayed(reveal, 350);
        page.evaluateJavascript("typeof window.seniorStartup==='function' && (window.seniorStartup(),true)", result -> {
            if (web != page || isDestroyed()) return;
            if (!"true".equals(result)) { page.removeCallbacks(reveal); reveal.run(); return; }
            page.postVisualStateCallback(0, new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) {
                    page.removeCallbacks(reveal);
                    reveal.run();
                }
            });
        });
        android.util.Log.i("Libraries", "Requested personal home screen");
    }
    private void dispatchNavigation() {
        if (!canCallPage()) return;
        if (pendingCompanion != null) {
            web.evaluateJavascript("window.navaCompanion?.(" + pendingCompanion + ")", null);
            pendingCompanion = null;
        } else if (pendingHome) web.evaluateJavascript("window.navaNativeHome?.()", null);
        else if (pendingResume) web.evaluateJavascript("window.navaNativeResume?.()", null);
        pendingHome = pendingResume = false;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) idle.interaction();
        String key = remote(event.getKeyCode());
        if (key == null || !ready) return super.dispatchKeyEvent(event);
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                && (upShortcut.isTracking() || (helpOpen ? guidePractice : homeShortcuts))) {
            long pressedAt = event.getDownTime();
            return upShortcut.handle(event, () -> sendKey("ArrowUp"), () -> {
                if (helpOpen) {
                    if (guidePractice) sendKey("ArrowUpHold");
                } else if (homeShortcuts && resumed) {
                    startActivity(TvProgrammeActivity.intent(this, null).putExtra("openingUpDownTime", pressedAt));
                }
            });
        }
        if ("Enter".equals(key) && (helpOpen || helpEnterDown)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                helpEnterDown = true;
                helpEnterHeld = false;
                web.postDelayed(helpHold, android.view.ViewConfiguration.getLongPressTimeout());
            } else if (event.getAction() == KeyEvent.ACTION_UP && helpEnterDown) {
                web.removeCallbacks(helpHold);
                if (!event.isCanceled() && !helpEnterHeld) {
                    boolean held = event.getEventTime() - event.getDownTime() >= android.view.ViewConfiguration.getLongPressTimeout();
                    sendKey(held ? "EnterHold" : "Enter");
                }
                helpEnterDown = helpEnterHeld = false;
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && (event.getRepeatCount() == 0 || key.startsWith("Arrow") || key.equals("Backspace"))) sendKey(key);
        return true;
    }
    boolean practisingHomeKey() { return resumed && helpOpen && guidePractice && canCallPage(); }
    void completeHomePractice() { sendKey("HomeDouble"); }
    void leaveHomePractice() {
        if (practisingHomeKey()) web.evaluateJavascript("window.navaNativeHome?.()", null);
    }
    private void sendKey(String key) { if (canCallPage()) web.evaluateJavascript("window.navaRemote?.(" + JSONObject.quote(key) + ")", null); }
    @Override public void onUserInteraction() { super.onUserInteraction(); idle.interaction(); }
    @Override public void onBackPressed() { idle.interaction(); if (ready) sendKey("Escape"); else super.onBackPressed(); }
    private static String remote(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return "ArrowUp";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "ArrowDown";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "ArrowLeft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "ArrowRight";
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: return "Enter";
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_ESCAPE: return "Escape";
            case KeyEvent.KEYCODE_DEL: return "Backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "Delete";
            case KeyEvent.KEYCODE_MENU: return "Menu";
            default: return null;
        }
    }
    @Override protected void onDestroy() {
        ready = false;
        if (startup != null) startup.detach(this);
        if (service != null) service.close();
        if (web != null) { web.removeJavascriptInterface("NavaTV"); web.removeJavascriptInterface("SeniorWeather"); web.destroy(); web = null; }
        super.onDestroy();
    }
}

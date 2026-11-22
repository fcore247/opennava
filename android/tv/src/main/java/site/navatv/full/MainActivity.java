package site.navatv.full;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final String ORIGIN = "https://nava.local/";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    static final String EXTRA_FROM_HOME = "nava_from_home";
    private boolean firePlatform() { return "Amazon".equalsIgnoreCase(Build.MANUFACTURER); }
    private boolean combined() { return "site.navatv.launcher".equals(getPackageName()); }
    private boolean homeNavigation(Intent intent) {
        return intent != null && (intent.getBooleanExtra(EXTRA_FROM_HOME, false) || "site.navatv.full.VISIT_HOME".equals(intent.getAction()));
    }
    private WebView webView;
    private boolean pageReady;
    private boolean openingYouTube;
    private boolean pendingHomeNavigation, pendingPreviewHint;
    private boolean normalFullHome(Intent intent) {
        return combined() && intent != null && intent.getData() == null &&
                (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0 &&
                (intent.getBooleanExtra("opennava.normalHome", false) || intent.getBooleanExtra(EXTRA_FROM_HOME, false));
    }
    private volatile boolean editingText;
    private NavaYouTube youtube;
    private MediathekService mediathek;
    private final ThumbnailProxy thumbnails = new ThumbnailProxy();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        site.navatv.updates.Updates.home(this);
        pendingHomeNavigation = homeNavigation(getIntent());
        pendingPreviewHint = savedState == null && normalFullHome(getIntent());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        immersive();
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(16, 19, 18));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // Remote keys enter through evaluateJavascript rather than a WebView touch gesture.
        // Only bundled, trusted media can load, so remote-triggered playback must be allowed.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(false);
        webView.addJavascriptInterface(new NativeBridge(), "NavaTV");
        youtube = new NavaYouTube(this::deliverYouTubeResult);
        mediathek = new MediathekService(this::deliverMediathekResult);
        webView.setWebViewClient(new LocalClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                String text = message.message();
                if (text.length() > 1200) text = text.substring(0, 1200);
                Log.i("OpenNava", "JS " + message.messageLevel() + " " + text);
                return true;
            }
        });
        setContentView(webView);
        webView.requestFocus();
        webView.loadUrl(ORIGIN + "index.html");
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override protected void onResume() {
        super.onResume();
        openingYouTube = false;
        LauncherState.clearBypass(this);
        immersive();
        if (webView != null) { webView.onResume(); notifyResume(); }
    }

    @Override protected void onPause() {
        pendingPreviewHint = false;
        if (webView != null) {
            if (pageReady) webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();});", null);
            webView.onPause();
        }
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingPreviewHint = normalFullHome(intent);
        if (homeNavigation(intent)) pendingHomeNavigation = true;
        LauncherState.clearBypass(this);
        notifyResume();
        notifyHome();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { immersive(); maybeShowPreviewHint(); }
    }

    private void maybeShowPreviewHint() {
        if (!pendingPreviewHint || !pageReady || !hasWindowFocus() || isFinishing() || isDestroyed()) return;
        pendingPreviewHint = false;
        if (getSharedPreferences("software-updates", MODE_PRIVATE).getBoolean("rustPreviewHintHandled", false)) return;
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            // A fresh installation does not need this migration hint, including on subsequent launches.
            getSharedPreferences("software-updates", MODE_PRIVATE).edit().putBoolean("rustPreviewHintHandled", true).apply();
            if (info.firstInstallTime <= 0 || info.lastUpdateTime <= info.firstInstallTime) return;
            boolean german = "de".equals(getResources().getConfiguration().getLocales().get(0).getLanguage());
            Toast.makeText(this, german
                    ? "Neue Rust-Oberfläche ausprobieren? Unter „Software updates“ findest du die Vorschau."
                    : "Try the new Rust interface under Software updates.", Toast.LENGTH_LONG).show();
        } catch (PackageManager.NameNotFoundException ignored) { /* The activity is exiting. */ }
    }

    private void notifyResume() {
        if (pageReady && webView != null) webView.evaluateJavascript(
                "if(typeof window.navaNativeResume==='function'){window.navaNativeResume();}", null);
    }

    private void notifyHome() {
        if (!pageReady || webView == null || !pendingHomeNavigation) return;
        pendingHomeNavigation = false;
        if (getIntent() != null) getIntent().removeExtra(EXTRA_FROM_HOME);
        webView.evaluateJavascript(
                "if(typeof window.navaNativeHome==='function'){window.navaNativeHome();}", result -> maybeShowPreviewHint());
    }

    private void deliverYouTubeResult(String requestId, String json) {
        deliverMediaResult("navaYouTubeResult", requestId, json);
    }

    private void deliverMediathekResult(String requestId, String json) {
        deliverMediaResult("navaMediathekResult", requestId, json);
    }

    private void deliverMediaResult(String callback, String requestId, String json) {
        if (!NavaYouTube.validRequestId(requestId)) return;
        runOnUiThread(() -> {
            if (!pageReady || webView == null || isDestroyed() || webView.getUrl() == null
                    || !trusted(Uri.parse(webView.getUrl()))) return;
            webView.evaluateJavascript("if(typeof window." + callback + "==='function'){window." + callback + "("
                    + JSONObject.quote(requestId) + "," + JSONObject.quote(json) + ");}", null);
        });
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        String key = remoteKey(event.getKeyCode());
        // Let WebView/IME handle cursor movement, submit and keyboard dismissal in text fields.
        if (editingText && key != null && (key.startsWith("Arrow")
                || "Enter".equals(key) || "Escape".equals(key)
                || "Backspace".equals(key) || "Delete".equals(key)))
            return super.dispatchKeyEvent(event);
        if (key != null && webView != null && pageReady) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (event.getRepeatCount() == 0 || key.startsWith("Arrow")
                    || "Backspace".equals(key) || "Delete".equals(key))) {
                webView.evaluateJavascript("if(typeof window.navaRemote==='function'){window.navaRemote("
                        + JSONObject.quote(key) + ");}", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static String remoteKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return "ArrowUp";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "ArrowDown";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "ArrowLeft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "ArrowRight";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "Enter";
            case KeyEvent.KEYCODE_BACK: return "Escape";
            case KeyEvent.KEYCODE_DEL: return "Backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "Delete";
            case KeyEvent.KEYCODE_MENU: return "Menu";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "MediaPlayPause";
            case KeyEvent.KEYCODE_MEDIA_PLAY: return "MediaPlay";
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return "MediaPause";
            default: return null;
        }
    }

    @Override public void onBackPressed() {
        if (pageReady) webView.evaluateJavascript(
                "if(typeof window.navaRemote==='function'){window.navaRemote('Escape');}", null);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (youtube != null) youtube.close();
        if (mediathek != null) mediathek.close();
        if (webView != null) { webView.removeJavascriptInterface("NavaTV"); webView.destroy(); webView = null; }
        super.onDestroy();
    }

    private static boolean trusted(Uri uri) {
        return uri != null && "https".equals(uri.getScheme()) && "nava.local".equals(uri.getHost())
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private final class LocalClient extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (trusted(request.getUrl())) return false;
            if (request.isForMainFrame() && request.hasGesture()) openExternal(request.getUrl());
            return true;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame() && "GET".equals(request.getMethod()) && ThumbnailProxy.allowed(request.getUrl()))
                return thumbnails.fetch(request.getUrl());
            if (!trusted(request.getUrl())) return emptyResponse(403, "Blocked");
            String path = request.getUrl().getPath();
            if (path != null && path.startsWith("/mediathek/image/")) {
                String id = path.substring("/mediathek/image/".length());
                if (mediathek == null || !"GET".equals(request.getMethod()) || !id.matches("[A-Za-z0-9_-]{1,128}"))
                    return emptyResponse(404, "Image unavailable");
                return mediathek.image(id);
            }
            return assetResponse(request);
        }

        @Override public void onPageFinished(WebView view, String url) {
            pageReady = trusted(Uri.parse(url));
            if (pageReady) {
                view.evaluateJavascript("(function(){if(window.__navaEditingInstalled)return;"
                        + "window.__navaEditingInstalled=true;function sync(){var e=document.activeElement;"
                        + "NavaTV.setTextEditing(!!(e&&(e.isContentEditable||e.matches("
                        + "'textarea,input[type=text],input[type=search],input[type=email],input[type=url],input[type=password],input[type=number],input[type=tel]'))));}"
                        + "document.addEventListener('focusin',sync);document.addEventListener('focusout',function(){setTimeout(sync,0)});sync();})();", null);
                notifyResume();
                notifyHome();
            }
        }
    }

    private void openExternal(Uri uri) {
        if (uri == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException failure) { message("No compatible app is installed for this link."); }
    }

    private void message(String text) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_SHORT).show());
    }

    private WebResourceResponse assetResponse(WebResourceRequest request) {
        String path = request.getUrl().getPath();
        if (path == null || path.equals("/")) path = "/index.html";
        if (!path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0)
            return emptyResponse(400, "Invalid path");
        for (String segment : path.split("/")) if (segment.equals("..")) return emptyResponse(403, "Blocked");
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod()))
            return emptyResponse(405, "Method not allowed");
        boolean googlePage = combined() && !firePlatform() && (path.equals("/index.html") || path.equals("/styles.css")
                || path.equals("/native.js") || path.equals("/tube.js"));
        String asset = (googlePage ? "google" : "web") + path;
        InputStream stream = null;
        try {
            long length;
            try {
                AssetFileDescriptor descriptor = getAssets().openFd(asset);
                length = descriptor.getLength();
                stream = descriptor.createInputStream();
            } catch (IOException compressed) {
                try (InputStream input = getAssets().open(asset); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                    byte[] content = bytes.toByteArray();
                    length = content.length;
                    stream = new ByteArrayInputStream(content);
                }
            }
            long start = 0, end = length - 1;
            String range = null;
            for (Map.Entry<String, String> header : request.getRequestHeaders().entrySet())
                if ("Range".equalsIgnoreCase(header.getKey())) range = header.getValue();
            if (range != null) {
                if (!range.matches("bytes=\\d*-\\d*")) { stream.close(); return emptyResponse(400, "Invalid range"); }
                String[] parts = range.substring(6).split("-", -1);
                if (parts[0].isEmpty() && parts[1].isEmpty()) { stream.close(); return emptyResponse(400, "Invalid range"); }
                if (parts[0].isEmpty()) start = Math.max(0, length - Long.parseLong(parts[1]));
                else {
                    start = Long.parseLong(parts[0]);
                    if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
                }
                if (start >= length || end < start) {
                    stream.close();
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Range", "bytes */" + length);
                    return new WebResourceResponse("text/plain", "UTF-8", 416, "Range not satisfiable", headers, new ByteArrayInputStream(new byte[0]));
                }
            }
            long skipped = 0;
            while (skipped < start) {
                long step = stream.skip(start - skipped);
                if (step == 0) { if (stream.read() < 0) throw new IOException("Early end of asset"); step = 1; }
                skipped += step;
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Length", Long.toString(end - start + 1));
            headers.put("Accept-Ranges", "bytes");
            headers.put("Cache-Control", "no-store");
            headers.put("X-Content-Type-Options", "nosniff");
            headers.put("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https://i.ytimg.com https://yt3.googleusercontent.com https://yt3.ggpht.com; media-src 'self'; font-src 'self'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'");
            if (range != null) headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
            String mime = mime(path);
            if ("HEAD".equals(request.getMethod())) { stream.close(); stream = new ByteArrayInputStream(new byte[0]); }
            return new WebResourceResponse(mime, mime.startsWith("text/") || mime.contains("javascript") ? "UTF-8" : null,
                    range == null ? 200 : 206, range == null ? "OK" : "Partial Content", headers,
                    new LimitedInputStream(stream, end - start + 1));
        } catch (IOException | NumberFormatException failure) {
            if (stream != null) try { stream.close(); } catch (IOException ignored) { }
            return emptyResponse(404, "Not found");
        }
    }

    private static WebResourceResponse emptyResponse(int status, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", status, reason,
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long count) { super(input); remaining = count; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = in.read();
            if (value >= 0) remaining--;
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (remaining <= 0) return -1;
            int read = in.read(buffer, offset, (int) Math.min(count, remaining));
            if (read > 0) remaining -= read;
            return read;
        }
        @Override public long skip(long count) throws IOException { long skipped = in.skip(Math.min(count, remaining)); remaining -= skipped; return skipped; }
        @Override public int available() throws IOException { return (int) Math.min(in.available(), remaining); }
    }

    private LinkedHashMap<String, ResolveInfo> launchableApps() {
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, ResolveInfo> result = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled) continue;
                String name = info.activityInfo.packageName;
                if (name.equals(getPackageName()) || name.equals("site.navatv.full")
                        || name.equals("com.google.android.apps.tv.launcherx") || name.equals("com.amazon.tv.launcher") || name.equals("com.amazon.tv.settings.v2")) continue;
                boolean normalUserApp = (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
                boolean streamingApp = name.equals("com.amazon.firebat") || name.equals("com.amazon.firetv.youtube")
                        || name.equals("com.netflix.ninja") || name.equals("com.disney.disneyplus")
                        || name.equals("de.swr.ard.avp.mobile.android.amazon") || name.equals("com.viki.kindle");
                if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category) || normalUserApp || streamingApp)
                    if (!result.containsKey(name)) result.put(name, info);
            }
        }
        return result;
    }

    private String iconData(Drawable drawable) {
        if (drawable == null) return null;
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 96, 96);
        drawable.draw(new Canvas(bitmap));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        bitmap.recycle();
        return "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }

    private void startSettings(String kind) {
        if (!firePlatform()) {
            String action;
            switch (kind) {
                case "network": action = Settings.ACTION_WIFI_SETTINGS; break;
                case "bluetooth": action = Settings.ACTION_BLUETOOTH_SETTINGS; break;
                case "display": action = Settings.ACTION_DISPLAY_SETTINGS; break;
                default: action = Settings.ACTION_SETTINGS;
            }
            try { startActivity(new Intent(action)); }
            catch (ActivityNotFoundException | SecurityException unavailable) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                catch (ActivityNotFoundException | SecurityException failed) { message("Device settings are unavailable."); }
            }
            return;
        }
        Intent intent;
        switch (kind) {
            case "network":
                intent = new Intent(Settings.ACTION_WIFI_SETTINGS).setComponent(new ComponentName(
                        "com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.network.NetworkActivity"));
                break;
            case "bluetooth":
                intent = new Intent("com.amazon.device.settings.action.CONTROLLERS").setComponent(new ComponentName(
                        "com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"));
                break;
            case "display":
                intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS).setComponent(new ComponentName(
                        "com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.display_sounds.DisplayAndSoundsActivity"));
                break;
            case "general": intent = generalSettingsIntent(); break;
            default: return;
        }
        LauncherState.beginSettingsBypass(this);
        Intent general = generalSettingsIntent();
        try {
            if (!canLaunchSettings(intent)) {
                Log.i("OpenNava", "Settings route unavailable or permission-protected: " + kind + "; opening general settings");
                intent = general;
            }
            startActivity(intent);
        } catch (RuntimeException failure) {
            Log.w("OpenNava", "Settings route failed: " + kind, failure);
            // A component may be restricted after resolution. Always retain the general fallback.
            if (!general.getComponent().equals(intent.getComponent())) {
                try { startActivity(general); return; }
                catch (RuntimeException fallbackFailure) {
                    Log.w("OpenNava", "General settings fallback failed", fallbackFailure);
                }
            }
            LauncherState.clearBypass(this);
            message("This system setting is currently unavailable.");
        }
    }

    private Intent generalSettingsIntent() {
        return new Intent("amazon.intent.action.MAINSETTINGS").setComponent(new ComponentName(
                "com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"));
    }

    private boolean canLaunchSettings(Intent intent) {
        ResolveInfo resolved = getPackageManager().resolveActivity(intent, 0);
        if (resolved == null || resolved.activityInfo == null || !resolved.activityInfo.enabled
                || !resolved.activityInfo.applicationInfo.enabled || !resolved.activityInfo.exported) return false;
        String permission = resolved.activityInfo.permission;
        return permission == null || permission.isEmpty()
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void startAmazonHome() {
        LauncherState.beginBypass(this);
        try {
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .setComponent(new ComponentName("com.amazon.tv.launcher", AMAZON_HOME))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException failure) {
            LauncherState.clearBypass(this);
            message("Could not open the Amazon home screen.");
        }
    }

    private final class NativeBridge {
        @android.webkit.JavascriptInterface public void openUpdates() {
            runOnUiThread(() -> site.navatv.updates.Updates.open(MainActivity.this));
        }
        @JavascriptInterface public void mediathekRequest(String requestId, String payloadJson) {
            if (mediathek != null && NavaYouTube.validRequestId(requestId)) mediathek.request(requestId, payloadJson);
        }

        @JavascriptInterface public boolean playMediathek(String opaqueId) {
            if (mediathek == null || opaqueId == null || !opaqueId.matches("[A-Za-z0-9_-]{1,128}")) return false;
            if (!MediathekService.contains(opaqueId)) return false;
            runOnUiThread(() -> {
                if (!pageReady || webView == null || webView.getUrl() == null || !trusted(Uri.parse(webView.getUrl()))) return;
                startActivity(NavaPlayerActivity.mediathekIntent(MainActivity.this, opaqueId));
            });
            return true;
        }

        @JavascriptInterface public void youtubeRequest(String requestId, String payloadJson) {
            if (youtube != null) youtube.request(requestId, payloadJson);
        }

        @JavascriptInterface public boolean playYouTube(String videoId) {
            return playYouTubeCard(videoId, "", "");
        }

        @JavascriptInterface public boolean playYouTubeCard(String videoId, String title, String author) {
            if (!NavaYouTube.validVideoId(videoId)) return false;
            runOnUiThread(() -> {
                if (openingYouTube || !hasWindowFocus() || !pageReady || webView == null
                        || webView.getUrl() == null || !trusted(Uri.parse(webView.getUrl()))) return;
                openingYouTube = true;
                YouTubePlayback.prepare(videoId);
                Intent movie = NavaPlayerActivity.intent(MainActivity.this, videoId);
                if (title != null) movie.putExtra(NavaPlayerActivity.EXTRA_TITLE, title.substring(0, Math.min(title.length(), 400)));
                if (author != null) movie.putExtra(NavaPlayerActivity.EXTRA_AUTHOR, author.substring(0, Math.min(author.length(), 140)));
                startActivity(movie);
            });
            return true;
        }

        @JavascriptInterface public void setTextEditing(boolean editing) {
            if (editingText == editing) return;
            editingText = editing;
            if (editing) runOnUiThread(() -> {
                if (webView == null || !editingText) return;
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
            });
        }

        @JavascriptInterface public String getApps() {
            JSONArray apps = new JSONArray();
            PackageManager pm = getPackageManager();
            List<ResolveInfo> list = new ArrayList<>(launchableApps().values());
            list.sort(Comparator.comparing(info -> info.loadLabel(pm).toString().toLowerCase(Locale.ROOT)));
            for (ResolveInfo info : list) {
                try {
                    JSONObject app = new JSONObject();
                    app.put("packageName", info.activityInfo.packageName);
                    app.put("name", info.loadLabel(pm).toString());
                    try { app.put("icon", iconData(info.loadIcon(pm))); } catch (RuntimeException ignored) { }
                    apps.put(app);
                } catch (Exception ignored) { }
            }
            return apps.toString();
        }

        @JavascriptInterface public boolean launchApp(String packageName) {
            if ("com.amazon.tv.launcher".equals(packageName)) { runOnUiThread(() -> startAmazonHome()); return true; }
            if (packageName == null || !launchableApps().containsKey(packageName)) return false;
            Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(packageName);
            if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) return false;
            final Intent launch = intent;
            runOnUiThread(() -> {
                try { startActivity(launch); }
                catch (RuntimeException failure) { message("Could not launch the app."); }
            });
            return true;
        }

        @JavascriptInterface public String getDeviceInfo() {
            JSONObject info = new JSONObject();
            try { info.put("model", Build.MODEL).put("platform", firePlatform() ? "firetv" : "googletv")
                    .put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName)
                    .put("androidVersion", Build.VERSION.RELEASE).put("apiLevel", Build.VERSION.SDK_INT); }
            catch (Exception ignored) { }
            return info.toString();
        }

        @JavascriptInterface public boolean openSettings(String kind) {
            if (!("general".equals(kind) || "network".equals(kind) || "bluetooth".equals(kind) || "display".equals(kind))) return false;
            runOnUiThread(() -> startSettings(kind));
            return true;
        }

        @JavascriptInterface public boolean hasPersonalView() { return combined(); }

        @JavascriptInterface public void showPhoneLink() {
            runOnUiThread(() -> {
                if (!combined() || !pageReady || webView == null || webView.getUrl() == null
                        || !trusted(Uri.parse(webView.getUrl()))) return;
                String pkg = getPackageName();
                startActivity(new Intent().setClassName(pkg, pkg + ".PhoneLinkActivity"));
            });
        }

        @JavascriptInterface public void returnToSenior() {
            runOnUiThread(() -> {
                if (!combined() || !pageReady || webView == null || webView.getUrl() == null || !trusted(Uri.parse(webView.getUrl()))) return;
                String pkg = getPackageName();
                startActivity(new Intent(pkg + ".RETURN_TO_SENIOR").setClassName(pkg,pkg + ".ReturnToSenior")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            });
        }
        @JavascriptInterface public void openAmazonHome() { runOnUiThread(() -> startAmazonHome()); }
        @JavascriptInterface public boolean isLauncherEnabled() { return LauncherState.isEnabled(MainActivity.this); }
        @JavascriptInterface public boolean setLauncherEnabled(boolean enabled) { return LauncherState.setEnabled(MainActivity.this, enabled); }
    }
}

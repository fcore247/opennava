package site.navatv.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Personal, local-only ambient screen. Navigation and playback state stay in the activity below. */
public final class AmbientActivity extends Activity {
    private WebView page;
    private int dismissKey = KeyEvent.KEYCODE_UNKNOWN;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) finish();
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
        page = new WebView(this);
        page.setBackgroundColor(0xff10161a);
        WebSettings settings = page.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setTextZoom(100);
        page.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return LocalPage.response(AmbientActivity.this, request, "ambient.html", null);
            }
        });
        page.addJavascriptInterface(new WeatherBridge(getApplicationContext()), "SeniorWeather");
        setContentView(page);
        page.loadUrl("https://nava.local/ambient.html");
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_POWER || key == KeyEvent.KEYCODE_SLEEP || key == KeyEvent.KEYCODE_WAKEUP
                || key == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) dismissKey = key;
        if (event.getAction() == KeyEvent.ACTION_UP && (key == dismissKey || dismissKey == KeyEvent.KEYCODE_UNKNOWN)) finish();
        return true;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) finish();
        return true;
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) { finish(); return true; }
        return super.dispatchGenericMotionEvent(event);
    }
    @Override public void onBackPressed() { finish(); }
    @Override protected void onPause() { if (page != null) page.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (page != null) page.onResume(); }
    @Override protected void onDestroy() {
        unregisterReceiver(screenOff);
        if (page != null) { page.removeJavascriptInterface("SeniorWeather"); page.destroy(); page = null; }
        super.onDestroy();
    }
}

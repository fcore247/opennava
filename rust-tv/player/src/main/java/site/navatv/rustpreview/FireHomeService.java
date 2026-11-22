package site.navatv.rustpreview;

import android.accessibilityservice.AccessibilityService;
import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.atomic.AtomicBoolean;

public final class FireHomeService extends AccessibilityService {
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final long DOUBLE_PRESS_MS = 550;
    private static final long HOME_SETTLE_MS = 200;
    private static final AtomicBoolean pendingHome = new AtomicBoolean();

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean redirectScheduled;
    private FireHomeSignals signals;
    private long firstPhysical;
    private final Runnable singlePhysicalHome = () -> {
        if (this.amazonEntered == 0 && isRedirectEnabled(this)) openRust();
    };
    private final Runnable settledHome = () -> {
        redirectScheduled = false;
        if (this.amazonEntered == 0 && isRedirectEnabled(this)) openRust();
    };

    private long firstKeyUp, lastRawKey, firstWindow, lastRedirect, amazonEntered;
    private int lastWindow = -1;
    private boolean secondDown, returnDown, sawRustWindow;

    public static boolean isRedirectEnabled(Context context) {
        return context.getSharedPreferences("fire-home", MODE_PRIVATE).getBoolean("redirect", true);
    }

    public static void setRedirectEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("fire-home", MODE_PRIVATE).edit().putBoolean("redirect", enabled).apply();
    }

    public static boolean consumeHomeRequest() {
        return pendingHome.getAndSet(false);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (signals == null) signals = new FireHomeSignals(this);
    }

    private boolean preciseHome() { return signals != null && signals.active(); }

    void onPhysicalHome(long stamp) {
        if (!isRedirectEnabled(this)) return;
        cancelRedirect();
        main.removeCallbacks(singlePhysicalHome);
        if (firstPhysical != 0 && stamp > firstPhysical && stamp - firstPhysical <= 650) {
            firstPhysical = 0;
            amazonEntered = SystemClock.uptimeMillis();
            openAmazon();
        } else {
            firstPhysical = stamp;
            amazonEntered = 0;
            // A second physical press can arrive while the first Amazon task is still opening.
            main.postDelayed(singlePhysicalHome, 320);
        }
    }

    private void openAmazon() {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName(AMAZON_LAUNCHER, AMAZON_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException error) {
            amazonEntered = 0;
            Log.w("OpenNavaHome", "Could not open Amazon Home", error);
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (preciseHome()) return false;
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME || !isRedirectEnabled(this)) return false;
        long now = SystemClock.uptimeMillis();
        lastRawKey = now;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                returnDown = amazonEntered != 0;
                secondDown = !returnDown && firstKeyUp != 0 && now - firstKeyUp <= DOUBLE_PRESS_MS;
                firstKeyUp = 0;
            }
            return secondDown || returnDown;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        boolean claimed = secondDown || returnDown;
        if (!event.isCanceled() && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            if (returnDown) {
                amazonEntered = 0;
                openRust();
            } else if (secondDown) {
                cancelRedirect();
                firstWindow = 0;
                amazonEntered = now;
                openAmazon();
            } else {
                firstKeyUp = now;
            }
        }
        secondDown = returnDown = false;
        return claimed;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !isRedirectEnabled(this)) return;
        if (!AMAZON_LAUNCHER.equals(event.getPackageName()) || !AMAZON_HOME.equals(event.getClassName())) {
            // Amazon opens Home briefly on its way to system Settings. Android widget
            // events within that same Home window do not indicate an activity change.
            String className = String.valueOf(event.getClassName());
            if (!AMAZON_LAUNCHER.equals(event.getPackageName()) || className.startsWith(AMAZON_LAUNCHER + ".")) {
                cancelRedirect();
            }
            if (getPackageName().equals(event.getPackageName()) && NativeActivity.class.getName().equals(className)) {
                sawRustWindow = true;
            }
            return;
        }
        long now = SystemClock.uptimeMillis();
        int window = event.getWindowId();
        if (preciseHome()) {
            if (amazonEntered != 0 || (firstPhysical != 0 && SystemClock.elapsedRealtime() - firstPhysical < 320)) return;
            // With the companion present only its real key signals count toward a double press.
            // Still handle standalone Home windows at boot when no key was pressed.
            if (window == lastWindow && now - lastRedirect < 600) return;
        } else if (amazonEntered != 0) {
            // The intended switch may deliver the same Home window several times.
            if (now - amazonEntered < 900 || (lastRawKey != 0 && now - lastRawKey < 900)) return;
            amazonEntered = 0;
        } else if (lastRawKey == 0 || now - lastRawKey > 900) {
            // On some remotes Fire OS hides HOME key events. Count only a second trip
            // from our native activity to Amazon, never duplicates from the same visit.
            if (sawRustWindow && firstWindow != 0 && now - firstWindow <= 1100) {
                firstWindow = 0;
                amazonEntered = now;
                sawRustWindow = false;
                return;
            }
            if (!sawRustWindow && window == lastWindow && now - lastRedirect < 600) return;
            firstWindow = now;
        } else if (window == lastWindow && now - lastRedirect < 600 && !sawRustWindow) {
            return;
        }
        sawRustWindow = false;
        lastWindow = window;
        lastRedirect = now;
        if (!redirectScheduled) {
            redirectScheduled = true;
            main.postDelayed(settledHome, HOME_SETTLE_MS);
        }
    }

    private void cancelRedirect() {
        if (redirectScheduled) {
            main.removeCallbacks(settledHome);
            redirectScheduled = false;
            firstWindow = 0;
            lastWindow = -1;
        }
    }

    private void openRust() {
        Intent intent = new Intent(Intent.ACTION_MAIN).setClass(this, NativeActivity.class)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingHome.set(true);
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            pendingHome.set(false);
            Log.w("OpenNavaHome", "Could not open the TV home", error);
        }
    }

    @Override public void onInterrupt() { cancelRedirect(); }

    @Override public void onDestroy() {
        cancelRedirect();
        main.removeCallbacks(singlePhysicalHome);
        if (signals != null) signals.close();
        super.onDestroy();
    }
}

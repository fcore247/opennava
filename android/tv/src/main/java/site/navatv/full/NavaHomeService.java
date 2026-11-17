package site.navatv.full;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** A narrow Home-window redirect; it never retrieves or examines window contents. */
public final class NavaHomeService extends AccessibilityService {
    private static final String AMAZON_PACKAGE = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    public interface ProgrammeShortcut {
        boolean onHomeKey(android.view.KeyEvent event);
        boolean guideOpening();
        void buttonsConnected(AccessibilityService service);
        void buttonsDisconnected();
    }
    private long lastRedirect;
    private int lastHomeWindow = -1;

    @Override protected boolean onKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() != android.view.KeyEvent.KEYCODE_HOME || !(getApplication() instanceof ProgrammeShortcut)
                || !LauncherState.isRequested(this) || LauncherState.isBypassed(this)) return false;
        return ((ProgrammeShortcut)getApplication()).onHomeKey(event);
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (getApplication() instanceof ProgrammeShortcut) ((ProgrammeShortcut)getApplication()).buttonsConnected(this);
        Log.i("OpenNava", "Home service connected; requested=" + LauncherState.isRequested(this));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            return;
        String packageName = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String className = event.getClassName() == null ? "" : event.getClassName().toString();
        if (("com.amazon.tv.settings.v2".equals(packageName) && !className.isEmpty()
                && !className.endsWith("CTSDummyIntentHandler"))
                || (AMAZON_PACKAGE.equals(packageName)
                && ("com.amazon.tv.launcher.ui.SettingsActivity".equals(className)
                || "com.amazon.tv.launcher.ui.MainSettingsActivity".equals(className)))) {
            LauncherState.clearSettingsBypass(this);
        }
        if (!AMAZON_PACKAGE.equals(packageName) || !AMAZON_HOME.equals(className))
            return;
        if (!LauncherState.isRequested(this) || LauncherState.isBypassed(this)) {
            Log.i("OpenNava", "Amazon Home allowed; requested=" + LauncherState.isRequested(this)
                    + "; bypass=" + LauncherState.isBypassed(this));
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int window = event.getWindowId();
        Log.d("SeniorHome", "Amazon-Home Fenster=" + window + " Zeitpunkt=" + event.getEventTime());
        if (window >= 0 && window == lastHomeWindow && now - lastRedirect < 1300) return;
        lastHomeWindow = window;
        if (getApplication() instanceof ProgrammeShortcut && ((ProgrammeShortcut)getApplication()).guideOpening()) return;
        if (now - lastRedirect < (getApplication() instanceof ProgrammeShortcut ? 140 : 800)) return;
        lastRedirect = now;
        try {
            Log.i("OpenNava", "Redirecting Amazon Home to OpenNava");
            Intent home = "site.navatv.launcher".equals(getPackageName())
                    ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setClassName(getPackageName(), getPackageName() + ".MainActivity")
                    : new Intent(this, MainActivity.class);
            startActivity(home
                    .putExtra(MainActivity.EXTRA_FROM_HOME, true)
                    .putExtra("from_fire_home_redirect", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (RuntimeException failure) {
            Log.w("OpenNava", "Home redirect could not start OpenNava", failure);
        }
    }

    @Override public void onDestroy() {
        if (getApplication() instanceof ProgrammeShortcut) ((ProgrammeShortcut)getApplication()).buttonsDisconnected();
        super.onDestroy();
    }
    public static boolean redirectAllowed(android.content.Context context) {
        return LauncherState.isRequested(context) && !LauncherState.isBypassed(context);
    }
    @Override public void onInterrupt() { }
}

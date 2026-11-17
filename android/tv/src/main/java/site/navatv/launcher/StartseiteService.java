package site.navatv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** Redirect the one known Google TV Home window when this app holds the HOME role. */
public final class StartseiteService extends AccessibilityService {
    private static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx";
    private static final String GOOGLE_WINDOW = GOOGLE_HOME + ".home.HomeActivity";
    private long lastRedirect;

    @Override protected boolean onKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() != android.view.KeyEvent.KEYCODE_HOME || Build.VERSION.SDK_INT < 29) return false;
        RoleManager manager = getSystemService(RoleManager.class);
        return manager != null && manager.isRoleHeld(RoleManager.ROLE_HOME) && HomeProgrammeShortcut.key(this, event);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || !GOOGLE_HOME.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())
                || !GOOGLE_WINDOW.contentEquals(event.getClassName() == null ? "" : event.getClassName())) return;
        if (Build.VERSION.SDK_INT < 29) return;
        RoleManager manager = getSystemService(RoleManager.class);
        if (manager == null || !manager.isRoleAvailable(RoleManager.ROLE_HOME) || !manager.isRoleHeld(RoleManager.ROLE_HOME)) return;
        long now = SystemClock.elapsedRealtime();
        if (HomeProgrammeShortcut.suppressRedirect() || (lastRedirect > 0 && now - lastRedirect < 140)) return;
        lastRedirect = now;
        if (VisitorMode.launchIfSelected(this)) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(home);
            Log.i("Libraries", "Channels opened from Home");
        } catch (RuntimeException error) {
            Log.w("Libraries", "Could not open Home: " + error.getClass().getSimpleName());
        }
    }
    @Override public void onInterrupt() { }
}

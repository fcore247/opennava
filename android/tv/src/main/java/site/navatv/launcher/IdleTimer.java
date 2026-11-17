package site.navatv.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.util.function.BooleanSupplier;

/** A single foreground deadline. State callbacks can refresh eligibility without moving it. */
final class IdleTimer {
    private static final long IDLE_MS = 3 * 60 * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Activity owner;
    private final BooleanSupplier eligible;
    private boolean resumed, armed;
    private final Runnable expire = this::showAmbient;

    IdleTimer(Activity owner, BooleanSupplier eligible) { this.owner = owner; this.eligible = eligible; }
    void resume() { resumed = true; refresh(); }
    void pause() { resumed = false; cancel(); }
    void interaction() { cancel(); refresh(); }
    private boolean canArm() {
        if (!resumed || !owner.hasWindowFocus() || owner.isFinishing() || owner.isDestroyed() || !eligible.getAsBoolean()) return false;
        PowerManager power = owner.getSystemService(PowerManager.class);
        return power == null || power.isInteractive();
    }
    void refresh() {
        if (!canArm()) { cancel(); return; }
        if (armed) return;
        armed = true;
        handler.postDelayed(expire, IDLE_MS);
    }
    private void cancel() { handler.removeCallbacks(expire); armed = false; }
    private void showAmbient() {
        armed = false;
        if (!canArm() || VisitorMode.isVisitor(owner)) return;
        try {
            owner.startActivity(new Intent(owner, AmbientActivity.class));
            Log.i("Libraries", "Screensaver started after three minutes");
        } catch (RuntimeException failure) {
            Log.w("Libraries", "Could not open the screensaver", failure);
            refresh();
        }
    }
}

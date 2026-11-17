package site.navatv.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

/** Routes the remote's programme shortcuts without changing the selected home screen. */
final class HomeProgrammeShortcut {
    private static final long DOUBLE_MS = 550;
    private static long firstUp, lastRaw, lastHomeIntent, lastOpen, fireDeparture, lastCountedDeparture = -1;
    static void leftLauncher() { fireDeparture = SystemClock.uptimeMillis(); }
    private static boolean secondDown;
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable practiceSingle;

    static boolean preciseFire;
    static void markOpening() { lastOpen = SystemClock.uptimeMillis(); }
    static void clearSuppression() { lastOpen = 0; }
    static boolean key(Context context, KeyEvent event) {
        if (preciseFire) return false;
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME || VisitorMode.isVisitor(context)) return false;
        MainActivity practice = ((StartupSignals)context.getApplicationContext()).guidePracticeActivity();
        final long now = SystemClock.uptimeMillis();
        lastRaw = now;
        Log.d("SeniorHome", "Home-Taste Aktion=" + event.getAction() + " Zeitpunkt=" + event.getEventTime());
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                secondDown = firstUp != 0 && now - firstUp <= DOUBLE_MS;
                firstUp = 0;
            }
            if (secondDown && practiceSingle != null) main.removeCallbacks(practiceSingle);
            return secondDown || practice != null;
        }
        if (event.getAction() != KeyEvent.ACTION_UP) return false;
        boolean claimed = secondDown;
        secondDown = false;
        if (event.isCanceled() || event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout())
            return claimed;
        if (practice != null) {
            if (claimed) {
                practice.completeHomePractice();
                practiceSingle = null;
            } else {
                firstUp = now;
                practiceSingle = practice::leaveHomePractice;
                main.postDelayed(practiceSingle, DOUBLE_MS);
            }
            return true;
        }
        if (claimed) open(context);
        else firstUp = now;
        return claimed;
    }

    static boolean suppressRedirect() {
        return lastOpen != 0 && SystemClock.uptimeMillis() - lastOpen < (preciseFire ? 1350 : 350);
    }

    // Some TV firmwares deliver repeated HOME intents directly to the selected launcher.
    static boolean homeIntent(Context context, boolean fromFireRedirect) {
        long now = SystemClock.uptimeMillis();
        if (suppressRedirect()) return true;
        if (preciseFire) return false;
        if (lastRaw != 0 && now - lastRaw < 900) return false;
        if (fromFireRedirect) {
            // Fire OS reports several launcher windows per physical press. The personal home
            // must have actually left the foreground again before counting the next redirect.
            if (lastCountedDeparture == fireDeparture) return false;
            lastCountedDeparture = fireDeparture;
        }
        boolean twice = lastHomeIntent != 0 && now - lastHomeIntent >= 65 && now - lastHomeIntent <= (fromFireRedirect ? 1100 : DOUBLE_MS);
        lastHomeIntent = twice ? 0 : now;
        if (twice) open(context);
        return twice;
    }

    static void open(Context context) {
        lastOpen = SystemClock.uptimeMillis();
        try {
            Intent guide = TvProgrammeActivity.intent(context, null);
            context.startActivity(guide.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            Log.i("SeniorHome", "Double-press Home to open the guide");
        } catch (RuntimeException unavailable) {
            Log.w("SeniorHome", "Could not open the TV guide", unavailable);
        }
    }
}

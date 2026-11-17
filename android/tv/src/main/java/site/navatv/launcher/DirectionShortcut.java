package site.navatv.launcher;

import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

/** Commit the tap on release so a held direction never also changes channel. */
final class DirectionShortcut {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::hold;
    private long downTime = -1;
    private int keyCode;
    private Runnable held;

    boolean isTracking() { return downTime >= 0; }

    boolean handle(KeyEvent event, Runnable onTap, Runnable onHold) {
        long start = event.getDownTime();
        int key = event.getKeyCode();
        int threshold = ViewConfiguration.getLongPressTimeout();
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                if (event.getRepeatCount() == 0 && (start != downTime || key != keyCode)) {
                    cancel();
                    downTime = start;
                    keyCode = key;
                    held = onHold;
                    handler.postAtTime(timeout, start + threshold);
                } else if (start == downTime && key == keyCode && (event.isLongPress() || event.getEventTime() - start >= threshold)) {
                    hold();
                }
                return true;
            case KeyEvent.ACTION_UP:
                if (start == downTime && key == keyCode) {
                    Runnable release = held == null || event.isCanceled() ? null
                            : event.getEventTime() - start >= threshold ? held : onTap;
                    cancel();
                    if (release != null) release.run();
                }
                return true;
            default:
                return false;
        }
    }

    private void hold() {
        Runnable action = held;
        held = null;
        handler.removeCallbacks(timeout);
        if (action != null) action.run();
    }

    void cancel() {
        handler.removeCallbacks(timeout);
        downTime = -1;
        held = null;
    }
}

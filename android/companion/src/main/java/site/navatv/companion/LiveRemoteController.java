package site.navatv.companion;

import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;

/** Observes the foreground player only while the phone's remote page is visible. */
final class LiveRemoteController {
    private final MainActivity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::read;
    private final RemoteControl remote;
    private LinearLayout slot;
    private boolean started, reading;
    private TvConnection.Device observed;
    private TvConnection.LiveState state = TvConnection.LiveState.OTHER;

    LiveRemoteController(MainActivity activity) {
        this.activity = activity;
        remote = new RemoteControl(activity.ui, activity::sendKey);
    }

    void attach(LinearLayout body) {
        if (observed != activity.device) state = TvConnection.LiveState.OTHER;
        observed = activity.device;
        slot = new LinearLayout(activity);
        slot.setOrientation(LinearLayout.VERTICAL);
        body.addView(slot);
        draw();
        schedule(0);
    }

    void detach() {
        slot = null;
        ui.removeCallbacks(poll);
    }

    void setStarted(boolean started) {
        this.started = started;
        if (started) schedule(0);
        else ui.removeCallbacks(poll);
    }

    private boolean watching() {
        return started && slot != null && activity.connected() && activity.device.supportsLiveTune;
    }

    private void schedule(long delay) {
        ui.removeCallbacks(poll);
        if (watching() && !reading) ui.postDelayed(poll, delay);
    }

    private void read() {
        if (!watching() || reading) return;
        reading = true;
        TvConnection.Device device = activity.device;
        activity.runTv(activity.tv::liveState, next -> {
            reading = false;
            if (watching() && device == activity.device) update(next);
            schedule(1800);
        }, error -> {
            reading = false;
            if (device == activity.device && !activity.connected()) {
                activity.device = null;
                if (slot != null && started) activity.render();
                return;
            }
            if (watching()) update(TvConnection.LiveState.OTHER);
            schedule(4000);
        });
    }

    private void update(TvConnection.LiveState next) {
        if (!state.equals(next)) {
            state = next;
            draw();
        }
    }

    private void send(String action) {
        TvConnection.Device device = activity.device;
        activity.runTv(() -> {
            activity.tv.liveControl(action);
            return activity.tv.liveState();
        }, next -> {
            if (watching() && activity.device == device) update(next);
            schedule(350);
        }, error -> {
            activity.say(activity.error(error));
            schedule(0);
        });
    }

    private void draw() {
        slot.removeAllViews();
        if (activity.connected() && !state.channel.isEmpty())
            remote.buildLive(slot, state, activity.device.hasRtl, this::send);
        else
            remote.build(slot, activity::textDialog);
    }
}

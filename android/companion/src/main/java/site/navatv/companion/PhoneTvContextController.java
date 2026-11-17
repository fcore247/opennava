package site.navatv.companion;

import android.os.Handler;
import android.os.Looper;

/** Follow TV navigation while the phone is visible. Reading a screen never sends navigation to the TV. */
final class PhoneTvContextController {
    private final MainActivity app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable read = this::read;
    private boolean started, pending;
    private TvConnection.Device observed;
    private TvConnection.ScreenState last;

    PhoneTvContextController(MainActivity app) { this.app = app; }

    void start() {
        started = true;
        last = null;
        refresh();
        schedule(0);
    }

    void stop() {
        started = false;
        ui.removeCallbacks(read);
    }

    void refresh() {
        TvConnection.Device current = app.connected() ? app.device : null;
        if (current != observed) {
            observed = current;
            last = null;
            schedule(0);
        }
    }

    private void schedule(long delay) {
        ui.removeCallbacks(read);
        if (started && observed != null && observed.seniorVersion >= 39 && !pending)
            ui.postDelayed(read, delay);
    }

    private void read() {
        if (!started || !app.connected() || app.device != observed) { refresh(); return; }
        TvConnection connection = app.tv;
        TvConnection.Device target = observed;
        String pageAtRequest = app.page;
        pending = true;
        app.runTv(connection::screenState, screen -> {
            pending = false;
            if (started && observed == target && connection == app.tv) {
                if (!screen.screen.equals("live") || !screen.section.equals("channels"))
                    app.livePage.closeMirroredChannelPicker();
                boolean changed = !screen.sameView(last);
                boolean manual = app.menuOpen() || app.page.equals("remote") || app.page.equals("devices");
                if (manual || app.hasWindowFocus()) last = screen;
                if (!manual && pageAtRequest.equals(app.page) && app.hasWindowFocus()) {
                    if (changed && (!screen.screen.equals("idle") || app.page.equals("home"))) follow(screen);
                    if (app.page.equals("live")) app.livePage.updatePlayback(screen.live);
                }
            }
            schedule(2000);
        }, error -> {
            pending = false;
            if (connection == app.tv && target == app.device && !app.connected()) {
                app.device = null;
                app.render();
            }
            schedule(4500);
        });
    }

    private void follow(TvConnection.ScreenState screen) {
        switch (screen.screen) {
            case "catalog":
                app.showTvProvider(screen.provider, screen.query);
                break;
            case "live":
            case "guide":
                if (screen.screen.equals("guide") && screen.section.equals("reminders")) {
                    app.showTvPage("recordings");
                    break;
                }
                if (!screen.channel.isEmpty()) app.livePage.selectedId = screen.channel;
                String target = screen.screen.equals("live") ? "live" : "programme";
                if (app.page.equals(target)) app.livePage.followChannel(screen.channel);
                else app.showTvPage(target);
                if (target.equals("live") && screen.section.equals("channels")) app.livePage.showTvChannelPicker();
                break;
            case "recordings": app.showTvPage("recordings"); break;
            case "player": app.showTvPlayback(screen.provider, screen.title); break;
            default: app.showTvPage("home"); break;
        }
    }
}

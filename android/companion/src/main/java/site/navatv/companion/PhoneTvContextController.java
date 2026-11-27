package site.navatv.companion;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import site.navatv.launcher.CustomStreams;
import site.navatv.launcher.PhoneGuide;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Follows the television while the phone is visible. Reading never sends navigation to the TV.
 * It reads on its own thread, so a slow report never delays a remote key.
 */
final class PhoneTvContextController {
    /** One observation: the Android-wide report plus OpenNava's own detail when it is on screen. */
    static final class Snapshot {
        final TvStatus status;
        /** Null unless OpenNava answered about its current screen. */
        final TvConnection.ScreenState screen;

        final Playback playback;
        /** The page open in OpenNava Rust; all empty when it is not on screen. */
        final String rustScreen, rustLibrary;

        Snapshot(TvStatus status, TvConnection.ScreenState screen, String rustScreen, String rustLibrary, Playback playback) {
            this.playback = playback;
            this.status = status;
            this.screen = screen;
            this.rustScreen = rustScreen;
            this.rustLibrary = rustLibrary;
        }

        /** The library the TV is showing: "ard", "zdf", "arte", an own library ID, or "". */
        String library() {
            if (!rustLibrary.isEmpty()) return rustLibrary;
            if (screen != null && (screen.screen.equals("catalog") || screen.screen.equals("player")))
                return screen.provider;
            return "";
        }

        /** Which of the phone's layouts matches the television: "live", "library", "navatube" or "". */
        String mode() {
            if (!status.awake) return "";
            String kind = screen == null ? "" : screen.screen;
            if (rustScreen.equals("youtube")) return "navatube";
            if (!live().channel.isEmpty() || kind.equals("live") || kind.equals("guide")) return "live";
            if (kind.equals("catalog") || kind.equals("player") || rustScreen.equals("libraries")
                    || rustScreen.equals("catalog") || rustScreen.equals("player") || !rustLibrary.isEmpty())
                return "library";
            return "";
        }

        TvConnection.LiveState live() { return screen == null ? TvConnection.LiveState.OTHER : screen.live; }

        boolean same(Snapshot other) {
            return other != null && playback.same(other.playback) && status.equals(other.status) && live().equals(other.live())
                    && rustScreen.equals(other.rustScreen) && rustLibrary.equals(other.rustLibrary)
                    && (screen == null ? other.screen == null : screen.sameView(other.screen));
        }
    }

    /** What one of OpenNava's own video players reports. */
    static final class Playback {
        static final Playback NONE = new Playback(new org.json.JSONObject());
        final String kind, id, title, author;
        final long position, duration, at;
        final boolean playing, live;

        private Playback(org.json.JSONObject o) {
            kind = o.optString("kind");
            id = o.optString("id");
            title = o.optString("title");
            author = o.optString("author");
            position = o.optLong("position");
            duration = o.optLong("duration");
            playing = o.optBoolean("playing");
            live = o.optBoolean("live");
            at = SystemClock.elapsedRealtime();
        }

        static Playback parse(org.json.JSONObject o) { return o.has("kind") ? new Playback(o) : NONE; }

        boolean present() { return !kind.isEmpty(); }

        /** Everything except the running position. */
        boolean same(Playback other) {
            return kind.equals(other.kind) && id.equals(other.id) && title.equals(other.title)
                    && author.equals(other.author) && playing == other.playing && live == other.live;
        }
    }

    private static final long PACE = 1500, STREAM_PACE = 20_000;

    private final MainActivity app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final Runnable read = this::read;
    private boolean started, pending, sendingHome;
    private TvConnection.Device observed;
    private Snapshot latest;
    private long streamsRead;
    private int failures;
    /** The library last open in OpenNava Rust or last played from the phone. */
    private volatile String lastLibrary = "";
    private volatile String playedTitle = "";

    /** The phone sent a video to the Rust player, which does not report what it plays. */
    void played(String library, String title) {
        lastLibrary = library;
        playedTitle = title;
        nudge();
    }

    String playedTitle() { return playedTitle; }

    PhoneTvContextController(MainActivity app) { this.app = app; }

    /** Null while disconnected or before the first report. */
    Snapshot latest() { return latest; }

    void start() {
        started = true;
        refresh();
        schedule(0);
    }

    void stop() {
        started = false;
        ui.removeCallbacks(read);
    }

    void close() {
        stop();
        reader.shutdownNow();
    }

    void refresh() {
        TvConnection.Device current = app.connected() ? app.device : null;
        if (current == observed) return;
        observed = current;
        latest = null;
        streamsRead = 0;
        failures = 0;
        sendingHome = false;
        schedule(0);
    }

    /** Ask again right after the phone sent a command, so that its effect shows at once. */
    void nudge() { schedule(250); }

    /** The own streams changed from this phone. */
    void streamsChanged() {
        streamsRead = 0;
        nudge();
    }

    void openHome() {
        TvConnection connection = app.tv;
        TvConnection.Device target = observed;
        if (!app.connected() || target == null || app.connecting) {
            app.needConnection();
            return;
        }
        if (sendingHome) return;
        sendingHome = true;
        app.runTv(() -> {
            if (connection.device() != target) throw new java.io.IOException("Your TV connection has changed.");
            connection.openHome();
            return null;
        }, ignored -> {
            sendingHome = false;
            nudge();
        }, error -> {
            sendingHome = false;
            app.say("Could not open Home on the TV. " + app.error(error));
            app.refreshConnectionHeader();
        });
    }

    private void schedule(long delay) {
        ui.removeCallbacks(read);
        if (started && observed != null && !pending) ui.postDelayed(read, delay);
    }

    private void read() {
        if (!started || pending) return;
        if (!app.connected() || app.device != observed) { refresh(); return; }
        TvConnection connection = app.tv;
        TvConnection.Device target = observed;
        boolean wantStreams = target.seniorVersion >= TvConnection.FIRST_TV_WITH_OWN_STREAMS
                && (streamsRead == 0 || SystemClock.elapsedRealtime() - streamsRead > STREAM_PACE);
        pending = true;
        try {
            reader.execute(() -> {
                Snapshot snapshot = null;
                List<CustomStreams.Entry> streams = null;
                @SuppressWarnings("unchecked")
                List<site.navatv.launcher.OwnLibrary.Source>[] libraries = new List[1];
                try {
                    TvStatus status = connection.status();
                    TvConnection.ScreenState screen = null;
                    // Live TV, the guide, recordings and the broadcaster player report their own detail.
                    if (status.awake && TvStatus.OPENNAVA_RUST.equals(status.app)
                            && status.activity.startsWith("site.navatv.launcher.")) {
                        try { screen = connection.screenState(); }
                        catch (Exception withoutDetail) { /* the Android report still describes the TV */ }
                    }
                    String rustScreen = "", rustLibrary = "";
                    if (status.awake && TvStatus.OPENNAVA_RUST.equals(status.app)
                            && status.activity.endsWith("StreamActivity")) {
                        rustScreen = "player";
                        rustLibrary = lastLibrary;
                    } else if (status.awake && TvStatus.OPENNAVA_RUST.equals(status.app)
                            && status.activity.endsWith("NativeActivity")) {
                        try {
                            org.json.JSONObject page = connection.rustScreen();
                            rustScreen = page.optString("screen");
                            if (rustScreen.equals("catalog")) rustLibrary = page.optString("provider").equals("own")
                                    ? page.optString("library") : page.optString("provider");
                            if (!rustLibrary.isEmpty()) {
                                if (!rustLibrary.equals(lastLibrary)) playedTitle = "";
                                lastLibrary = rustLibrary;
                            }
                        } catch (Exception olderLauncher) { /* shown as a home screen */ }
                    }
                    Playback playback = Playback.NONE;
                    if (status.awake && TvStatus.OPENNAVA_RUST.equals(status.app)
                            && (status.activity.endsWith("NavaPlayerActivity") || status.activity.endsWith("StreamActivity"))) {
                        try { playback = Playback.parse(connection.navaTube("now", "")); }
                        catch (Exception olderLauncher) { /* the mini player still shows that something plays */ }
                    }
                    snapshot = new Snapshot(status, screen, rustScreen, rustLibrary, playback);
                    if (wantStreams) {
                        try { streams = connection.ownStreams(); }
                        catch (Exception unavailable) { /* retried with the next interval */ }
                        try { libraries[0] = connection.librarySources(); }
                        catch (Exception withoutRust) { /* retried with the next interval */ }
                    }
                } catch (Exception unreadable) { /* handled on the UI thread */ }
                Snapshot result = snapshot;
                List<CustomStreams.Entry> own = streams;
                List<site.navatv.launcher.OwnLibrary.Source> sources = libraries[0];
                ui.post(() -> {
                    if (sources != null && observed == target) app.showLibraries(sources);
                    deliver(connection, target, result, wantStreams, own);
                });
            });
        } catch (RejectedExecutionException closing) {
            pending = false;
        }
    }

    private void deliver(TvConnection connection, TvConnection.Device target, Snapshot result,
                         boolean askedForStreams, List<CustomStreams.Entry> own) {
        pending = false;
        if (!started || observed != target || connection != app.tv) {
            schedule(0);
            return;
        }
        if (result == null) {
            if (!connection.isConnected()) {
                app.device = null;
                app.render();
            } else schedule(Math.min(10_000, PACE * (1 + ++failures)));
            return;
        }
        failures = 0;
        if (askedForStreams) {
            streamsRead = SystemClock.elapsedRealtime();
            if (own != null) {
                PhoneGuide.setCustomStreams(own);
                app.showOwnStreams(own);
            }
        }
        Snapshot before = latest;
        latest = result;
        if (!result.same(before)) app.tvChanged(before, result);
        else app.playbackTick();
        schedule(PACE);
    }
}

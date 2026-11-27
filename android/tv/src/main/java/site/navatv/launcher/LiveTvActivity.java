package site.navatv.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsManifest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class LiveTvActivity extends Activity {
    private static final int MANAGE_STREAMS = -1;
    private static LiveTvActivity resumedPlayer;
    private static final String TUNE_LIVE = "site.navatv.launcher.TUNE_LIVE";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
            .withZone(ZoneId.of("Europe/Berlin"));
    private static final DateTimeFormatter SCRUB_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK)
            .withZone(ZoneId.of("Europe/Berlin"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DirectionShortcut channelShortcut = new DirectionShortcut();
    private final ExecutorService network = Executors.newFixedThreadPool(3);
    private final ExecutorService guides = Executors.newFixedThreadPool(2);
    private final ExecutorService listPool = Executors.newFixedThreadPool(4);
    private final List<Future<?>> listTasks = new ArrayList<>();
    private final List<LiveTvControls.Row> listRows = new ArrayList<>();
    private List<Integer> listOrder = new ArrayList<>();
    private final Set<String> mine = new LinkedHashSet<>();
    private int previousStation = -1, listGeneration;
    private boolean listMine = true, listOkDown, listOkLong, libraryAvailable;
    private long listOpeningPress = -1;
    private long listPainted, lastAutoRetry = -1;
    private String typed = "";
    private final Runnable commitDigits = this::commitDigits;
    private final Runnable paintList = this::paintList;
    private final Runnable listTimeout = this::closeChannelList;
    private final IdleTimer idle = new IdleTimer(this, () -> playerIdle());
    private LiveTvControls view;
    private ExoPlayer player;
    private LiveTvMediaSession mediaSession;
    private long pausedAirtime = C.TIME_UNSET, pausedPeriodTime = C.TIME_UNSET, pausedPosition, lastMediaDown = -1;
    private int lastMediaCode;
    private List<LiveGuide.Channel> channels;
    private int station, tuneGeneration, mediaGeneration;
    private List<LiveGuide.Programme> listings = Collections.emptyList();
    private LiveGuide.Programme replay, guideSelection;
    private String liveUrl;
    private Future<?> streamTask, guideTask, replayTask;
    private long nextGuideUpdate;
    private long bookmarkUnavailable = -1;
    private boolean guessedFormat, plainRetry;
    private boolean guidePending, pendingDuration, failed, firstFrame, requestPlay = true;
    private long resumeProgramme = -1, resumePosition, resumeLiveTime = C.TIME_UNSET;
    private long resumeFromEnd = C.TIME_UNSET, lastWindowRecovery = -1;
    private boolean triedDefaultRecovery;
    private int heldKey;
    private long heldSince, scrubPeriodTime = C.TIME_UNSET;
    private boolean scrubAtEnd;
    private boolean heldLong;
    private final Runnable beginHold = this::startLongPress;
    private final Runnable advanceHold = this::advanceScrub;
    private final Runnable hideStrip = () -> {
        if (view.durationDialog == null && !pendingDuration && heldKey == 0 && !failed && firstFrame && requestPlay) view.hide();
    };
    private final Runnable hideMessage = () -> view.message(null);
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            long now = System.currentTimeMillis();
            if (!guidePending && now >= nextGuideUpdate) fetchGuide(now);
            if (view.isVisible()) updateStrip();
            if (view.listVisible() && now - listPainted > 20000) paintList();
            publishPlayback();
            main.postDelayed(this, 1000);
        }
    };

    static Intent replayIntent(Context context, String channelId, long programmeStart) {
        return new Intent(context, LiveTvActivity.class).putExtra("channel", channelId)
                .putExtra("programmeStart", programmeStart);
    }

    static Intent companionTuneIntent(Context context, String channelId) {
        if (stationIndex(channelId) < 0) return null;
        return new Intent(context, LiveTvActivity.class).setAction(TUNE_LIVE)
                .putExtra("channel", channelId)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    /** The viewer added or removed a stream while the player is open. Called on the main thread. */
    static void lineupChanged() {
        if (resumedPlayer != null) resumedPlayer.refreshLineup();
    }

    // Station numbers are positions in this list, so they move together when the line-up changes.
    private void refreshLineup() {
        List<LiveGuide.Channel> latest = LiveGuide.channels();
        if (channels == null || latest == channels) return;
        String current = channels.get(station).id;
        channels = latest;
        station = Math.max(0, stationIndex(current));
        previousStation = -1;
        includeNewStreams();
        if (view != null && view.listVisible()) closeChannelList();
        if (!channels.get(station).id.equals(current)) tuneTo(station, 0);
    }

    /** US and own streams join "My channels" once. Removing one from the favourites is remembered. */
    private void includeNewStreams() {
        Set<String> seen = new LinkedHashSet<>(getPreferences(MODE_PRIVATE)
                .getStringSet("introduced", Collections.<String>emptySet()));
        boolean changed = false;
        for (LiveGuide.Channel item : channels) {
            if (!item.direct() || !seen.add(item.id)) continue;
            mine.add(item.id);
            changed = true;
        }
        if (!changed) return;
        android.content.SharedPreferences.Editor edit = getPreferences(MODE_PRIVATE).edit().putStringSet("introduced", seen);
        if (getPreferences(MODE_PRIVATE).contains("mine")) edit.putString("mine", String.join(",", mine));
        edit.apply();
    }

    private static int stationIndex(String channelId) {
        List<LiveGuide.Channel> stations = LiveGuide.channels();
        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).id.equals(channelId)) return i;
        }
        return -1;
    }

    // Accessed only on the main thread, including requests from the companion provider.
    private static LiveTvActivity activePlayer() {
        LiveTvActivity live = resumedPlayer;
        if (live == null || live.player == null || live.isFinishing() || !live.hasWindowFocus()
                || live.view.durationDialog != null || live.view.listVisible()) return null;
        return live;
    }

    static Bundle companionState() {
        LiveTvActivity live = activePlayer();
        Bundle state = new Bundle();
        state.putString("mode", live == null ? "other" : "live");
        if (live != null) {
            state.putString("channel", live.channel().id);
            state.putBoolean("paused", !live.requestPlay);
            state.putBoolean("seekable", live.seekWindow() != null);
            state.putString("recordMode", live.recordMode());
            state.putBoolean("recordEnd", live.untilProgrammeEnd() > 0);
        }
        return state;
    }

    static boolean companionControl(String action) {
        LiveTvActivity live = activePlayer();
        if (live == null || action == null) return false;
        if (action.startsWith("record_")) {
            if (!"duration".equals(live.recordMode())) return false;
            String value = action.substring(7);
            long length;
            if ("end".equals(value)) length = live.untilProgrammeEnd();
            else if (value.matches("[1-9][0-9]{0,2}")) length = Integer.parseInt(value) * 60000L;
            else return false;
            if (length <= 0 || length > 360 * 60000L) return false;
            live.idle.interaction();
            return live.startRecording(live.channel(), live.liveUrl, length);
        }
        switch (action) {
            case "next": live.switchStation(1); break;
            case "previous": live.switchStation(-1); break;
            case "pause": live.playbackAction(); break;
            case "rewind": case "forward":
                if (live.seekWindow() == null) return false;
                live.skipSeconds("rewind".equals(action) ? -1 : 1);
                break;
            case "guide": live.startActivity(TvProgrammeActivity.intent(live, live.channel().id)); break;
            case "record": live.chooseDuration(); break;
            default: return false;
        }
        live.idle.interaction();
        return true;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SeniorPlaybackHost.playback(this, true);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
        CustomStreamStore.load(this);
        channels = LiveGuide.channels();
        if (channels.isEmpty()) { finish(); return; }
        String selected = getIntent().getStringExtra("channel");
        resumeProgramme = getIntent().getLongExtra("programmeStart", -1);
        if (selected == null) selected = getPreferences(MODE_PRIVATE).getString("channel", "");
        if (state != null) {
            selected = state.getString("channel", selected);
            resumeProgramme = state.getLong("programme", -1);
            resumePosition = state.getLong("position", 0);
            resumeLiveTime = state.getLong("airtime", C.TIME_UNSET);
            requestPlay = state.getBoolean("playing", true);
        }
        station = Math.max(0, stationIndex(selected));
        String saved = getPreferences(MODE_PRIVATE).getString("mine", null);
        for (LiveGuide.Channel item : channels) {
            if (saved == null ? !item.regional && !item.direct() : ("," + saved + ",").contains("," + item.id + ",")) mine.add(item.id);
        }
        includeNewStreams();
        Intent intent = getIntent();
        if (resumeProgramme >= 0 && resumeProgramme == intent.getLongExtra("programmeStart", -1)
                && channel().id.equals(intent.getStringExtra("channel"))) {
            guideSelection = new LiveGuide.Programme(extra(intent, "programmeTitle"), resumeProgramme,
                    intent.getLongExtra("programmeEnd", -1), extra(intent, "programmeProvider"),
                    extra(intent, "programmeDetail"), extra(intent, "programmeEventId"));
        }
        view = new LiveTvControls(this);
        if (state == null && TUNE_LIVE.equals(intent.getAction())) tuneTo(station, 0);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!TUNE_LIVE.equals(intent.getAction())) return;
        refreshLineup();
        int index = stationIndex(intent.getStringExtra("channel"));
        if (index < 0) return;
        setIntent(intent);
        closeChannelList();
        if (view.durationDialog != null) view.durationDialog.dismiss();
        tuneTo(index, 0);
    }

    private static String extra(Intent intent, String name) {
        String value = intent.getStringExtra(name);
        return value == null ? "" : value;
    }

    @Override protected void onStart() {
        super.onStart();
        if (isFinishing()) return;
        refreshLineup();
        // Start the picture after one second of media. Changing channel should feel like a television.
        player = new ExoPlayer.Builder(this).setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000, 50000, 1000, 2000).build()).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage("de").build());
        player.setVideoSurfaceView(view.surface);
        mediaSession = new LiveTvMediaSession(this, main, new LiveTvMediaSession.Transport() {
            @Override public boolean key(KeyEvent event) { return handleMediaKey(event); }
            @Override public void setPlaying(boolean playing) { setPlayback(playing); }
        });
        player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() {
                boolean initialFrame = !firstFrame;
                firstFrame = true;
                if (!requestPlay && pausedPeriodTime == C.TIME_UNSET) capturePause();
                if (initialFrame && heldKey == 0 && !pendingDuration) view.message(null);
                if (initialFrame && view.isVisible() && view.durationDialog == null) scheduleHide();
                view.busy(false);
                if (initialFrame && replay == null) prefetchNeighbours();
                Log.i("SeniorLive", "First frame: " + channel().id + (replay == null ? " live" : " Library"));
                Timeline.Window shown = window();
                if (replay == null && shown != null && shown.windowStartTimeMs != C.TIME_UNSET)
                    Log.i("SeniorLive", "stream time: offset " + (System.currentTimeMillis() - shown.windowStartTimeMs
                            - shown.getDurationMs()) / 1000 + " s");
            }
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.height > 0) view.sizeVideo(size.width * size.pixelWidthHeightRatio / size.height);
            }
            @Override public void onPlaybackStateChanged(int status) {
                if (player == null) return;
                if (status == Player.STATE_ENDED && replay != null) {
                    main.post(() -> { if (player != null && player.getPlaybackState() == Player.STATE_ENDED) toLive(); });
                }
                view.busy(!failed && status == Player.STATE_BUFFERING);
                publishPlayback();
                idle.refresh();
            }
            @Override public void onTimelineChanged(Timeline timeline, int reason) {
                if (player == null || replay != null || (resumeLiveTime == C.TIME_UNSET && resumeFromEnd == C.TIME_UNSET)) return;
                Timeline.Window w = seekWindow();
                if (w == null) return;
                long pos;
                if (resumeLiveTime != C.TIME_UNSET && w.windowStartTimeMs != C.TIME_UNSET)
                    pos = resumeLiveTime - w.windowStartTimeMs;
                else if (resumeFromEnd != C.TIME_UNSET) pos = w.getDurationMs() - 1 - resumeFromEnd;
                else return;
                resumeFromEnd = resumeLiveTime = C.TIME_UNSET;
                player.seekTo(bound(w, pos));
                if (!requestPlay) capturePause();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                view.surface.setKeepScreenOn(playing);
                publishPlayback();
                idle.refresh();
            }
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                if (player == null || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return;
                if (!ready && requestPlay) capturePause();
                requestPlay = ready;
                showStrip(!ready);
                publishPlayback();
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (!requestPlay && player != null && reason == Player.DISCONTINUITY_REASON_SEEK) capturePause();
            }
            @Override public void onPlayerError(PlaybackException problem) {
                if (player == null) return;
                Timeline.Window w = seekWindow();
                if (expiredSegment(problem, w)) {
                    if (!requestPlay || scrubPeriodTime != C.TIME_UNSET) { publishPlayback(); return; }
                    reloadLiveWindow(w, 0);
                    return;
                }
                Log.w("SeniorLive", channel().id + ": " + problem.getErrorCodeName());
                if (guessedFormat && !firstFrame && replay == null && liveUrl != null
                        && (problem.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                        || problem.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)) {
                    plainRetry = true;
                    play(liveUrl, false, 0);
                    return;
                }
                long moment = SystemClock.elapsedRealtime();
                if (replay == null && guideSelection == null && requestPlay
                        && (lastAutoRetry < 0 || moment - lastAutoRetry > 30000)) {
                    // The cached address may have moved. Ask the broadcaster once more before bothering the viewer.
                    lastAutoRetry = moment;
                    LiveGuide.forgetStream(channel());
                    Log.i("SeniorLive", "Automatic retry: " + channel().id);
                    tune();
                    return;
                }
                error(replay == null ? "Channel currently unavailable." : "This programme is unavailable.");
            }
        });
        tune();
        main.post(tick);
    }

    private LiveGuide.Channel channel() { return channels.get(station); }
    String companionChannel() { return channel().id; }
    String companionSection() {
        if (view.durationDialog != null) return "duration";
        if (view.listVisible()) return "channels";
        return replay != null ? "replay" : "watch";
    }
    private long untilProgrammeEnd() {
        long now = System.currentTimeMillis();
        LiveGuide.Programme onAir = airing(now);
        long length = onAir == null ? 0 : onAir.end - now;
        return length > 1000 && length <= 360 * 60000L ? length : 0;
    }
    private String recordMode() {
        if (failed || channel().direct() || (guideSelection != null && replay == null)) return "unavailable";
        if (replay != null) return firstFrame ? "remember" : "unavailable";
        if (liveUrl == null) return "unavailable";
        LiveGuide.Programme displayed = airing(screenTime());
        if (displayed != null && displayed.end <= System.currentTimeMillis() && displayed.start != bookmarkUnavailable) return "remember";
        return "duration";
    }

    private void tune() { tune(0); }

    /** A short delay lets several quick presses pass through channels without starting each stream. */
    private void tune(long delay) {
        plainRetry = false;
        int generation = ++tuneGeneration;
        ++mediaGeneration;
        cancel(streamTask);
        cancel(guideTask);
        cancel(replayTask);
        cancelKeys();
        channelShortcut.cancel();
        main.removeCallbacks(hideMessage);
        player.stop();
        player.clearMediaItems();
        liveUrl = null;
        pausedAirtime = C.TIME_UNSET;
        pausedPeriodTime = C.TIME_UNSET;
        pausedPosition = 0;
        pendingDuration = false;
        bookmarkUnavailable = -1;
        failed = firstFrame = false;
        replay = null;
        guidePending = false;
        nextGuideUpdate = System.currentTimeMillis() + delay + 5000;
        LiveGuide.Channel channel = channel();
        libraryAvailable = LiveLibraryActivity.available(this, channel);
        listings = guideSelection == null && resumeProgramme < 0
                ? LiveGuide.known(channel, System.currentTimeMillis()) : Collections.<LiveGuide.Programme>emptyList();
        view.message(null);
        view.busy(true);
        showStrip(false);
        Runnable begin = () -> { if (currentStation(generation)) beginTune(generation, channel); };
        if (delay > 0) main.postDelayed(begin, delay);
        else begin.run();
    }

    private void beginTune(int generation, LiveGuide.Channel channel) {
        long guideAt = guideSelection != null ? guideSelection.start : resumeProgramme;
        fetchGuide(guideAt >= 0 ? guideAt : System.currentTimeMillis());
        if (guideSelection != null && hasReplay(guideSelection) && !guideSelection.provider.isEmpty()
                && guideSelection.end > guideSelection.start && !guideSelection.title.isEmpty()) {
            resumeProgramme = -1;
            openReplay(guideSelection, resumePosition, false);
        } else if (guideSelection != null) resumeProgramme = guideSelection.start;
        String known = LiveGuide.cachedStream(channel);
        if (known != null) { liveReady(known); return; }
        streamTask = network.submit(() -> {
            try {
                String url = LiveGuide.resolveStream(channel);
                main.post(() -> { if (currentStation(generation)) liveReady(url); });
            } catch (Exception missing) {
                main.post(() -> {
                    if (currentStation(generation) && guideSelection == null && replay == null && resumeProgramme < 0)
                        error("Channel currently unavailable.");
                });
            }
        });
    }

    private void liveReady(String url) {
        liveUrl = url;
        if (guideSelection == null && resumeProgramme < 0 && replay == null) play(url, true, 0);
        if (pendingDuration) {
            pendingDuration = false;
            chooseDuration();
        }
    }

    /** Resolve the two neighbouring channels in advance, so the next press starts at once. */
    private void prefetchNeighbours() {
        List<Integer> order = zapOrder();
        int at = order.indexOf(station);
        if (at < 0 || order.size() < 2) return;
        for (int step : new int[] {1, -1}) {
            LiveGuide.Channel neighbour = channels.get(order.get(Math.floorMod(at + step, order.size())));
            listPool.submit(() -> {
                try { if (LiveGuide.cachedStream(neighbour) == null) LiveGuide.resolveStream(neighbour); }
                catch (Exception unavailable) { }
                try { LiveGuide.programmes(neighbour, System.currentTimeMillis()); }
                catch (Exception unavailable) { }
            });
        }
    }

    private void fetchGuide(long at) {
        LiveGuide.Channel channel = channel();
        int generation = tuneGeneration;
        guidePending = true;
        guideTask = guides.submit(() -> {
            List<LiveGuide.Programme> result;
            try { result = LiveGuide.programmes(channel, at); }
            catch (Exception unavailable) { result = Collections.emptyList(); }
            List<LiveGuide.Programme> programmes = result;
            main.post(() -> {
                if (!currentStation(generation)) return;
                guidePending = false;
                if (!programmes.isEmpty()) listings = programmes;
                nextGuideUpdate = System.currentTimeMillis() + (programmes.isEmpty() ? 60_000 : 10 * 60_000L);
                if (resumeProgramme >= 0) {
                    long start = resumeProgramme;
                    resumeProgramme = -1;
                    LiveGuide.Programme item = null;
                    for (LiveGuide.Programme candidate : programmes) {
                        if (candidate.start != start) continue;
                        if (guideSelection != null && !guideSelection.eventId.isEmpty()
                                && !guideSelection.eventId.equals(candidate.eventId)) continue;
                        if (guideSelection != null && !guideSelection.title.isEmpty()
                                && !guideSelection.title.equalsIgnoreCase(candidate.title)) continue;
                        item = candidate;
                        break;
                    }
                    if (item != null) {
                        if (guideSelection != null) guideSelection = item;
                        openReplay(item, resumePosition, false);
                    } else if (guideSelection != null) error("This programme is no longer in the schedule.");
                    else if (liveUrl != null) play(liveUrl, true, 0);
                }
                updateStrip();
            });
        });
    }

    private boolean currentStation(int generation) { return player != null && generation == tuneGeneration; }

    private void play(String url, boolean hls, long position) {
        cancelKeys();
        resumeFromEnd = C.TIME_UNSET;
        lastWindowRecovery = -1;
        pausedPeriodTime = pausedAirtime = C.TIME_UNSET;
        failed = firstFrame = false;
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        String type = hls || url.toLowerCase(Locale.ROOT).contains(".m3u8") ? MimeTypes.APPLICATION_M3U8 : null;
        guessedFormat = false;
        if (replay == null && "custom".equals(channel().provider)) {
            String path = String.valueOf(android.net.Uri.parse(url).getPath()).toLowerCase(Locale.ROOT);
            if (url.toLowerCase(Locale.ROOT).contains(".m3u8")) type = MimeTypes.APPLICATION_M3U8;
            else if (path.endsWith(".mpd")) type = MimeTypes.APPLICATION_MPD;
            else if (path.matches(".*\\.(mp4|m4v|mkv|webm|ts|mp3|aac|m4a)")) type = null;
            else {
                // Many addresses carry no file ending. Try HLS first, then let the player inspect the data.
                type = plainRetry ? null : MimeTypes.APPLICATION_M3U8;
                guessedFormat = !plainRetry;
            }
        }
        if (type != null) item.setMimeType(type);
        if (position > 0) player.setMediaItem(item.build(), position);
        else player.setMediaItem(item.build());
        player.prepare();
        player.setPlayWhenReady(requestPlay);
        updateStrip();
    }

    /** Up and Down pass through the viewer's own channels. Every channel stays reachable in the list. */
    private List<Integer> zapOrder() {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) if (mine.contains(channels.get(i).id)) order.add(i);
        if (order.isEmpty()) for (int i = 0; i < channels.size(); i++) order.add(i);
        return order;
    }

    private void switchStation(int direction) {
        List<Integer> order = zapOrder();
        int at = order.indexOf(station), target = -1;
        if (at >= 0) target = order.get(Math.floorMod(at + direction, order.size()));
        else if (direction > 0) {
            for (int index : order) if (index > station) { target = index; break; }
            if (target < 0) target = order.get(0);
        } else {
            for (int index : order) if (index < station) target = index;
            if (target < 0) target = order.get(order.size() - 1);
        }
        Log.i("SeniorLive", "Change channel " + direction + ": " + channels.get(target).id);
        tuneTo(target, 260);
    }

    private void tuneTo(int index, long delay) {
        clearDigits();
        guideSelection = null;
        if (index != station) previousStation = station;
        station = index;
        getPreferences(MODE_PRIVATE).edit().putString("channel", channel().id).apply();
        resumeLiveTime = resumeFromEnd = C.TIME_UNSET;
        resumeProgramme = -1;
        resumePosition = 0;
        requestPlay = true;
        lastAutoRetry = -1;
        // A companion intent can arrive before onStart has created the player.
        if (player != null) tune(delay);
    }

    // ---- Channel list -----------------------------------------------------------------------

    void openChannelList() {
        if (player == null || view.durationDialog != null) return;
        cancelKeys();
        channelShortcut.cancel();
        clearDigits();
        view.hide();
        listMine = mine.contains(channel().id);
        populateList(station);
        Log.i("SeniorLive", "Opened channels: " + (listMine ? "My channels" : "All channels"));
    }

    private void populateList(int focusStation) {
        long now = System.currentTimeMillis();
        listOrder = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) if (!listMine || mine.contains(channels.get(i).id)) listOrder.add(i);
        listRows.clear();
        int selection = 0;
        for (int index : listOrder) {
            LiveTvControls.Row row = new LiveTvControls.Row();
            row.index = index;
            row.name = channels.get(index).name;
            row.favourite = !listMine && mine.contains(channels.get(index).id);
            row.playing = index == station;
            fill(row, LiveGuide.knownNowNext(channels.get(index), now), now);
            if (channels.get(index).direct()) {
                row.now = channels.get(index).directLabel();
                row.loaded = true;
            }
            if (index <= focusStation) selection = listRows.size();
            listRows.add(row);
        }
        if (!listMine) {
            LiveTvControls.Row manage = new LiveTvControls.Row();
            manage.index = MANAGE_STREAMS;
            manage.name = "Add your own stream";
            manage.now = "Add or remove web streams";
            manage.loaded = true;
            listRows.add(manage);
        }
        listPainted = now;
        view.showList(listRows, selection, listMine, clock(now) + " local time");
        touchList();
        loadList(selection);
    }

    private void fill(LiveTvControls.Row row, LiveGuide.Programme[] pair, long now) {
        LiveGuide.Programme current = pair[0], following = pair[1];
        row.now = current == null ? "" : current.title;
        row.until = current == null ? "" : "bis " + clock(current.end);
        row.fraction = current == null || current.end <= current.start ? -1
                : (int) Math.max(0, Math.min(1000, (now - current.start) * 1000 / (current.end - current.start)));
        row.next = following == null ? "" : "Up next  " + clock(following.start) + "  ·  " + following.title;
    }

    private void loadList(int around) {
        int generation = ++listGeneration;
        for (Future<?> task : listTasks) task.cancel(true);
        listTasks.clear();
        // Nearest rows first: they are the ones on screen.
        List<LiveTvControls.Row> queue = new ArrayList<>(listRows);
        Collections.sort(queue, (a, b) -> Integer.compare(Math.abs(listRows.indexOf(a) - around), Math.abs(listRows.indexOf(b) - around)));
        for (LiveTvControls.Row row : queue) {
            if (row.index == MANAGE_STREAMS || channels.get(row.index).direct()) continue;
            LiveGuide.Channel item = channels.get(row.index);
            listTasks.add(listPool.submit(() -> {
                LiveGuide.Programme[] found;
                try { found = LiveGuide.nowNext(item, System.currentTimeMillis()); }
                catch (Exception unavailable) { found = null; }
                LiveGuide.Programme[] pair = found;
                main.post(() -> {
                    if (generation != listGeneration || !view.listVisible()) return;
                    row.loaded = true;
                    if (pair != null) fill(row, pair, System.currentTimeMillis());
                    main.removeCallbacks(paintList);
                    main.postDelayed(paintList, 70);
                });
            }));
        }
    }

    private void paintList() {
        if (view == null || !view.listVisible()) return;
        long now = System.currentTimeMillis();
        if (now - listPainted > 20000) {
            boolean stale = false;
            for (LiveTvControls.Row row : listRows) {
                if (row.index == MANAGE_STREAMS || channels.get(row.index).direct()) continue;
                LiveGuide.Programme[] pair = LiveGuide.knownNowNext(channels.get(row.index), now);
                if (pair[0] == null && !row.now.isEmpty()) stale = true;
                else if (pair[0] != null) fill(row, pair, now);
            }
            listPainted = now;
            if (stale) { loadList(view.listSelection()); }
        }
        view.refreshList(clock(now) + " local time");
    }

    private void touchList() {
        main.removeCallbacks(listTimeout);
        main.postDelayed(listTimeout, 45000);
    }

    void showChannelTab(boolean own) {
        if (!view.listVisible() || own == listMine) return;
        int focusStation = listRows.isEmpty() ? station : listRows.get(view.listSelection()).index;
        if (focusStation == MANAGE_STREAMS) focusStation = station;
        listMine = own;
        populateList(focusStation);
    }

    void chooseChannelRow(int position) {
        if (!view.listVisible() || position < 0 || position >= listRows.size()) return;
        int index = listRows.get(position).index;
        closeChannelList();
        if (index == MANAGE_STREAMS) {
            startActivity(new Intent(this, CustomStreamsActivity.class));
            return;
        }
        Log.i("SeniorLive", "Channel selected: " + channels.get(index).id);
        if (index == station && !failed && replay == null) { showStrip(false); return; }
        tuneTo(index, 0);
    }

    private void toggleFavourite() {
        if (listRows.isEmpty()) return;
        LiveTvControls.Row row = listRows.get(view.listSelection());
        if (row.index == MANAGE_STREAMS) return;
        String id = channels.get(row.index).id;
        boolean added = !mine.remove(id);
        if (added) mine.add(id);
        getPreferences(MODE_PRIVATE).edit().putString("mine", String.join(",", mine)).apply();
        Log.i("SeniorLive", "My channels " + (added ? "+" : "-") + " " + id);
        touchList();
        if (listMine) {
            int at = view.listSelection();
            populateList(at + 1 < listRows.size() ? listRows.get(at + 1).index : at > 0 ? listRows.get(at - 1).index : station);
        } else {
            row.favourite = added;
            view.refreshList(clock(System.currentTimeMillis()) + " local time");
        }
    }

    private void closeChannelList() {
        listOpeningPress = -1;
        ++listGeneration;
        for (Future<?> task : listTasks) task.cancel(true);
        listTasks.clear();
        listOkDown = false;
        main.removeCallbacks(listTimeout);
        main.removeCallbacks(paintList);
        if (view != null) view.hideList();
    }

    private boolean listKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_DOWN && event.getDownTime() == listOpeningPress) {
            if (event.getAction() == KeyEvent.ACTION_UP) listOpeningPress = -1;
            return true;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        int hold = ViewConfiguration.getLongPressTimeout();
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) { view.moveList(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1); touchList(); }
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) { view.moveList(key == KeyEvent.KEYCODE_CHANNEL_UP ? -6 : 6); touchList(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && event.getRepeatCount() == 0) showChannelTab(key == KeyEvent.KEYCODE_DPAD_LEFT);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (event.getRepeatCount() == 0) { listOkDown = true; listOkLong = false; }
                    else if (listOkDown && !listOkLong && (event.isLongPress() || event.getEventTime() - event.getDownTime() >= hold)) {
                        listOkLong = true;
                        toggleFavourite();
                    }
                } else if (listOkDown) {
                    listOkDown = false;
                    if (!listOkLong && !event.isCanceled()) {
                        if (event.getEventTime() - event.getDownTime() >= hold) toggleFavourite();
                        else chooseChannelRow(view.listSelection());
                    }
                }
                return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_GUIDE:
                if (!down && !event.isCanceled()) closeChannelList();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return true;
            default: return super.dispatchKeyEvent(event);
        }
    }

    // ---- Channel numbers --------------------------------------------------------------------

    private void digit(int value) {
        if (typed.length() >= 2) typed = "";
        typed += value;
        int index = Integer.parseInt(typed) - 1;
        view.digits(typed, index >= 0 && index < channels.size() ? channels.get(index).name : "No channel");
        main.removeCallbacks(commitDigits);
        main.postDelayed(commitDigits, typed.length() >= 2 ? 800 : 1700);
    }

    private void commitDigits() {
        int index = typed.isEmpty() ? -1 : Integer.parseInt(typed) - 1;
        clearDigits();
        if (index >= 0 && index < channels.size() && player != null) {
            Log.i("SeniorLive", "Channel number: " + channels.get(index).id);
            tuneTo(index, 0);
        }
    }

    private void clearDigits() {
        typed = "";
        main.removeCallbacks(commitDigits);
        if (view != null) view.digits(null, null);
    }

    private void openReplay(LiveGuide.Programme item, long position, boolean startPlaying) {
        Log.i("SeniorLive", "Catch-up skip " + channel().id + " " + clock(item.start));
        cancel(replayTask);
        int selection = ++mediaGeneration;
        view.message(item.title + " is opening…");
        main.removeCallbacks(hideMessage);
        replayTask = network.submit(() -> {
            try {
                LiveGuide.Programme linked = hasReplay(item) ? item : LiveGuide.archive(item);
                if (linked == null || !hasReplay(linked)) throw new java.io.IOException("No catch-up link");
                String url = LiveGuide.replay(linked);
                main.post(() -> {
                    if (player == null || selection != mediaGeneration) return;
                    replay = linked;
                    if (startPlaying) requestPlay = true;
                    resumeLiveTime = C.TIME_UNSET;
                    play(url, false, position);
                    showStrip(false);
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (player == null || selection != mediaGeneration) return;
                    if (guideSelection != null) {
                        error("The selected programme cannot currently be played from the library.");
                        return;
                    }
                    if (player.getMediaItemCount() == 0 && liveUrl != null) play(liveUrl, true, 0);
                    say("This programme is currently unavailable in the library.");
                });
            }
        });
    }

    private void toLive() {
        guideSelection = null;
        ++mediaGeneration;
        cancel(replayTask);
        requestPlay = true;
        resumeProgramme = -1;
        resumeLiveTime = C.TIME_UNSET;
        if (liveUrl == null || failed || player.getPlayerError() != null) { tune(); return; }
        if (replay != null || player.getMediaItemCount() == 0) {
            replay = null;
            play(liveUrl, true, 0);
        } else {
            player.seekToDefaultPosition();
            player.play();
        }
        showStrip(false);
    }

    private LiveGuide.Programme airing(long instant) {
        LiveGuide.Programme before = null;
        for (LiveGuide.Programme p : listings) {
            if (p.start <= instant && instant < p.end) return p;
            if (p.end <= instant) before = p;
            else if (p.start > instant) {
                // Published schedules leave short gaps for trailers. The earlier programme keeps the slot.
                if (before != null && p.start - before.end <= 20 * 60000L)
                    return new LiveGuide.Programme(before.title, before.start, p.start, before.provider, before.detail, before.eventId);
                break;
            }
        }
        return null;
    }

    private static boolean hasReplay(LiveGuide.Programme p) { return p.detail != null && !p.detail.isEmpty(); }

    private Timeline.Window window() {
        if (player == null || player.getCurrentTimeline().isEmpty()) return null;
        return player.getCurrentTimeline().getWindow(player.getCurrentMediaItemIndex(), new Timeline.Window());
    }

    private long screenTime() {
        Timeline.Window w = window();
        boolean preview = scrubPeriodTime != C.TIME_UNSET && seekable(w);
        long position = preview ? scrubPosition(w) : player.getCurrentPosition();
        if (replay != null) return replay.start + position;
        if (!requestPlay && !preview && pausedAirtime != C.TIME_UNSET) return pausedAirtime;
        long now = System.currentTimeMillis();
        // Some playlists carry a clock that is hours away from the wall clock. Then count back from the live edge.
        boolean trusted = w != null && w.windowStartTimeMs != C.TIME_UNSET
                && (!seekable(w) || Math.abs(now - w.windowStartTimeMs - w.getDurationMs()) < 5 * 60000L);
        if (trusted) return w.windowStartTimeMs + position;
        long offset = player.getCurrentLiveOffset();
        if (offset != C.TIME_UNSET && (w == null || w.windowStartTimeMs == C.TIME_UNSET)) return now - offset + (position - player.getCurrentPosition());
        return seekable(w) ? now - (w.getDurationMs() - bound(w, position)) : now;
    }

    private void jump(int direction) {
        Log.i("SeniorLive", "Short skip " + direction + ": " + channel().id);
        ++mediaGeneration;
        cancel(replayTask);
        if (failed) { if (direction > 0) toLive(); return; }
        long now = System.currentTimeMillis();
        LiveGuide.Programme seen = replay != null ? replay : airing(screenTime());
        LiveGuide.Programme next = null;
        if (seen != null) {
            for (LiveGuide.Programme p : listings) {
                if (direction < 0 && p.start < seen.start) next = p;
                if (direction > 0 && p.start > seen.start) { next = p; break; }
            }
        }
        if (next != null && next.start <= now) {
            if (direction > 0 && now < next.end) { toLive(); return; }
            jumpToProgramme(next, direction);
            return;
        }
        if (direction > 0 && replay != null && seen != null && next == null) { toLive(); return; }
        skipSeconds(direction);
    }

    private void jumpToProgramme(LiveGuide.Programme adjacent, int direction) {
        guideSelection = null;
        if (hasReplay(adjacent)) {
            openReplay(adjacent, 0, true);
            return;
        }
        final int selection = ++mediaGeneration;
        view.message(adjacent.title + " is being found…");
        main.removeCallbacks(hideMessage);
        replayTask = network.submit(() -> {
            LiveGuide.Programme resolved = null;
            try { resolved = LiveGuide.archive(adjacent); } catch (Exception unavailable) { }
            final LiveGuide.Programme linked = resolved;
            main.post(() -> {
                if (player == null || mediaGeneration != selection) return;
                if (linked != null && hasReplay(linked)) { openReplay(linked, 0, true); return; }
                view.message(null);
                // Some public streams also offer this programme inside the live timeshift window.
                Timeline.Window w = seekWindow();
                if (replay == null && w != null && w.windowStartTimeMs != C.TIME_UNSET) {
                    long target = adjacent.start - w.windowStartTimeMs;
                    if (target >= 0 && target < w.getDurationMs()) {
                        player.seekTo(target);
                        showStrip(false);
                        return;
                    }
                }
                skipSeconds(direction);
            });
        });
    }

    private void skipSeconds(int direction) {
        Timeline.Window w = seekWindow();
        if (w == null) { say("Seeking is unavailable on this channel."); return; }
        commitSeek(w, bound(w, cursor(w) + direction * 10000L));
    }

    private static boolean seekable(Timeline.Window w) {
        return w != null && !w.isPlaceholder && w.isSeekable && w.getDurationMs() > 0;
    }

    private Timeline.Window seekWindow() {
        Timeline.Window w = window();
        return !failed && seekable(w) ? w : null;
    }

    private static long bound(Timeline.Window w, long position) {
        return Math.max(0, Math.min(position, w.getDurationMs() - 1));
    }

    private boolean expiredSegment(PlaybackException problem, Timeline.Window w) {
        if (replay != null || w == null || !w.isLive()) return false;
        if (problem.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return true;
        if (!(w.manifest instanceof HlsManifest) || !firstFrame) return false;
        long segmentMs = ((HlsManifest) w.manifest).mediaPlaylist.targetDurationUs / 1000;
        if (player.getCurrentPosition() > segmentMs) return false;
        for (Throwable cause = problem.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return status == 404 || status == 410;
            }
        }
        return false;
    }

    // Sources can delete a segment between the playlist refresh and the HTTP request.
    private void reloadLiveWindow(Timeline.Window w, long target) {
        MediaItem item = player.getCurrentMediaItem();
        if (item == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean retry = target == 0 && lastWindowRecovery >= 0 && now - lastWindowRecovery < 15000;
        if (retry && triedDefaultRecovery) {
            error("The channel has no playable video at the moment.");
            return;
        }
        triedDefaultRecovery = retry;
        lastWindowRecovery = now;
        resumeLiveTime = resumeFromEnd = C.TIME_UNSET;
        if (!retry) {
            long end = w.getDurationMs() - 1;
            resumeFromEnd = target == 0 ? Long.MAX_VALUE : end - target;
            if (target > 0 && target < end && w.windowStartTimeMs != C.TIME_UNSET)
                resumeLiveTime = w.windowStartTimeMs + target;
        }
        Log.i("SeniorLive", "Live window refreshed " + channel().id + " zielMs=" + target);
        pausedPeriodTime = pausedAirtime = C.TIME_UNSET;
        player.setMediaItem(item);
        player.prepare();
        player.setPlayWhenReady(requestPlay);
    }

    private long cursor(Timeline.Window w) {
        if (!requestPlay && pausedPeriodTime != C.TIME_UNSET)
            return bound(w, pausedPeriodTime - w.getPositionInFirstPeriodMs());
        return bound(w, player.getCurrentPosition());
    }

    // A playlist refresh moves the window origin. Keep the preview tied to the content.
    private long scrubPosition(Timeline.Window w) {
        return scrubAtEnd ? w.getDurationMs() - 1 : bound(w, scrubPeriodTime - w.getPositionInFirstPeriodMs());
    }

    private String limitLabel(Timeline.Window w, long position) {
        if (position == 0) return replay == null ? "Start of available stream" : "Start of programme";
        if (position == w.getDurationMs() - 1) return replay == null ? "Back to live" : "End of programme";
        return null;
    }

    private void commitSeek(Timeline.Window w, long position) {
        long last = w.getDurationMs() - 1;
        String edge = position == 0 ? "start" : position == last ? (replay == null ? "live" : "end") : "inside";
        Log.i("SeniorLive", "Seek " + (replay == null ? "live " : "replay ") + channel().id
                + " pos=" + position + " min=0 max=" + last + " edge=" + edge);
        if (player.getPlayerError() != null) reloadLiveWindow(w, position);
        else {
            player.seekTo(position);
            if (!requestPlay) capturePause();
        }
        showStrip(false);
        String label = limitLabel(w, position);
        if (label == null) view.message(null);
        else say(label);
    }

    private static boolean centerKey(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void startLongPress() {
        if (heldKey == 0 || heldLong) return;
        if (centerKey(heldKey)) {
            heldLong = true;
            togglePlayback();
        } else startScrub();
    }

    private void startScrub() {
        if (heldKey == 0 || heldLong) return;
        heldLong = true;
        ++mediaGeneration;
        cancel(replayTask);
        Timeline.Window w = seekWindow();
        if (w == null) { say("Seeking is unavailable on this channel."); return; }
        scrubPeriodTime = w.getPositionInFirstPeriodMs() + cursor(w);
        advanceScrub();
    }

    private void advanceScrub() {
        if (!heldLong || scrubPeriodTime == C.TIME_UNSET) return;
        Timeline.Window w = seekWindow();
        if (w == null) {
            scrubPeriodTime = C.TIME_UNSET;
            say("Seeking is unavailable on this channel.");
            return;
        }
        long elapsed = SystemClock.uptimeMillis() - heldSince;
        long step = elapsed < 1800 ? 10000 : elapsed < 4500 ? 30000 : 60000;
        int direction = seekDirection(heldKey);
        long position = bound(w, scrubPosition(w) + direction * step);
        scrubPeriodTime = w.getPositionInFirstPeriodMs() + position;
        scrubAtEnd = direction > 0 && position == w.getDurationMs() - 1;
        showStrip(false);
        main.removeCallbacks(hideMessage);
        String label = limitLabel(w, position);
        if (label == null) label = direction < 0 ? "Rewind" : "Fast forward";
        if (replay != null || w.windowStartTimeMs != C.TIME_UNSET)
            label += "   ·   " + SCRUB_CLOCK.format(Instant.ofEpochMilli(screenTime())) + " local time";
        else if (position < w.getDurationMs() - 1)
            label += "   ·   " + ((w.getDurationMs() - position + 999) / 1000) + " sec behind live";
        view.message(label);
        main.postDelayed(advanceHold, 250);
    }

    private static int seekDirection(int key) {
        return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND ? -1 : 1;
    }

    private void cancelKeys() {
        heldKey = 0;
        heldLong = false;
        scrubPeriodTime = C.TIME_UNSET;
        scrubAtEnd = false;
        main.removeCallbacks(beginHold);
        main.removeCallbacks(advanceHold);
    }

    private void handleRemoteKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (heldKey != key) {
                cancelKeys();
                heldKey = key;
                heldSince = SystemClock.uptimeMillis();
                main.postDelayed(beginHold, ViewConfiguration.getLongPressTimeout());
            }
            if (event.isLongPress() || event.getRepeatCount() > 0
                    && SystemClock.uptimeMillis() - heldSince >= ViewConfiguration.getLongPressTimeout()) startLongPress();
            return;
        }
        if (event.getAction() != KeyEvent.ACTION_UP || heldKey != key) return;
        if (!heldLong && !event.isCanceled() && event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout())
            startLongPress();
        Timeline.Window w = scrubPeriodTime != C.TIME_UNSET ? seekWindow() : null;
        long target = w == null ? C.TIME_UNSET : scrubPosition(w);
        boolean wasLong = heldLong;
        cancelKeys();
        if (event.isCanceled()) return;
        if (wasLong) {
            if (w != null) {
                Log.i("SeniorLive", "Seek complete " + seekDirection(key) + ": " + channel().id + " auf " + target + " ms");
                commitSeek(w, target);
            }
            scheduleHide();
        } else if (centerKey(key)) {
            if (!view.isVisible()) toggleStrip();
            else if (view.selected() == LiveTvControls.BUTTON_PLAYBACK) playbackAction();
            else if (view.selected() == LiveTvControls.BUTTON_LIBRARY) openChannelLibrary();
            else stripAction();
        } else if (view.isVisible() && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            view.moveSelection(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            scheduleHide();
        } else jump(seekDirection(key));
    }

    private boolean handleMediaKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && code != KeyEvent.KEYCODE_MEDIA_PLAY
                && code != KeyEvent.KEYCODE_MEDIA_PAUSE && code != KeyEvent.KEYCODE_HEADSETHOOK) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && player != null) {
            if (lastMediaCode == code && lastMediaDown == event.getDownTime()) return true;
            lastMediaCode = code;
            lastMediaDown = event.getDownTime();
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY) setPlayback(true);
            else if (code == KeyEvent.KEYCODE_MEDIA_PAUSE) setPlayback(false);
            else togglePlayback();
        }
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (player == null) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) idle.interaction();
        if (handleMediaKey(event)) return true;
        if (view.listVisible()) return listKey(event);
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        if (key >= KeyEvent.KEYCODE_0 && key <= KeyEvent.KEYCODE_9) {
            if (pressed) digit(key - KeyEvent.KEYCODE_0);
            return true;
        }
        if (key >= KeyEvent.KEYCODE_NUMPAD_0 && key <= KeyEvent.KEYCODE_NUMPAD_9) {
            if (pressed) digit(key - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_GUIDE:
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openChannelList();
                return true;
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                if (pressed && previousStation >= 0) tuneTo(previousStation, 0);
                return true;
            case KeyEvent.KEYCODE_INFO:
                if (pressed) showStrip(true);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) cancelKeys();
                long pressedAt = event.getDownTime();
                boolean up = key == KeyEvent.KEYCODE_DPAD_UP;
                return channelShortcut.handle(event, () -> switchStation(up ? 1 : -1), () -> {
                    if (up) {
                        Intent programme = TvProgrammeActivity.intent(this, channel().id)
                                .putExtra("openingUpDownTime", pressedAt);
                        startActivity(programme);
                    } else {
                        openChannelList();
                        listOpeningPress = pressedAt;
                    }
                });
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                handleRemoteKey(event);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    cancelKeys();
                    switchStation(key == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                }
                return true;
            default: return super.dispatchKeyEvent(event);
        }
    }

    void togglePlayback() { setPlayback(!requestPlay); }

    void playbackAction() {
        if (failed) retryPlayback();
        else togglePlayback();
    }

    void openChannelLibrary() {
        if (player == null || view.durationDialog != null) return;
        if (!LiveLibraryActivity.open(this, channel())) {
            libraryAvailable = LiveLibraryActivity.available(this, channel());
            updateStrip();
            say("This channel has no on-demand library.");
        }
    }

    private void capturePause() {
        if (player == null) return;
        long position = player.getCurrentPosition();
        pausedPosition = Math.max(0, position);
        Timeline.Window w = seekWindow();
        pausedPeriodTime = w == null ? C.TIME_UNSET : w.getPositionInFirstPeriodMs() + position;
        pausedAirtime = replay == null && w != null && w.windowStartTimeMs != C.TIME_UNSET
                ? w.windowStartTimeMs + position : C.TIME_UNSET;
    }

    private void setPlayback(boolean play) {
        if (player == null || isFinishing()) return;
        idle.interaction();
        if (failed) { if (play) retryPlayback(); return; }
        if (play == requestPlay) return;
        long anchor = pausedAirtime;
        long pausedAt = pausedPosition;
        boolean expired = false;
        if (!play) capturePause();
        requestPlay = play;
        if (play) {
            Timeline.Window w = seekWindow();
            boolean anchored = replay == null && pausedPeriodTime != C.TIME_UNSET && w != null;
            long position = w == null ? 0 : cursor(w);
            if (anchored) {
                long offset = pausedPeriodTime - w.getPositionInFirstPeriodMs();
                position = bound(w, offset);
                expired = position != offset;
                if (player.getPlayerError() == null && (expired || Math.abs(position - player.getCurrentPosition()) > 700))
                    player.seekTo(position);
            }
            if (player.getPlayerError() != null) {
                if (w != null && replay == null) reloadLiveWindow(w, position);
                else { player.seekToDefaultPosition(); player.prepare(); }
            }
            pausedPeriodTime = pausedAirtime = C.TIME_UNSET;
        }
        player.setPlayWhenReady(play);
        Log.i("SeniorLive", (play ? "Resume" : "Pause") + ": " + channel().id
                + " positionMs=" + player.getCurrentPosition() + " pausePositionMs=" + (play ? pausedAt : pausedPosition)
                + " sendezeitMs=" + (play ? anchor : pausedAirtime) + " fensterAbgelaufen=" + expired);
        showStrip(!play);
        if (!play) view.select(LiveTvControls.BUTTON_PLAYBACK);
        if (expired) say("Your paused position has expired. Playback has resumed at the start of the available stream.");
        publishPlayback();
    }

    private void publishPlayback() {
        if (mediaSession == null || player == null) return;
        int state;
        if (failed) state = PlaybackState.STATE_ERROR;
        else if (!requestPlay) state = PlaybackState.STATE_PAUSED;
        else if (player.isPlaying()) state = PlaybackState.STATE_PLAYING;
        else if (player.getPlaybackState() == Player.STATE_ENDED) state = PlaybackState.STATE_STOPPED;
        else state = PlaybackState.STATE_BUFFERING;
        mediaSession.state(state, requestPlay ? player.getCurrentPosition() : pausedPosition);
        LiveGuide.Programme item = replay == null ? airing(screenTime()) : replay;
        mediaSession.metadata(channel().name, item == null ? channel().name : item.title);
    }

    void toggleStrip() {
        if (view.listVisible()) return;
        if (view.isExpanded()) view.hide(); else showStrip(true);
    }

    private void showStrip(boolean expanded) {
        updateStrip();
        view.show(expanded);
        scheduleHide();
    }

    private void scheduleHide() {
        main.removeCallbacks(hideStrip);
        if (heldKey == 0) main.postDelayed(hideStrip, view.isExpanded() ? 10000 : 7500);
    }

    private void updateStrip() {
        if (view == null || player == null) return;
        long stamp = screenTime(), now = System.currentTimeMillis();
        LiveGuide.Programme item = replay == null ? airing(stamp) : replay;
        int kind = !requestPlay ? LiveTvControls.PAUSED : replay != null ? LiveTvControls.ARCHIVE
                : firstFrame && now - stamp > 60000 ? LiveTvControls.SHIFTED : LiveTvControls.LIVE;
        String info = item != null ? item.title : channel().direct() ? channel().directLabel() : "Live guide";
        String from = item == null ? "" : clock(item.start), until = item == null ? "" : clock(item.end);
        if (item != null && kind == LiveTvControls.LIVE && item.end > now)
            until += "   ·   Remaining " + Math.max(1, (item.end - now + 30000) / 60000) + " Min";
        int fraction = item == null || item.end <= item.start ? -1 : (int)Math.max(0, Math.min(1000, (stamp-item.start)*1000/(item.end-item.start)));
        String following = "";
        if (item != null && replay == null) {
            for (LiveGuide.Programme p : listings) {
                if (p.start >= item.end - 1000) { following = clock(p.start) + "   " + p.title; break; }
            }
        }
        boolean past = item != null && item.end <= now;
        String action = "Record";
        if (replay != null || past) action = item.start == bookmarkUnavailable ? "Record from now" : "Save programme";
        view.update(station+1, channel().name, kind, info, from, until, fraction, following, clock(now) + " local time",
                action, !requestPlay, failed, libraryAvailable);
    }

    static String clock(long instant) { return CLOCK.format(Instant.ofEpochMilli(instant)); }

    private void say(String text) {
        main.removeCallbacks(hideMessage);
        view.message(text);
        main.postDelayed(hideMessage, 2600);
    }

    private void error(String message) {
        failed = true;
        pendingDuration = false;
        view.busy(false);
        view.message(message + "  Press OK to reload.");
        main.removeCallbacks(hideMessage);
        showStrip(false);
        idle.refresh();
    }

    void stripAction() {
        if (player == null) return;
        if (failed) retryPlayback();
        else chooseDuration();
    }

    private void retryPlayback() {
        if (guideSelection == null) { toLive(); return; }
        resumeProgramme = guideSelection.start;
        tune();
    }

    private void chooseDuration() {
        if (player == null || view.durationDialog != null) return;
        if (channel().direct()) { say("Recording is not available for this stream."); return; }
        if (guideSelection != null && replay == null) { say("Opening the selected programme."); return; }
        if (replay != null) {
            if (!firstFrame) { say("The programme is still opening."); return; }
            rememberArchive(replay);
            return;
        }
        if (liveUrl == null) {
            pendingDuration = true;
            main.removeCallbacks(hideMessage);
            view.message("Loading recording options…");
            return;
        }
        final LiveGuide.Channel current = channel();
        LiveGuide.Programme displayed = airing(screenTime());
        if (displayed != null && displayed.end <= System.currentTimeMillis() && displayed.start != bookmarkUnavailable) {
            checkForArchive(displayed);
            return;
        }
        final LiveGuide.Programme onAir = airing(System.currentTimeMillis());
        final String source = liveUrl;
        main.removeCallbacks(hideStrip);
        view.durations(current.name + "   ·   Live now", onAir == null ? "" : onAir.title, onAir == null ? 0 : onAir.end, length -> {
            if (length <= 0) { say("This programme has ended."); return; }
            startRecording(current, source, length);
        });
    }

    private boolean startRecording(LiveGuide.Channel current, String source, long length) {
        try {
            Recordings.start(this, current, source, airing(System.currentTimeMillis()), length);
            say("Added to Recordings.");
            return true;
        } catch (RuntimeException e) {
            say(e.getMessage() == null ? "Could not start recording." : e.getMessage());
            return false;
        }
    }

    private void checkForArchive(LiveGuide.Programme item) {
        cancel(replayTask);
        final int generation = ++mediaGeneration;
        main.removeCallbacks(hideMessage);
        view.message("Checking the library…");
        replayTask = network.submit(() -> {
            LiveGuide.Programme found = null;
            try {
                LiveGuide.Programme p = hasReplay(item) ? item : LiveGuide.archive(item);
                if (p != null && hasReplay(p) && RecordingHls.verifyArchive(LiveGuide.replay(p))) found = p;
            } catch (Exception unavailable) { }
            final LiveGuide.Programme linked = found;
            main.post(() -> {
                if (player == null || generation != mediaGeneration) return;
                if (linked != null) rememberArchive(linked);
                else {
                    bookmarkUnavailable = item.start;
                    showStrip(true);
                    say("There is no catch-up link for this earlier programme.");
                }
            });
        });
    }

    private void rememberArchive(LiveGuide.Programme item) {
        try {
            Recordings.remember(this, channel(), item);
            say("Programme saved to Recordings.");
        } catch (RuntimeException e) {
            say("Could not save the programme.");
        }
    }

    void durationDismissed() { if (player != null) showStrip(true); }

    @Override public void onBackPressed() {
        pendingDuration = false;
        if (view != null && view.listVisible()) { closeChannelList(); return; }
        if (view != null && view.isExpanded()) { view.hide(); return; }
        setResult(RESULT_OK);
        finish();
    }

    private boolean playerIdle() { return player != null && (failed || firstFrame && !player.getPlayWhenReady()); }

    @Override public void onUserInteraction() { super.onUserInteraction(); idle.interaction(); }
    @Override public void onResume() {
        super.onResume();
        resumedPlayer = this;
        idle.resume();
    }
    @Override public void onPause() {
        if (resumedPlayer == this) resumedPlayer = null;
        idle.pause(); cancelKeys(); channelShortcut.cancel(); super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) {
            if (heldLong && view != null) view.message(null);
            cancelKeys();
            channelShortcut.cancel();
        }
        idle.refresh();
    }

    private void remember() {
        if (player == null || player.getMediaItemCount() == 0) return;
        requestPlay = player.getPlayWhenReady();
        resumeProgramme = replay == null ? -1 : replay.start;
        resumePosition = replay == null ? 0 : player.getCurrentPosition();
        resumeLiveTime = C.TIME_UNSET;
        Timeline.Window w = seekWindow();
        if (replay == null && firstFrame && w != null && w.windowStartTimeMs != C.TIME_UNSET) {
            long when = screenTime();
            if (System.currentTimeMillis() - when > 30000 || !requestPlay) resumeLiveTime = when;
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        if (channels != null && !channels.isEmpty()) {
            long previousRequest = resumeProgramme;
            long previousLiveTime = resumeLiveTime;
            remember();
            out.putString("channel", channel().id);
            out.putLong("programme", guideSelection != null ? guideSelection.start : resumeProgramme);
            out.putLong("position", resumePosition);
            out.putLong("airtime", resumeLiveTime);
            out.putBoolean("playing", requestPlay);
            // An instance-state snapshot must not change an in-flight tune.
            resumeProgramme = previousRequest;
            resumeLiveTime = previousLiveTime;
        }
        super.onSaveInstanceState(out);
    }

    @Override protected void onStop() {
        remember();
        if (mediaSession != null) { mediaSession.close(); mediaSession = null; }
        ++tuneGeneration;
        ++mediaGeneration;
        cancelKeys();
        cancel(streamTask);
        cancel(guideTask);
        cancel(replayTask);
        if (view != null && view.durationDialog != null) view.durationDialog.dismiss();
        closeChannelList();
        clearDigits();
        main.removeCallbacksAndMessages(null);
        if (view != null) view.busy(false);
        if (player != null) {
            ExoPlayer old = player;
            player = null;
            old.release();
            view.surface.setKeepScreenOn(false);
        }
        super.onStop();
    }

    private static void cancel(Future<?> task) { if (task != null) task.cancel(true); }

    @Override protected void onDestroy() {
        SeniorPlaybackHost.playback(this, false);
        network.shutdownNow();
        guides.shutdownNow();
        listPool.shutdownNow();
        super.onDestroy();
    }
}

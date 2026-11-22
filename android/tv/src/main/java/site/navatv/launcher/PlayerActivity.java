package site.navatv.launcher;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One private player for live TV and on-demand broadcasts from the station catalogue. */
public final class PlayerActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService resolver = Executors.newSingleThreadExecutor();
    private final DirectionShortcut shortcut = new DirectionShortcut();
    private ExoPlayer player;
    private Future<?> request;
    private PlayerChrome chrome;
    private FrameLayout root, controls;
    private SurfaceView surface;
    private LinearLayout timeline, notice;
    private TextView station, title, author, elapsedTime, endTime, status;
    private PlayerChrome.ActionButton play, rewind, forward;
    private ProgressBar progress, spinner;
    private String itemId;
    private String recordingId;
    private boolean live, failed, ended, started, firstFrame, wantPlay = true;
    private boolean controlsShown;
    private String provider = "";
    String companionProvider() { return provider.toLowerCase(Locale.ROOT); }
    String companionTitle() { return title == null ? "" : title.getText().toString(); }
    boolean fromRecordings() { return recordingId != null || getIntent().getBooleanExtra("fromRecordings", false); }
    private String terminalMessage;
    private long position;
    private long liveProgramTime = C.TIME_UNSET;
    private final IdleTimer idle = new IdleTimer(this, this::isIdlePlayback);
    private int generation;
    private float aspect = 16f/9;
    private int heldSeekSeconds;
    private final Runnable repeatSeek = new Runnable() {
        @Override public void run() {
            if (heldSeekSeconds == 0 || player == null) return;
            seek(heldSeekSeconds);
            ui.postDelayed(this, 400);
        }
    };
    private final Runnable hide = () -> {
        if (player != null && player.isPlaying() && firstFrame) hideControls();
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (controlsShown) updateProgress();
            ui.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        SeniorPlaybackHost.playback(this, true);
        MediathekService.initialize(getApplicationContext());
        itemId = getIntent().getStringExtra("id");
        // Keep the original programme reference in the activity intent for process restoration.
        if (itemId == null && getIntent().hasExtra("programme")) {
            itemId = MediathekService.acceptProgramme(getIntent().getStringExtra("provider"),
                    getIntent().getStringExtra("programme"), getIntent().getStringExtra("title"));
            if (itemId == null) android.widget.Toast.makeText(this, "Invalid programme.", android.widget.Toast.LENGTH_SHORT).show();
        }
        recordingId = getIntent().getStringExtra("recording");
        if (recordingId != null && Recordings.localUri(this,recordingId) != null) {
            Recordings.playing(this,recordingId);
        } else if (itemId == null || !itemId.matches("[A-Za-z0-9_-]{1,128}")) { finish(); return; }
        if (saved != null) {
            position = saved.getLong("position");
            wantPlay = saved.getBoolean("playing", true);
            liveProgramTime = saved.getLong("liveProgramTime",C.TIME_UNSET);
            ended = saved.getBoolean("ended");
            terminalMessage = saved.getString("terminalError");
            failed = terminalMessage != null;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        buildUi();
        if (saved != null) {
            live = saved.getBoolean("live");
            setMetadata(saved.getString("channel", ""), saved.getString("title", ""), saved.getString("author", ""));
        } else {
            String source = getIntent().getStringExtra("provider");
            setMetadata(source == null ? "" : source.toUpperCase(Locale.ROOT), getIntent().getStringExtra("title"), "");
        }
    }
    @Override protected void onStart() {
        super.onStart();
        if (isFinishing()) return;
        started = true;
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (!started || failed) return;
                if (state == Player.STATE_BUFFERING) setNotice(live ? "Loading live TV…" : "Loading programme…", true);
                if (state == Player.STATE_READY && firstFrame) notice.setVisibility(View.GONE);
                if (state == Player.STATE_ENDED) {
                    ended = true;
                    wantPlay = false;
                    position = Math.max(player.getCurrentPosition(),player.getDuration());
                    notice.setVisibility(View.GONE);
                    showControls();
                }
                idle.refresh();
                android.util.Log.i("Libraries", "Playback state=" + state);
                updateProgress();
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                surface.setKeepScreenOn(playing);
                if (playing) scheduleHide(); else showControls();
                idle.refresh();
                updateProgress();
            }
            @Override public void onPlayWhenReadyChanged(boolean willPlay, int reason) {
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) wantPlay = willPlay;
                updateProgress();
                idle.refresh();
            }
            @Override public void onTimelineChanged(Timeline timeline, int reason) { restoreLivePosition(); }
            @Override public void onRenderedFirstFrame() {
                firstFrame = true;
                idle.refresh();
                notice.setVisibility(View.GONE);
                android.util.Log.i("Libraries", "First video frame");
                scheduleHide();
            }
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) { aspect = size.width * size.pixelWidthHeightRatio / size.height; resizeVideo(); }
            }
            @Override public void onPlayerError(PlaybackException error) { showFailure(error); }
        });
        if (failed || ended) {
            if (failed) setNotice(terminalMessage, false); else setNotice("Programme ended", false);
            showControls(); updateProgress(); idle.refresh();
        } else load();
        ui.post(tick);
    }
    private void load() {
        int current = ++generation;
        if (request != null) request.cancel(true);
        failed = false; ended = false; terminalMessage = null; firstFrame = false;
        idle.refresh();
        setNotice("Loading programme…", true);
        showControls();
        request = resolver.submit(() -> {
            try {
                MediathekService.Playback item;
                if (recordingId != null) {
                    android.net.Uri file = Recordings.localUri(this,recordingId);
                    org.json.JSONObject row = Recordings.get(this,recordingId);
                    if (file == null || row == null) throw new java.io.IOException("Recording not found.");
                    MediathekService.Entry entry = new MediathekService.Entry("Recording", row.optString("title"),
                            row.optString("station"), "", file.toString(), "", "", 0, false);
                    item = new MediathekService.Playback(file.toString(),entry,"application/x-mpegURL");
                } else {
                    item = MediathekService.resolve(itemId);
                    if (item == null || !MediathekService.isTrustedStreamUrl(item.url)) throw new java.io.IOException();
                }
                ui.post(() -> {
                    if (!started || current != generation) return;
                    live = item.live;
                    String source = recordingId == null ? item.channel : item.author;
                    setMetadata(source, item.title, recordingId == null ? item.author : "");
                    if ("ARTE".equals(item.channel)) {
                        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                                .buildUpon().setPreferredAudioLanguage("de").build());
                    }
                    MediaItem.Builder media = new MediaItem.Builder().setUri(item.url);
                    if(recordingId!=null){
                        org.json.JSONObject recording=Recordings.get(this,recordingId);
                        if(recording!=null)media.setClippingConfiguration(Recordings.clip(this,recordingId,recording));
                    }
                    String mime = item.mimeType == null ? "" : item.mimeType.toLowerCase(Locale.ROOT);
                    if (mime.contains("mpegurl")) media.setMimeType(MimeTypes.APPLICATION_M3U8);
                    else if (mime.equals("video/mp4")) media.setMimeType(MimeTypes.VIDEO_MP4);
                    player.setMediaItem(media.build());
                    player.prepare();
                    if (!live && position > 0) player.seekTo(position);
                    player.setPlayWhenReady(wantPlay);
                    updateProgress();
                });
            } catch (Exception error) {
                ui.post(() -> { if (started && current == generation) showFailure(error); });
            }
        });
    }
    private void showFailure(Throwable error) {
        failed = true;
        ended = false;
        wantPlay = false;
        idle.refresh();
        String known = MediathekService.playbackErrorMessage(error);
        Throwable cause = error;
        for (int depth = 0; known == null && cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                int code = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)cause).responseCode;
                if (code == 403 || code == 451) known = "The broadcaster has restricted playback. The programme may be limited by region or airtime.";
                if (code == 404 || code == 410) known = "The broadcaster no longer offers this programme.";
            }
        }
        terminalMessage = known == null ? "This programme cannot be played right now." : known;
        setNotice(terminalMessage, false);
        android.util.Log.w("Libraries", "Playback error " + error.getClass().getSimpleName());
        surface.setKeepScreenOn(false);
        showControls();
        updateProgress();
    }
    private void setNotice(String message, boolean loading) {
        status.setText(message);
        spinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        notice.setVisibility(View.VISIBLE);
    }

    private void setMetadata(String source, String programme, String producer) {
        provider = source == null ? "" : source;
        title.setText(programme);
        author.setText(producer);
        boolean showAuthor = !TextUtils.isEmpty(producer) && !producer.equalsIgnoreCase(provider)
                && !producer.equalsIgnoreCase(programme);
        author.setVisibility(showAuthor ? View.VISIBLE : View.GONE);
    }

    private static void setText(TextView label, String value) {
        if (!TextUtils.equals(label.getText(), value)) label.setText(value);
    }

    private void updateProgress() {
        if (player == null) return;
        String action = failed ? "Try again" : ended ? "Restart" : wantPlay ? "Pause" : "Playback";
        PlayerChrome.Icon icon = failed || ended ? PlayerChrome.Icon.RETRY : wantPlay ? PlayerChrome.Icon.PAUSE : PlayerChrome.Icon.PLAY;
        play.update(action, icon, true);
        long duration = ended ? position : player.getDuration(), current = ended ? position : Math.max(0,player.getCurrentPosition());
        boolean seekable = canSeek();
        rewind.update("Back 10 seconds", PlayerChrome.Icon.REWIND, seekable);
        forward.update("Forward 10 seconds", PlayerChrome.Icon.FORWARD, seekable);
        String state = ended ? "ENDED" : !wantPlay && !failed ? "PAUSED" : live ? "LIVE" : "";
        String badge = provider.isEmpty() || state.isEmpty() ? provider + state : provider + "  ·  " + state;
        setText(station, badge);
        station.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
        setText(chrome.clock, LiveTvActivity.clock(System.currentTimeMillis()) + " local time");
        timeline.setVisibility(!failed && (duration > 0 || live) ? View.VISIBLE : View.GONE);
        progress.setVisibility(duration > 0 ? View.VISIBLE : View.INVISIBLE);
        progress.setProgress(duration > 0 ? (int)Math.min(1000,current * 1000 / duration) : 0);
        if (live) {
            long offset = player.getCurrentLiveOffset();
            String behind = offset != C.TIME_UNSET && offset > 15000 ? "−" + stamp(offset) : "";
            setText(elapsedTime, behind);
            setText(endTime, "Live");
            timeline.setContentDescription(behind.isEmpty() ? "Live" : behind + " until live");
        } else if (duration > 0) {
            String elapsed = stamp(Math.min(current, duration)), total = stamp(duration);
            setText(elapsedTime, elapsed);
            setText(endTime, total);
            timeline.setContentDescription(elapsed + " / " + total);
        }
    }
    private boolean canSeek() {
        if (failed || ended || player == null || player.getPlaybackState() == Player.STATE_IDLE) return false;
        Timeline timeline = player.getCurrentTimeline();
        if (timeline.isEmpty()) return false;
        Timeline.Window window = timeline.getWindow(player.getCurrentMediaItemIndex(), new Timeline.Window());
        return !window.isPlaceholder && window.isSeekable && window.getDurationMs() > 0;
    }
    private static String stamp(long millis) {
        long s = Math.max(0,millis/1000);
        return s >= 3600 ? String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,s/60%60,s%60) : String.format(Locale.ROOT,"%d:%02d",s/60,s%60);
    }
    private void toggle() {
        if (player == null) return;
        if (failed || ended) {
            if (ended) position = 0;
            wantPlay = true; load(); return;
        }
        wantPlay = !wantPlay;
        player.setPlayWhenReady(wantPlay);
        showControls(); updateProgress();
    }
    private void seek(int seconds) {
        if (canSeek()) {
            long target = Math.max(0, player.getCurrentPosition() + seconds * 1000L);
            if (player.getDuration() > 0) target = Math.min(target,player.getDuration()-1);
            player.seekTo(target);
        }
        showControls(); updateProgress();
    }
    private void scheduleHide() {
        ui.removeCallbacks(hide);
        if (player != null && player.isPlaying() && firstFrame && !failed) ui.postDelayed(hide, 5000);
    }
    private void showControls() {
        if (!controlsShown) {
            controlsShown = true;
            controls.animate().cancel();
            controls.setVisibility(View.VISIBLE);
            chrome.select(0, true);
            updateProgress();
            controls.animate().alpha(1f).setDuration(180).start();
        }
        scheduleHide();
    }

    private void hideControls() {
        if (!controlsShown) return;
        controlsShown = false;
        root.requestFocus();
        controls.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (!controlsShown) controls.setVisibility(View.GONE);
        }).start();
    }
    private boolean isIdlePlayback() {
        return started && player != null && (failed || ended || (firstFrame && !wantPlay && !player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_READY));
    }
    private long currentLiveProgramTime() {
        if (!live || wantPlay || player == null || !player.isCurrentMediaItemSeekable() || player.getCurrentTimeline().isEmpty()) return C.TIME_UNSET;
        Timeline.Window window = player.getCurrentTimeline().getWindow(player.getCurrentMediaItemIndex(),new Timeline.Window());
        return window.windowStartTimeMs == C.TIME_UNSET ? C.TIME_UNSET : window.windowStartTimeMs + player.getCurrentPosition();
    }
    private void restoreLivePosition() {
        if (!live || liveProgramTime == C.TIME_UNSET || player == null || player.getCurrentTimeline().isEmpty()) return;
        Timeline.Window window = player.getCurrentTimeline().getWindow(player.getCurrentMediaItemIndex(),new Timeline.Window());
        if (window.isPlaceholder || window.windowStartTimeMs == C.TIME_UNSET || !window.isSeekable) return;
        long current = liveProgramTime - window.windowStartTimeMs;
        liveProgramTime = C.TIME_UNSET;
        if (current >= 0 && window.getDurationMs() > current) player.seekTo(current);
    }
    @Override protected void onResume() { super.onResume(); idle.resume(); }
    @Override protected void onPause() { idle.pause(); cancelPress(); super.onPause(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) cancelPress();
        idle.refresh();
    }
    @Override public void onUserInteraction() { super.onUserInteraction(); idle.interaction(); }
    @Override public void onBackPressed() {
        // A deliberate Back restores the selected card. HOME instead opens the channel picker.
        setResult(RESULT_OK);
        finish();
    }
    private void cancelPress() {
        shortcut.cancel();
        heldSeekSeconds = 0;
        ui.removeCallbacks(repeatSeek);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (player == null) return super.dispatchKeyEvent(event);
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (down) idle.interaction();

        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return shortcut.handle(event, () -> {
                    if (!controlsShown) showControls();
                    else if (chrome.selected() == 1) seek(-10);
                    else if (chrome.selected() == 2) seek(10);
                    else toggle();
                }, this::toggle);
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int direction = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                boolean handled = shortcut.handle(event, () -> {
                    if (controlsShown) {
                        chrome.moveSelection(direction, true);
                        scheduleHide();
                    } else seek(direction * 10);
                }, () -> {
                    heldSeekSeconds = direction * 10;
                    repeatSeek.run();
                });
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    heldSeekSeconds = 0;
                    ui.removeCallbacks(repeatSeek);
                }
                return handled;
            }
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && event.getRepeatCount() == 0) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) seek(key == KeyEvent.KEYCODE_MEDIA_REWIND ? -10 : 10);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down && (failed || ended || !wantPlay)) toggle();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && wantPlay) toggle();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_INFO:
                if (down) showControls();
                return true;
            default: return super.dispatchKeyEvent(event);
        }
    }
    private void resizeVideo() {
        int width = root.getWidth(), height = root.getHeight();
        if (width == 0 || height == 0 || aspect <= 0) return;
        if ((float)width/height > aspect) width = Math.round(height * aspect); else height = Math.round(width/aspect);
        surface.setLayoutParams(new FrameLayout.LayoutParams(width,height,Gravity.CENTER));
    }
    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusableInTouchMode(true);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB) -> {
            if (r-l != oldR-oldL || b-t != oldB-oldT) resizeVideo();
        });
        root.setOnClickListener(v -> {
            if (controlsShown && player != null && player.isPlaying()) hideControls();
            else showControls();
        });
        controls = new FrameLayout(this);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        chrome = new PlayerChrome(this, controls, recordingId == null ? "Library" : "Recording");

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        controls.addView(details, chrome.at(-1, -2, Gravity.TOP | Gravity.START, 56, 50, 128, 0));
        station = chrome.label(11, 770, PlayerChrome.LAVENDER);
        station.setLetterSpacing(.14f);
        details.addView(station);
        title = chrome.label(37, 800, PlayerChrome.PEARL);
        title.setLetterSpacing(-.045f);
        title.setPadding(0, chrome.dp(2), 0, 0);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(chrome.dp(4), 0, chrome.dp(1), 0x65000000);
        details.addView(title);
        author = chrome.label(18, 650, PlayerChrome.PEARL);
        author.setSingleLine(true);
        author.setEllipsize(TextUtils.TruncateAt.END);
        author.setShadowLayer(chrome.dp(3), 0, chrome.dp(1), 0x80000000);
        details.addView(author);

        timeline = new LinearLayout(this);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(timeline, chrome.at(-1, -2, Gravity.BOTTOM, 54, 0, 54, 86));
        elapsedTime = chrome.label(14, 650, PlayerChrome.PEARL);
        timeline.addView(elapsedTime);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(PlayerChrome.LAVENDER));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x887d7991));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(0, chrome.dp(4), 1);
        bar.setMargins(chrome.dp(12), 0, chrome.dp(12), 0);
        timeline.addView(progress, bar);
        endTime = chrome.label(14, 650, PlayerChrome.PEARL);
        timeline.addView(endTime);

        play = chrome.button("Pause", PlayerChrome.Icon.PAUSE, this::toggle);
        rewind = chrome.button("Back 10 seconds", PlayerChrome.Icon.REWIND, () -> seek(-10));
        forward = chrome.button("Forward 10 seconds", PlayerChrome.Icon.FORWARD, () -> seek(10));
        chrome.setActions(0, play, rewind, forward);
        controls.setVisibility(View.GONE);
        controls.setAlpha(0f);

        notice = new LinearLayout(this);
        notice.setOrientation(LinearLayout.VERTICAL);
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(chrome.dp(22), chrome.dp(14), chrome.dp(22), chrome.dp(14));
        notice.setBackground(chrome.shape(0xf01a1b32, 14, PlayerChrome.VIOLET_EDGE));
        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(PlayerChrome.LAVENDER));
        notice.addView(spinner, new LinearLayout.LayoutParams(chrome.dp(30), chrome.dp(30)));
        status = chrome.label(18, 650, PlayerChrome.PEARL);
        status.setGravity(Gravity.CENTER);
        status.setMaxWidth(chrome.dp(420));
        notice.addView(status);
        root.addView(notice, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(root);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putLong("position", player == null || ended || player.getMediaItemCount() == 0 ? position : player.getCurrentPosition());
        out.putBoolean("ended",ended);
        if (title != null) {
            out.putString("channel", provider);
            out.putString("title", title.getText().toString());
            out.putString("author", author.getText().toString());
            out.putBoolean("live", live);
        }
        out.putString("terminalError",terminalMessage);
        out.putBoolean("playing", wantPlay);
        long stamp = currentLiveProgramTime();
        out.putLong("liveProgramTime",stamp == C.TIME_UNSET ? liveProgramTime : stamp);
        super.onSaveInstanceState(out);
    }
    @Override protected void onStop() {
        started = false; generation++;
        if (request != null) request.cancel(true);
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            if (!live && !ended && player.getMediaItemCount() > 0) position = player.getCurrentPosition();
            long stamp = currentLiveProgramTime();
            if (stamp != C.TIME_UNSET) liveProgramTime = stamp;
            player.release(); player = null;
        }
        if (surface != null) surface.setKeepScreenOn(false);
        super.onStop();
    }
    @Override protected void onDestroy() {
        SeniorPlaybackHost.playback(this, false);
        resolver.shutdownNow();
        if (recordingId != null) Recordings.playing(this,null);
        super.onDestroy();
    }
}

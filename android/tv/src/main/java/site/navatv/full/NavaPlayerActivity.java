package site.navatv.full;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import site.navatv.launcher.CompactPlaybackControls;
import site.navatv.launcher.PlaybackLoadingMark;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.source.sabr.SabrMediaSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrBackoffError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrPoTokenError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrReloadPlayerError;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.video.VideoListener;
import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/** Native OpenNava player: custom controls and source playback, never opens SmartTube's UI. */
public final class NavaPlayerActivity extends Activity {
    public static final String EXTRA_VIDEO_ID = "video_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_AUTHOR = "author";
    public static final String EXTRA_POSITION_MS = "position_ms";
    private static final String EXTRA_PROVIDER = "provider";
    private static final String EXTRA_MEDIATHEK_ID = "mediathek_id";
    private static final int ACCENT = Color.rgb(206, 241, 218);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SimpleExoPlayer player;
    private ServiceManager service;
    private NavaMediaSources sources;
    private Disposable loading;
    private static DefaultBandwidthMeter youtubeBandwidth;
    private DefaultTrackSelector trackSelector;
    private boolean sourcePrepared, platformNetwork, sabrSource;
    private long startupMs, preparedMs, lastAdvanceMs, lastPosition, lastBuffered, waitingSinceMs;
    private long healthySinceMs, previousRebufferMs;
    private int sabrBitrateLimit = Integer.MAX_VALUE;
    private static final long STALLED_MS = 18_000, MAX_BUFFER_WAIT_MS = 45_000;
    private Runnable pendingRecovery;
    private FrameLayout root;
    private VideoFrame videoFrame;
    private SurfaceView surface;
    private TextView brand, liveBadge, title, author, status, play, rewind, forward, time;
    private LinearLayout controls, loadingPanel;
    private CompactPlaybackControls compact;
    private View spinner;
    private SeekBar progress;
    private String videoId;
    private String mediathekId, providerLabel = "YOUTUBE";
    private boolean mediathekMode, liveMedia;
    private final android.util.SparseArray<Typeface> navaFonts = new android.util.SparseArray<>();
    private long resumePosition;
    private int loadGeneration;
    private int recoveryAttempts;
    private long lastPositionLog;
    private boolean started, failed, ready, seekable, renderedFrame, resumePlaying = true, updatingProgress;
    private final Runnable hideControls = () -> {
        if (player != null && ready && renderedFrame && player.getPlayWhenReady() && !failed) {
            controls.setVisibility(View.INVISIBLE);
            brand.setVisibility(View.INVISIBLE);
            liveBadge.setVisibility(View.INVISIBLE);
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!started) return;
            updateProgress();
            checkPlaybackProgress();
            ui.postDelayed(this, 500);
        }
    };

    public static Intent intent(Context context, String videoId) {
        return new Intent(context, NavaPlayerActivity.class).putExtra(EXTRA_VIDEO_ID, videoId);
    }

    static Intent mediathekIntent(Context context, String opaqueId) {
        // The Intent carries only a native cache handle, never a stream URL from the WebView.
        return new Intent(context, NavaPlayerActivity.class).putExtra(EXTRA_PROVIDER, "mediathek")
                .putExtra(EXTRA_MEDIATHEK_ID, opaqueId);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        mediathekMode = "mediathek".equals(getIntent().getStringExtra(EXTRA_PROVIDER));
        if (mediathekMode) {
            mediathekId = getIntent().getStringExtra(EXTRA_MEDIATHEK_ID);
            providerLabel = "LIBRARY";
            if (mediathekId == null || !mediathekId.matches("[A-Za-z0-9_-]{1,128}")) { finish(); return; }
        } else {
            videoId = getIntent().getStringExtra(EXTRA_VIDEO_ID);
            if (!NavaYouTube.validVideoId(videoId)) { finish(); return; }
        }
        resumePosition = state == null ? getIntent().getLongExtra(EXTRA_POSITION_MS, 0) : state.getLong("position", 0);
        resumePlaying = state == null || state.getBoolean("playing", true);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        GlobalPreferences.instance(getApplicationContext());
        if (!mediathekMode) service = YouTubeServiceManager.instance();
        sources = new NavaMediaSources(this);
        buildUi();
    }

    @Override protected void onStart() {
        super.onStart();
        if (isFinishing() || (mediathekMode ? mediathekId == null : videoId == null)) return;
        started = true;
        startupMs = SystemClock.elapsedRealtime();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this).setEnableDecoderFallback(true);
        trackSelector = new DefaultTrackSelector();
        if (mediathekMode) {
            player = ExoPlayerFactory.newSimpleInstance(this, renderers, trackSelector);
        } else {
            if (youtubeBandwidth == null) {
                youtubeBandwidth = new DefaultBandwidthMeter.Builder(getApplicationContext())
                        .setInitialBitrateEstimate(2_000_000L).build();
            }
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 50_000, 1_000, 5_000).createDefaultLoadControl();
            player = ExoPlayerFactory.newSimpleInstance(this, renderers, trackSelector, loadControl, null, youtubeBandwidth);
        }
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_MOVIE).build(), true);
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.EventListener() {
            @Override public void onPlayerStateChanged(boolean playing, int state) {
                Log.i("OpenNava", "OpenNava player state=" + state + " playing=" + playing);
                ready = !failed && (state == Player.STATE_READY || state == Player.STATE_ENDED);
                if (ready) seekable = player.isCurrentWindowSeekable();
                surface.setKeepScreenOn(playing && (state == Player.STATE_READY || state == Player.STATE_BUFFERING));
                if (!failed && sourcePrepared) {
                    if ((!renderedFrame || state == Player.STATE_BUFFERING) && playing) {
                        showLoading(null);
                    } else {
                        loadingPanel.setVisibility(View.GONE);
                    }
                }
                if (state == Player.STATE_ENDED && !renderedFrame && !failed) {
                    Log.w("OpenNava", "OpenNava player ended without a rendered video frame");
                    if (mediathekMode) showError(); else recoverPlayback(new IOException("No video frame"));
                    return;
                }
                if (compact == null) play.setText(failed ? "↻  Retry" : playing && state != Player.STATE_ENDED ? "Ⅱ  Pause" : "▶  Play");
                if (!playing || state == Player.STATE_ENDED) showControls();
                else if (ready) scheduleHide();
                updateProgress();
            }
            @Override public void onPositionDiscontinuity(int reason) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    previousRebufferMs = waitingSinceMs = 0;
                    lastAdvanceMs = SystemClock.elapsedRealtime();
                }
            }
            @Override public void onTracksChanged(TrackGroupArray groups, TrackSelectionArray selections) {
                if (player == null) return;
                int selectedVideo = 0, selectedAudio = 0;
                for (int index = 0; index < selections.length; index++) {
                    TrackSelection selection = selections.get(index);
                    if (selection == null) continue;
                    int type = player.getRendererType(index);
                    if (type == C.TRACK_TYPE_VIDEO) selectedVideo++;
                    if (type == C.TRACK_TYPE_AUDIO) selectedAudio++;
                    Format format = selection.getSelectedFormat();
                    Log.i("OpenNava", "OpenNava selected track type=" + type
                            + " mime=" + format.sampleMimeType + " codecs=" + format.codecs
                            + " size=" + format.width + "x" + format.height
                            + " bitrate=" + format.bitrate + " adaptiveTracks=" + selection.length());
                }
                Log.i("OpenNava", "OpenNava track groups=" + groups.length
                        + " videoSelections=" + selectedVideo + " audioSelections=" + selectedAudio);
            }
            @Override public void onPlayerError(ExoPlaybackException error) { recoverPlayback(error); }
        });
        player.addVideoListener(new VideoListener() {
            @Override public void onVideoSizeChanged(int width, int height, int rotation, float ratio) {
                Log.i("OpenNava", "OpenNava video output size=" + width + "x" + height);
                if (width > 0 && height > 0) videoFrame.setRatio((width * ratio) / height);
            }
            @Override public void onRenderedFirstFrame() {
                if (!sourcePrepared || failed || !started) return;
                boolean first = !renderedFrame;
                renderedFrame = true;
                status.setVisibility(View.GONE);
                spinner.setVisibility(View.GONE);
                loadingPanel.setVisibility(View.GONE);
                scheduleHide();
                if (first) {
                    waitingSinceMs = 0;
                    long now = SystemClock.elapsedRealtime();
                    Log.i("OpenNava", "OpenNava player first frame startupMs=" + (now - startupMs)
                            + " mediaMs=" + (now - preparedMs) + " attempt=" + recoveryAttempts);
                }
            }
        });
        loadVideo();
        ui.post(ticker);
    }

    private void loadVideo() {
        if (!started || player == null) return;
        if (pendingRecovery != null) { ui.removeCallbacks(pendingRecovery); pendingRecovery = null; }
        if (loading != null) loading.dispose();
        final int generation = ++loadGeneration;
        sourcePrepared = false;
        waitingSinceMs = healthySinceMs = previousRebufferMs = 0;
        failed = false;
        ready = false;
        renderedFrame = false;
        if (resumePlaying) showLoading(null);
        else loadingPanel.setVisibility(View.GONE);
        showControls();
        if (mediathekMode) {
            loading = io.reactivex.Observable.fromCallable(() -> MediathekService.resolve(mediathekId))
                    .subscribeOn(Schedulers.io()).take(1).timeout(25, TimeUnit.SECONDS)
                    .observeOn(AndroidSchedulers.mainThread()).subscribe(item -> {
                        if (generation != loadGeneration || !started || player == null) return;
                        try {
                            liveMedia = item.live;
                            providerLabel = "ZDF".equals(item.channel) ? "ZDF" : "ARD";
                            if (compact != null) compact.setMetadata(providerLabel, item.title, item.author);
                            else {
                                brand.setText("OpenNava   /   " + providerLabel);
                                liveBadge.setVisibility(liveMedia ? View.VISIBLE : View.GONE);
                                title.setText(item.title); author.setText(item.author);
                            }
                            seekable = !liveMedia;
                            MediaSource media = sources.createMediathek(item.url, item.mimeType);
                            sourcePrepared = true;
                            preparedMs = SystemClock.elapsedRealtime();
                            player.prepare(media);
                            if (!liveMedia && resumePosition > 0) player.seekTo(resumePosition);
                            player.setPlayWhenReady(resumePlaying);
                        } catch (Exception error) { logFailure("mediathek-source", error); showError(); }
                    }, error -> {
                        logFailure("mediathek-resolve", error);
                        if (generation == loadGeneration && started) showError(mediathekFailure(error));
                    });
            return;
        }
        final boolean platform = platformNetwork;
        loading = YouTubePlayback.format(videoId)
                .firstOrError()
                .map(info -> new PreparedVideo(info, sources.create(info, platform)))
                .timeout(35, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (generation != loadGeneration || !started || player == null) return;
                    try { openFormat(result); }
                    catch (RuntimeException error) { logFailure("source", error); showError(); }
                }, error -> {
                    if (generation != loadGeneration || !started) return;
                    logFailure("format-load", error);
                    showError();
                });
    }

    private static final class PreparedVideo {
        final MediaItemFormatInfo info;
        final MediaSource source;
        PreparedVideo(MediaItemFormatInfo info, MediaSource source) { this.info = info; this.source = source; }
    }

    private void openFormat(PreparedVideo result) {
        MediaItemFormatInfo info = result.info;
        if (compact != null) compact.setMetadata("YouTube",
                notEmpty(info.getTitle()) ? info.getTitle() : null,
                notEmpty(info.getAuthor()) ? info.getAuthor() : null);
        else {
            if (notEmpty(info.getTitle())) title.setText(info.getTitle());
            if (notEmpty(info.getAuthor())) author.setText(info.getAuthor());
        }
        seekable = !info.isLive() || info.isStreamSeekable();
        liveMedia = info.isLive();
        if (compact == null) liveBadge.setVisibility(liveMedia ? View.VISIBLE : View.GONE);

        // The pinned SABR extractor requests one itag per renderer. With a multi-track
        // selection the fetched itag and ExoPlayer's chosen representation can diverge.
        sabrSource = result.source instanceof SabrMediaSource;
        int budget = Integer.MAX_VALUE;
        if (sabrSource) {
            long estimate = youtubeBandwidth.getBitrateEstimate();
            budget = (int) Math.min(sabrBitrateLimit, Math.max(180_000L, estimate * 3 / 5 - 128_000L));
        }
        trackSelector.setParameters(trackSelector.buildUponParameters()
                .setViewportSizeToPhysicalDisplaySize(this, true)
                .setForceHighestSupportedBitrate(sabrSource).setMaxVideoBitrate(budget));
        preparedMs = SystemClock.elapsedRealtime();
        sourcePrepared = true;
        lastAdvanceMs = preparedMs;
        lastPosition = lastBuffered = -1;
        Log.i("OpenNava", "OpenNava player source prepared startupMs=" + (preparedMs - startupMs)
                + " fixedSabr=" + sabrSource + " maxVideoBitrate=" + budget);
        player.prepare(result.source);
        if (seekable && resumePosition > 0 && (!liveMedia || recoveryAttempts == 0)) player.seekTo(resumePosition);
        player.setPlayWhenReady(resumePlaying);
    }

    private void checkPlaybackProgress() {
        if (mediathekMode || !sourcePrepared || failed || player == null || pendingRecovery != null) return;
        long now = SystemClock.elapsedRealtime();
        int state = player.getPlaybackState();
        long position = player.getCurrentPosition(), buffered = player.getBufferedPosition();
        if (!player.getPlayWhenReady() || player.getPlaybackSuppressionReason() != Player.PLAYBACK_SUPPRESSION_REASON_NONE
                || state == Player.STATE_ENDED || !surface.getHolder().getSurface().isValid()) {
            waitingSinceMs = healthySinceMs = previousRebufferMs = 0;
            lastAdvanceMs = now;
        } else {
            if (state == Player.STATE_BUFFERING || !renderedFrame) {
                if (waitingSinceMs == 0) waitingSinceMs = now;
                if (buffered > lastBuffered) lastAdvanceMs = now;
                healthySinceMs = 0;
            } else {
                // SABR stays on the same itag. Several short interruptions need a step
                // down even though each one is shorter than the hard stall deadline.
                if (sabrSource && waitingSinceMs >= preparedMs && now - waitingSinceMs >= 4_000) {
                    boolean repeated = previousRebufferMs > 0 && now - previousRebufferMs < 60_000;
                    previousRebufferMs = now;
                    if (repeated && recoveryAttempts == 0 && player.getVideoFormat() != null
                            && player.getVideoFormat().bitrate > 250_000) {
                        recoverPlayback(new IOException("Repeated YouTube rebuffering"));
                        return;
                    }
                }
                waitingSinceMs = 0;
                if (healthySinceMs == 0) healthySinceMs = now;
                if (now - healthySinceMs >= 60_000 && position != lastPosition) recoveryAttempts = 0;
            }
            if (renderedFrame && position != lastPosition) lastAdvanceMs = now;
            if (now - lastAdvanceMs >= STALLED_MS || (waitingSinceMs > 0 && now - waitingSinceMs >= MAX_BUFFER_WAIT_MS)) {
                Log.w("OpenNava", "OpenNava player stalled state=" + state + " firstFrame=" + renderedFrame);
                recoverPlayback(new IOException("YouTube playback stalled"));
            }
        }
        lastPosition = position;
        lastBuffered = buffered;
    }

    private static boolean notEmpty(String value) { return value != null && !value.trim().isEmpty(); }

    private void recoverPlayback(Throwable error) {
        logFailure("playback", error);
        if (!started || player == null || !sourcePrepared || pendingRecovery != null || failed) return;
        rememberPlayback();
        sourcePrepared = false;
        if (++recoveryAttempts > 2) { showError(mediathekMode ? mediathekFailure(error) : null); return; }
        if (mediathekMode) {
            showLoading(liveMedia ? "Reconnecting to live TV…" : "Resuming playback…");
            if (pendingRecovery != null) ui.removeCallbacks(pendingRecovery);
            pendingRecovery = () -> { if (started) loadVideo(); };
            ui.postDelayed(pendingRecovery, 1500);
            return;
        }
        SabrReloadPlayerError reload = NavaMediaSources.findCause(error, SabrReloadPlayerError.class);
        SabrBackoffError backoff = NavaMediaSources.findCause(error, SabrBackoffError.class);
        SabrPoTokenError tokenError = NavaMediaSources.findCause(error, SabrPoTokenError.class);
        long delay = 350;
        if (reload != null) {
            service.reloadPlayerResponse(videoId, reload.reloadPlaybackToken);
        } else if (backoff != null) {
            // Respect the server's requested wait; no unbounded request loop.
            if (backoff.backoffMs > 30_000) { showError(); return; }
            delay = Math.max(1_000, backoff.backoffMs);
        } else if (tokenError != null) {
            service.switchNextClientNow();
        } else {
            if (sabrSource && player.getVideoFormat() != null && player.getVideoFormat().bitrate > 0) {
                sabrBitrateLimit = Math.max(150_000, (int) (player.getVideoFormat().bitrate * .64));
            }
            if (recoveryAttempts == 1) platformNetwork = true;
            else service.switchNextClientNow();
        }
        player.stop(true);
        showLoading("Resuming playback…");
        if (pendingRecovery != null) ui.removeCallbacks(pendingRecovery);
        pendingRecovery = () -> { if (started) loadVideo(); };
        ui.postDelayed(pendingRecovery, delay);
    }

    private void showError() {
        showError(null);
    }

    private void showLoading(String notice) {
        boolean showCaption = compact == null || notice != null;
        if (showCaption) status.setText(notice != null ? notice : liveMedia ? "Loading live TV…" : "Loading video…");
        status.setVisibility(showCaption ? View.VISIBLE : View.GONE);
        spinner.setVisibility(View.VISIBLE);
        loadingPanel.setVisibility(View.VISIBLE);
    }

    private void showError(String safeMessage) {
        failed = true;
        ready = false;
        sourcePrepared = false;
        if (player != null && player.getPlaybackState() != Player.STATE_IDLE) player.stop(true);
        spinner.setVisibility(View.GONE);
        status.setText((safeMessage == null ? "Playback is currently unavailable." : safeMessage)
                + "\nOK to retry · Back to close the player");
        status.setVisibility(View.VISIBLE);
        loadingPanel.setVisibility(View.VISIBLE);
        if (compact == null) play.setText("↻  Retry");
        else updateProgress();
        if (player != null) player.setPlayWhenReady(false);
        surface.setKeepScreenOn(false);
        showControls();
    }

    private static String mediathekFailure(Throwable error) {
        String known = MediathekService.playbackErrorMessage(error);
        if (known != null) return known;
        com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException http =
                NavaMediaSources.findCause(error, com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException.class);
        if (http != null) {
            if (http.responseCode == 403 || http.responseCode == 451)
                return "The broadcaster has restricted playback. The programme may be limited by region or airtime.";
            if (http.responseCode == 404 || http.responseCode == 410)
                return "The broadcaster no longer offers this programme.";
        }
        return null;
    }

    private static void logFailure(String stage, Throwable error) {
        StringBuilder types = new StringBuilder();
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (depth > 0) types.append('>');
            types.append(cause.getClass().getSimpleName());
            if (cause instanceof com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException)
                types.append("(http=").append(((com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException)cause).responseCode).append(')');
        }
        Log.w("OpenNava", "OpenNava player failure stage=" + stage + " causes=" + types);
    }

    private void togglePlayback() {
        if (player == null) return;
        if (failed) {
            recoveryAttempts = 0;
            platformNetwork = false;
            sabrBitrateLimit = Integer.MAX_VALUE;
            startupMs = SystemClock.elapsedRealtime();
            resumePlaying = true;
            loadVideo();
        } else {
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                resumePosition = 0;
                if (liveMedia) player.seekToDefaultPosition(); else player.seekTo(0);
                resumePlaying = true;
            } else {
                resumePlaying = sourcePrepared ? !player.getPlayWhenReady() : !resumePlaying;
            }
            player.setPlayWhenReady(resumePlaying);
        }
        showControls();
    }

    private void seek(long delta) {
        if (player == null || !seekable || !ready) { showControls(); return; }
        long position = Math.max(0, player.getCurrentPosition() + delta);
        long duration = player.getDuration();
        if (duration > 0) position = Math.min(position, Math.max(0, duration - 500));
        player.seekTo(position);
        showControls();
        updateProgress();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (compact != null && compact.handleNavigation(event)) return true;
        int code = event.getKeyCode();
        boolean control = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_MENU
                || code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
        if (!control) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND: seek(-10_000); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seek(10_000); break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                resumePlaying = true; if (player != null) player.setPlayWhenReady(true); showControls(); break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                resumePlaying = false; if (player != null) player.setPlayWhenReady(false); showControls(); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getRepeatCount() == 0) togglePlayback(); break;
            default: showControls();
        }
        return true;
    }

    @Override public void onBackPressed() { finish(); }

    private void showControls() {
        if (compact != null) { compact.reveal(); return; }
        if (controls == null) return;
        controls.setVisibility(View.VISIBLE);
        brand.setVisibility(View.VISIBLE);
        liveBadge.setVisibility(liveMedia ? View.VISIBLE : View.GONE);
        scheduleHide();
    }

    private void scheduleHide() {
        if (compact != null) return;
        ui.removeCallbacks(hideControls);
        if (!failed && player != null && player.getPlayWhenReady()) ui.postDelayed(hideControls, 4_000);
    }

    private void updateProgress() {
        if (player == null || (progress == null && compact == null)) return;
        long duration = player.getDuration(), position = Math.max(0, player.getCurrentPosition());
        if (ready) seekable = player.isCurrentWindowSeekable();
        long now = android.os.SystemClock.elapsedRealtime();
        if (ready && now - lastPositionLog >= 5000) {
            lastPositionLog = now;
            Log.i("OpenNava", "OpenNava player positionMs=" + position + " durationMs=" + duration
                    + " playing=" + player.getPlayWhenReady() + " firstFrame=" + renderedFrame
                    + " videoFormat=" + (player.getVideoFormat() == null ? "none" : player.getVideoFormat().sampleMimeType));
        }
        if (compact != null) {
            boolean playing = player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED;
            compact.update(playing, playing && ready && renderedFrame && !failed, failed, seekable && ready,
                    liveMedia, position, player.getBufferedPosition(), duration);
            return;
        }
        updatingProgress = true;
        progress.setEnabled(seekable && duration > 0);
        boolean canSeek = seekable && ready && duration > 0;
        rewind.setEnabled(canSeek); forward.setEnabled(canSeek);
        rewind.setAlpha(canSeek ? 1f : .4f); forward.setAlpha(canSeek ? 1f : .4f);
        progress.setProgress(duration > 0 ? (int) Math.min(1000, position * 1000 / duration) : 0);
        progress.setSecondaryProgress(duration > 0 ? (int) Math.min(1000, player.getBufferedPosition() * 1000 / duration) : 0);
        updatingProgress = false;
        if (liveMedia) {
            long behind = duration > 0 ? Math.max(0, duration - position) : 0;
            time.setText(behind > 10_000 && seekable ? "LIVE  ·  −" + clock(behind) : "LIVE");
        } else time.setText(duration > 0 ? clock(position) + "  /  " + clock(duration) : "–:–");
    }

    private static String clock(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        videoFrame = new VideoFrame(this);
        surface = new SurfaceView(this);
        videoFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(videoFrame, new FrameLayout.LayoutParams(-1, -1));

        loadingPanel = new LinearLayout(this);
        loadingPanel.setGravity(Gravity.CENTER); loadingPanel.setOrientation(LinearLayout.VERTICAL);
        boolean preview = "site.navatv.rustpreview".equals(getPackageName());
        status = label("", 16, Color.WHITE);
        status.setGravity(Gravity.CENTER);
        if (preview) {
            spinner = new PlaybackLoadingMark(this);
            loadingPanel.addView(spinner, new LinearLayout.LayoutParams(-2, -2));
            status.setBackground(rounded(0xeb211f2b, 14, 0x4068657f));
            status.setPadding(dp(16), dp(10), dp(16), dp(10));
            status.setMaxWidth(dp(440));
            loadingPanel.addView(status, new LinearLayout.LayoutParams(-2, -2));
        } else {
            loadingPanel.setPadding(dp(28), dp(24), dp(28), dp(24));
            loadingPanel.setBackground(rounded(0xf016201b, 20, 0x446d8f7a));
            ProgressBar progressWheel = new ProgressBar(this);
            progressWheel.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            spinner = progressWheel;
            loadingPanel.addView(spinner, new LinearLayout.LayoutParams(dp(32), dp(32)));
            status.setPadding(0, dp(16), 0, 0);
            loadingPanel.addView(status, new LinearLayout.LayoutParams(dp(360), -2));
            loadingPanel.setTranslationY(-dp(40));
        }
        loadingPanel.setVisibility(View.GONE);
        root.addView(loadingPanel, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        if (preview) {
            compact = new CompactPlaybackControls(this, mediathekMode ? "Mediathek" : "YouTube",
                    getIntent().getStringExtra(EXTRA_TITLE), getIntent().getStringExtra(EXTRA_AUTHOR),
                    this::togglePlayback, this::seek, ms -> {
                        if (player != null && ready && seekable) player.seekTo(ms);
                    });
            root.addView(compact, new FrameLayout.LayoutParams(-1, -1));
            loadingPanel.bringToFront();
            root.setOnClickListener(v -> compact.reveal());
            setContentView(root);
            return;
        }
        brand = label("OpenNava   /   " + providerLabel, 11, ACCENT);
        applyFontWeight(brand, 600);
        brand.setLetterSpacing(.14f);
        brand.setPadding(dp(18), dp(10), dp(18), dp(10));
        brand.setBackground(rounded(0xda101a16, 30, 0x446d8f7a));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        badge.setMargins(dp(32), dp(26), 0, 0); root.addView(brand, badge);
        liveBadge = label("●  LIVE", 11, ACCENT);
        applyFontWeight(liveBadge, 600);
        liveBadge.setLetterSpacing(.1f); liveBadge.setPadding(dp(16), dp(10), dp(16), dp(10));
        liveBadge.setBackground(rounded(0xda101a16, 30, 0x446d8f7a));
        liveBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams livePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        livePosition.setMargins(0, dp(26), dp(32), 0); root.addView(liveBadge, livePosition);

        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(26), dp(20), dp(26), dp(18));
        GradientDrawable controlBackground = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xe618231c, 0xf50d1511});
        controlBackground.setCornerRadius(dp(22)); controlBackground.setStroke(dp(1), 0x446d8f7a);
        controls.setBackground(controlBackground);
        title = label(getIntent().getStringExtra(EXTRA_TITLE), 24, Color.WHITE);
        title.setMaxLines(2); applyFontWeight(title, 700);
        controls.addView(title);
        author = label(getIntent().getStringExtra(EXTRA_AUTHOR), 13, 0xffaebeb4);
        author.setPadding(0, dp(6), 0, dp(8)); controls.addView(author);
        progress = new SeekBar(this); progress.setMax(1000); progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setThumbTintList(ColorStateList.valueOf(ACCENT)); progress.setSecondaryProgressTintList(ColorStateList.valueOf(0xff777777));
        progress.setPadding(0, dp(8), 0, dp(8));
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && !updatingProgress && player != null && player.getDuration() > 0) {
                    player.seekTo(player.getDuration() * value / 1000); showControls();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { ui.removeCallbacks(hideControls); }
            @Override public void onStopTrackingTouch(SeekBar bar) { scheduleHide(); }
        });
        controls.addView(progress, new LinearLayout.LayoutParams(-1, dp(30)));
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        play = controlButton("Ⅱ  Pause", true); play.setContentDescription("Play or pause");
        play.setOnClickListener(view -> togglePlayback()); row.addView(play, new LinearLayout.LayoutParams(dp(154), dp(40)));
        rewind = controlButton("− 10 sec", false); rewind.setOnClickListener(view -> seek(-10_000));
        LinearLayout.LayoutParams rewindPosition = new LinearLayout.LayoutParams(dp(108), dp(40));
        rewindPosition.leftMargin = dp(8); row.addView(rewind, rewindPosition);
        forward = controlButton("+ 10 sec", false); forward.setOnClickListener(view -> seek(10_000));
        LinearLayout.LayoutParams forwardPosition = new LinearLayout.LayoutParams(dp(108), dp(40));
        forwardPosition.leftMargin = dp(8); row.addView(forward, forwardPosition);
        time = label("", 13, 0xffd9e6dd); time.setGravity(Gravity.RIGHT);
        time.setPadding(dp(12), 0, 0, 0); row.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(row);
        TextView hint = label("← / →  Seek     ·     OK  Pause     ·     Back  Close", 11, 0xff97aa9e);
        hint.setPadding(0, dp(12), 0, 0); controls.addView(hint);
        FrameLayout.LayoutParams controlPosition = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        controlPosition.setMargins(dp(32), 0, dp(32), dp(24)); root.addView(controls, controlPosition);
        setContentView(root);
    }

    private GradientDrawable rounded(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), border); return drawable;
    }

    private TextView controlButton(String text, boolean primary) {
        TextView view = label(text, 13, primary ? 0xff14231a : 0xffd7e7dc);
        view.setGravity(Gravity.CENTER); applyFontWeight(view, 650);
        view.setBackground(rounded(primary ? ACCENT : 0xff27382e, 14, primary ? ACCENT : 0xff405547));
        return view;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value == null ? "" : value); view.setTextSize(size); view.setTextColor(color);
        applyFontWeight(view, 500); return view;
    }

    private void applyFontWeight(TextView view, int weight) {
        Typeface font = navaFonts.get(weight);
        if (font == null) {
            try {
                // Bundled Manrope defaults to wght=200. NORMAL/BOLD alone do not select
                // its variable outline reliably on Fire OS; set both axis and metadata.
                font = new Typeface.Builder(getAssets(), "web/assets/fonts/Manrope.ttf")
                        .setFontVariationSettings("'wght' " + weight).setWeight(weight).setItalic(false).build();
            } catch (RuntimeException ignored) { }
            if (font == null) font = Typeface.create(weight >= 600 ? "sans-serif" : "sans-serif-medium",
                    weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
            navaFonts.put(weight, font);
        }
        view.setTypeface(font);
        view.setFontVariationSettings("'wght' " + weight);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void rememberPlayback() {
        // Before prepare or during recovery the player's masked position is usually zero.
        if (player == null || !sourcePrepared) return;
        long position = player.getCurrentPosition();
        if (renderedFrame || position > 0) resumePosition = Math.max(0, position);
        resumePlaying = player.getPlayWhenReady();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        rememberPlayback();
        state.putLong("position", resumePosition); state.putBoolean("playing", resumePlaying);
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() {
        started = false; ++loadGeneration;
        if (loadingPanel != null) loadingPanel.setVisibility(View.GONE);
        ui.removeCallbacksAndMessages(null);
        if (loading != null) { loading.dispose(); loading = null; }
        if (player != null) {
            rememberPlayback();
            sourcePrepared = false;
            player.release(); player = null;
        }
        if (surface != null) surface.setKeepScreenOn(false);
        super.onStop();
    }

    /** Keeps the native video surface's aspect ratio without any Leanback/player UI module. */
    private static final class VideoFrame extends FrameLayout {
        private float ratio = 16f / 9;
        VideoFrame(Context context) { super(context); }
        void setRatio(float value) { ratio = value; requestLayout(); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            int videoWidth = width, videoHeight = height;
            if (ratio > 0 && width > 0 && height > 0) {
                if ((float) width / height > ratio) videoWidth = Math.round(height * ratio);
                else videoHeight = Math.round(width / ratio);
            }
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(videoWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(videoHeight, MeasureSpec.EXACTLY));
        }
    }
}

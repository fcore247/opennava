package site.navatv.rustpreview;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.os.Handler;
import android.os.Looper;
import site.navatv.launcher.CompactPlaybackControls;
import site.navatv.launcher.PlaybackLoadingMark;
import android.widget.TextView;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.video.VideoListener;
import java.util.Locale;

/** Same-package native player for authorized HTTPS URLs resolved by the Rust catalogue. */
public final class StreamActivity extends Activity {
    private static final String TAG = "OpenNavaRustPlayer";
    private SimpleExoPlayer player;
    private FrameLayout screen;
    private SurfaceView surface;
    private CompactPlaybackControls controls;
    private PlaybackLoadingMark loading;
    private boolean renderedFrame, failed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            updateControls();
            handler.postDelayed(this, 500);
        }
    };
    private TextView status;
    private Uri uri;
    private long resumeAt;
    private boolean shouldPlay = true;
    private float aspect = 16f / 9f;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String source = getIntent().getStringExtra("stream_url");
        uri = source == null ? Uri.EMPTY : Uri.parse(source);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            finish();
            return;
        }
        if (state != null) {
            resumeAt = state.getLong("position");
            shouldPlay = state.getBoolean("playing", true);
        }
        screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        screen.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        screen.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) fitSurface();
        });
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(20);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        screen.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        controls = new CompactPlaybackControls(this, "Mediathek", getIntent().getStringExtra("title"), null,
                this::togglePlayback, delta -> {
                    if (player != null) seekTo(player.getCurrentPosition() + delta);
                }, this::seekTo);
        screen.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        loading = new PlaybackLoadingMark(this);
        screen.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        status.bringToFront();
        screen.setOnClickListener(v -> controls.reveal());
        setContentView(screen);
    }

    private void fitSurface() {
        if (screen.getWidth() <= 0 || screen.getHeight() <= 0 || aspect <= 0) return;
        int width = Math.min(screen.getWidth(), Math.round(screen.getHeight() * aspect));
        int height = Math.min(screen.getHeight(), Math.round(width / aspect));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (params.width == width && params.height == height) return;
        params.width = width;
        params.height = height;
        surface.setLayoutParams(params);
    }

    @Override protected void onStart() {
        super.onStart();
        if (isFinishing()) return;
        prepare();
    }

    private void prepare() {
        failed = false;
        renderedFrame = false;
        status.setVisibility(View.GONE);
        loading.setVisibility(shouldPlay ? View.VISIBLE : View.GONE);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        DefaultHttpDataSourceFactory network = new DefaultHttpDataSourceFactory(
                "OpenNava Rust (Android TV)", null, 15_000, 20_000, false);
        MediaSource media;
        if (path.endsWith(".m3u8")) {
            // Preserve the master playlist, including its AAC audio group, map and byte ranges.
            media = new HlsMediaSource.Factory(network).createMediaSource(uri);
        } else if (path.endsWith(".mp4")) {
            media = new ExtractorMediaSource.Factory(network).createMediaSource(uri);
        } else {
            showFailure();
            return;
        }
        DefaultTrackSelector tracks = new DefaultTrackSelector();
        tracks.setParameters(tracks.buildUponParameters().setPreferredAudioLanguage("de"));
        player = ExoPlayerFactory.newSimpleInstance(this,
                new DefaultRenderersFactory(this).setEnableDecoderFallback(true), tracks);
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MOVIE).build(), true);
        player.setVideoSurfaceView(surface);
        player.addVideoListener(new VideoListener() {
            @Override public void onRenderedFirstFrame() {
                renderedFrame = true;
                Log.i(TAG, "First broadcaster video frame");
                loading.setVisibility(View.GONE);
            }
            @Override public void onVideoSizeChanged(int w, int h, int rotation, float pixelRatio) {
                if (w <= 0 || h <= 0 || pixelRatio <= 0) return;
                aspect = w * pixelRatio / h;
                if (rotation == 90 || rotation == 270) aspect = 1 / aspect;
                fitSurface();
            }
        });
        player.addListener(new Player.EventListener() {
            @Override public void onPlayerStateChanged(boolean play, int state) {
                surface.setKeepScreenOn(play && (state == Player.STATE_READY || state == Player.STATE_BUFFERING));
                boolean waiting = state == Player.STATE_BUFFERING || (state == Player.STATE_READY && !renderedFrame);
                loading.setVisibility(play && !failed && waiting ? View.VISIBLE : View.GONE);
                updateControls();
                if (state == Player.STATE_ENDED) finish();
            }
            @Override public void onPlayerError(ExoPlaybackException error) {
                Throwable cause = error.getCause();
                Log.w(TAG, "Broadcaster playback error " + error.type + " (" +
                        (cause == null ? "unknown" : cause.getClass().getSimpleName()) + ")");
                showFailure();
            }
        });
        if (resumeAt > 0) player.seekTo(resumeAt);
        player.prepare(media, resumeAt == 0, true);
        player.setPlayWhenReady(shouldPlay);
        handler.post(tick);
    }

    private void showFailure() {
        failed = true;
        loading.setVisibility(View.GONE);
        surface.setKeepScreenOn(false);
        controls.update(false, false, true, false, false, 0, 0, 0);
        controls.reveal();
        status.setText("Dieses Video ist gerade nicht verfügbar. Zurück führt zur Mediathek.");
        status.setVisibility(View.VISIBLE);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        if (player != null) {
            resumeAt = player.getCurrentPosition();
            shouldPlay = player.getPlayWhenReady();
            player.release();
            player = null;
        }
        if (surface != null) surface.setKeepScreenOn(false);
        if (loading != null) loading.setVisibility(View.GONE);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putLong("position", player == null ? resumeAt : player.getCurrentPosition());
        state.putBoolean("playing", player == null ? shouldPlay : player.getPlayWhenReady());
    }

    private void updateControls() {
        if (player == null) return;
        boolean ready = player.getPlaybackState() == Player.STATE_READY;
        boolean playing = player.getPlayWhenReady() && !failed;
        controls.update(playing, ready && renderedFrame && playing, failed,
                !failed && ready && player.isCurrentWindowSeekable(), false,
                player.getCurrentPosition(), player.getBufferedPosition(), player.getDuration());
    }

    private void togglePlayback() {
        if (failed) {
            handler.removeCallbacks(tick);
            if (player != null) { resumeAt = player.getCurrentPosition(); player.release(); player = null; }
            shouldPlay = true;
            prepare();
        } else if (player != null) player.setPlayWhenReady(!player.getPlayWhenReady());
    }

    private void seekTo(long ms) {
        if (player == null || failed || !player.isCurrentWindowSeekable()) return;
        long duration = player.getDuration();
        player.seekTo(Math.max(0, duration > 0 ? Math.min(duration - 1, ms) : ms));
        updateControls();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (controls != null && controls.handleNavigation(event)) return true;
        if (player == null) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && code != KeyEvent.KEYCODE_MEDIA_PLAY
                && code != KeyEvent.KEYCODE_MEDIA_PAUSE && code != KeyEvent.KEYCODE_MEDIA_REWIND
                && code != KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            switch (code) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: togglePlayback(); break;
                case KeyEvent.KEYCODE_MEDIA_PLAY: if (failed) togglePlayback(); else player.setPlayWhenReady(true); break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: player.setPlayWhenReady(false); break;
                case KeyEvent.KEYCODE_MEDIA_REWIND: seekTo(player.getCurrentPosition() - 10000); break;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekTo(player.getCurrentPosition() + 10000); break;
            }
            controls.reveal();
        }
        return true;
    }
}

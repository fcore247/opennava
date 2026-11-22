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
import android.widget.MediaController;
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
public final class StreamActivity extends Activity implements MediaController.MediaPlayerControl {
    private static final String TAG = "OpenNavaRustPlayer";
    private SimpleExoPlayer player;
    private FrameLayout screen;
    private SurfaceView surface;
    private MediaController controls;
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
        status.setText(getIntent().getStringExtra("title"));
        screen.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        setContentView(screen);
        controls = new MediaController(this);
        controls.setAnchorView(screen);
        controls.setMediaPlayer(this);
        screen.setOnClickListener(v -> controls.show());
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
                Log.i(TAG, "First broadcaster video frame");
                status.setVisibility(View.GONE);
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
    }

    private void showFailure() {
        surface.setKeepScreenOn(false);
        status.setText("Dieses Video ist gerade nicht verfügbar. Zurück führt zur Mediathek.");
        status.setVisibility(View.VISIBLE);
    }

    @Override protected void onStop() {
        if (controls != null) controls.hide();
        if (player != null) {
            resumeAt = player.getCurrentPosition();
            shouldPlay = player.getPlayWhenReady();
            player.release();
            player = null;
        }
        if (surface != null) surface.setKeepScreenOn(false);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putLong("position", player == null ? resumeAt : player.getCurrentPosition());
        state.putBoolean("playing", player == null ? shouldPlay : player.getPlayWhenReady());
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        if (player == null) return super.onKeyDown(key, event);
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (event.getRepeatCount() == 0) player.setPlayWhenReady(!player.getPlayWhenReady());
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY: start(); break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: pause(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: seekTo(Math.max(0, getCurrentPosition() - 10_000)); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: seekTo(getCurrentPosition() + 10_000); break;
            default: return super.onKeyDown(key, event);
        }
        controls.show();
        return true;
    }

    @Override public int getDuration() {
        long duration = player == null ? 0 : player.getDuration();
        return duration <= 0 ? 0 : (int)Math.min(Integer.MAX_VALUE, duration);
    }
    @Override public int getCurrentPosition() { return player == null ? 0 : (int)player.getCurrentPosition(); }
    @Override public boolean isPlaying() { return player != null && player.getPlayWhenReady(); }
    @Override public boolean canPause() { return true; }
    @Override public boolean canSeekBackward() { return player != null && player.isCurrentWindowSeekable(); }
    @Override public boolean canSeekForward() { return canSeekBackward(); }
    @Override public void start() { if (player != null) player.setPlayWhenReady(true); }
    @Override public void pause() { if (player != null) player.setPlayWhenReady(false); }
    @Override public void seekTo(int position) { if (canSeekForward()) player.seekTo(Math.max(0, position)); }
    @Override public int getBufferPercentage() { return player == null ? 0 : player.getBufferedPercentage(); }
    @Override public int getAudioSessionId() { return player == null ? 0 : player.getAudioSessionId(); }
}

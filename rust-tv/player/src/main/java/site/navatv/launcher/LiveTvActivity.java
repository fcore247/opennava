package site.navatv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
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
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The native preview's live player shares the classic TV chrome and broadcaster resolver. */
public final class LiveTvActivity extends Activity {
    private static final String TAG = "OpenNavaRustLive";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());
    private final List<LiveGuide.Channel> channels = LiveGuide.channels();
    private final ExecutorService streams = Executors.newSingleThreadExecutor();
    private final ExecutorService schedules = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hideControls;
    private final Runnable renewGuide = this::loadGuide;

    private FrameLayout root, overlay;
    private SurfaceView surface;
    private TextView status, stationName, title, next, timeRange, number, kind;
    private ProgressBar progress;
    private PlaybackLoadingMark loading;
    private PlayerChrome chrome;
    private PlayerChrome.ActionButton play;
    private AlertDialog channelList;
    private ExoPlayer player;
    private Future<?> streamJob, guideJob;
    private int station, generation;
    private boolean wantsPlay = true, failed, firstFrame;
    private float aspect = 16f / 9;
    private LiveGuide.Programme now, following;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        String selected = state != null ? state.getString("channel") : getIntent().getStringExtra("channel");
        if (selected == null) selected = getPreferences(MODE_PRIVATE).getString("channel", "daserste");
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).id.equals(selected)) station = i;
        if (state != null) wantsPlay = state.getBoolean("play", true);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusableInTouchMode(true);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob) -> {
            if (r-l != oright-ol || b-t != ob-ot) fitVideo();
        });
        root.setOnClickListener(v -> {
            if (visible()) hideControls(); else showControls(true);
        });
        overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        chrome = new PlayerChrome(this, overlay, "Live-TV");
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        overlay.addView(details, chrome.at(-1, -2, Gravity.TOP | Gravity.START, 56, 50, 128, 0));
        kind = addLine(details, 11, 770, PlayerChrome.LAVENDER);
        kind.setLetterSpacing(.13f);
        stationName = addLine(details, 37, 800, PlayerChrome.PEARL);
        stationName.setLetterSpacing(-.045f);
        title = addLine(details, 18, 650, PlayerChrome.PEARL);
        LinearLayout times = new LinearLayout(this);
        times.setGravity(Gravity.CENTER_VERTICAL);
        details.addView(times);
        timeRange = addLine(times, 12, 600, PlayerChrome.MUTED);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(PlayerChrome.LAVENDER));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(chrome.dp(76), chrome.dp(3));
        bar.leftMargin = chrome.dp(10);
        times.addView(progress,bar);
        next = addLine(details, 13, 570, PlayerChrome.MUTED);
        number = chrome.label(148, 800, PlayerChrome.PEARL);
        number.setLetterSpacing(-.065f);
        number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        number.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob) -> {
            if (r-l != oright-ol && r>l) number.getPaint().setShader(new LinearGradient(0,0,r-l,chrome.dp(32),
                    new int[]{0xff99a7ff,0xffbd9cf7,0xfff0a9bf,0xfff3b59e},null, Shader.TileMode.CLAMP));
        });
        overlay.addView(number, chrome.at(-2,-2, Gravity.BOTTOM | Gravity.END,0,0,68,91));
        play = chrome.button("Pause", PlayerChrome.Icon.PAUSE, this::togglePlay);
        chrome.setActions(0,play,chrome.button("Senderliste", PlayerChrome.Icon.LIBRARY, this::chooseChannel));
        loading = new PlaybackLoadingMark(this);
        root.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        status = chrome.label(16, 630, PlayerChrome.PEARL);
        status.setGravity(Gravity.CENTER);
        root.addView(status,chrome.at(-2,-2,Gravity.CENTER,0,0,0,0));
        setContentView(root);
    }

    private TextView addLine(LinearLayout parent, int size, int weight, int color) {
        TextView text = chrome.label(size, weight, color);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        parent.addView(text);
        return text;
    }

    private void fitVideo() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        if ((float)w/h > aspect) w = Math.round(h * aspect); else h = Math.round(w / aspect);
        surface.setLayoutParams(new FrameLayout.LayoutParams(w,h,Gravity.CENTER));
    }

    @Override protected void onStart() {
        super.onStart();
        player = new ExoPlayer.Builder(this).setLoadControl(new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15000,50000,1000,2000).build()).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA).build(), true);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage("de").build());
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.height > 0 && size.width > 0) { aspect = size.width * size.pixelWidthHeightRatio / size.height; fitVideo(); }
            }
            @Override public void onRenderedFirstFrame() {
                if (player == null) return;
                firstFrame = true;
                loading.setVisibility(View.GONE);
                Log.i(TAG,"First frame: " + channels.get(station).id);
                if (visible()) scheduleHide(false);
            }
            @Override public void onIsPlayingChanged(boolean playing) { surface.setKeepScreenOn(playing); }
            @Override public void onPlaybackStateChanged(int state) {
                if (player == null || failed) return;
                if (state == Player.STATE_ENDED) fail();
                else updateLoading();
            }
            @Override public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Stream failed for " + channels.get(station).id, error);
                LiveGuide.forgetStream(channels.get(station));
                fail();
            }
        });
        tune();
    }

    private void tune() {
        final int request = ++generation;
        final LiveGuide.Channel channel = channels.get(station);
        if (streamJob != null) streamJob.cancel(true);
        if (guideJob != null) guideJob.cancel(true);
        main.removeCallbacks(renewGuide);
        player.stop();
        player.clearMediaItems();
        failed = firstFrame = false;
        now = following = null;
        getPreferences(MODE_PRIVATE).edit().putString("channel",channel.id).apply();
        updateLoading();
        status.setVisibility(View.GONE);
        showControls(false);
        streamJob = streams.submit(() -> {
            try {
                String url = LiveGuide.resolveStream(channel);
                main.post(() -> {
                    if (generation != request || player == null) return;
                    player.setMediaItem(new MediaItem.Builder().setMediaId(channel.id).setUri(url)
                            .setMimeType(MimeTypes.APPLICATION_M3U8).build());
                    player.prepare();
                    player.setPlayWhenReady(wantsPlay);
                });
            } catch (Exception problem) {
                main.post(() -> { if (generation == request && player != null) fail(); });
            }
        });
        loadGuide();
    }

    private void loadGuide() {
        if (player == null) return;
        final int request = generation;
        final LiveGuide.Channel channel = channels.get(station);
        guideJob = schedules.submit(() -> {
            LiveGuide.Programme[] result;
            try { result = LiveGuide.nowNext(channel,System.currentTimeMillis()); }
            catch (Exception offline) { result = LiveGuide.knownNowNext(channel,System.currentTimeMillis()); }
            final LiveGuide.Programme[] pair = result;
            main.post(() -> {
                if (generation != request || player == null) return;
                now = pair == null ? null : pair[0];
                following = pair == null ? null : pair[1];
                refreshLabels();
                long nextCheck = now == null ? 60000 : Math.min(60000,Math.max(2000,now.end-System.currentTimeMillis()+100));
                main.postDelayed(renewGuide,nextCheck);
            });
        });
    }

    private void refreshLabels() {
        long stamp = System.currentTimeMillis();
        String n = String.format(Locale.GERMANY,"%02d", station + 1);
        number.setText(n);
        kind.setText("SENDER " + n + (wantsPlay ? "   ·   LIVE" : "   ·   PAUSIERT"));
        chrome.clock.setText(CLOCK.format(Instant.ofEpochMilli(stamp)));
        stationName.setText(channels.get(station).name);
        title.setText(now == null ? "" : now.title);
        next.setText(following == null ? "" : "Danach   " + following.title);
        timeRange.setText(now == null ? "" : CLOCK.format(Instant.ofEpochMilli(now.start)) + " – " + CLOCK.format(Instant.ofEpochMilli(now.end)));
        progress.setVisibility(now != null && now.end > now.start ? View.VISIBLE : View.GONE);
        if (now != null && now.end > now.start) progress.setProgress((int)Math.min(1000,Math.max(0,(stamp-now.start)*1000/(now.end-now.start))));
        play.update(failed ? "Neu laden" : wantsPlay ? "Pause" : "Wiedergabe",
                failed ? PlayerChrome.Icon.RETRY : wantsPlay ? PlayerChrome.Icon.PAUSE : PlayerChrome.Icon.PLAY, true);
    }

    private boolean visible() { return overlay.getVisibility() == View.VISIBLE; }
    private void scheduleHide(boolean manual) {
        main.removeCallbacks(hide);
        if (firstFrame && !failed && wantsPlay && channelList == null) main.postDelayed(hide, manual ? 10000 : 7500);
    }
    private void showControls(boolean manual) {
        refreshLabels();
        overlay.setVisibility(View.VISIBLE);
        chrome.select(0,true);
        scheduleHide(manual);
    }
    private void hideControls() {
        main.removeCallbacks(hide);
        if (failed) return;
        overlay.setVisibility(View.GONE);
        root.requestFocus();
    }
    private void fail() {
        failed = true;
        loading.setVisibility(View.GONE);
        status.setText("Dieser Sender ist gerade nicht erreichbar.");
        status.setVisibility(View.VISIBLE);
        showControls(true);
    }
    private void updateLoading() {
        boolean waiting = !firstFrame || player.getPlaybackState() == Player.STATE_BUFFERING;
        loading.setVisibility(wantsPlay && !failed && waiting ? View.VISIBLE : View.GONE);
    }
    private void togglePlay() {
        if (player == null) return;
        if (failed) { wantsPlay = true; tune(); return; }
        wantsPlay = !wantsPlay;
        player.setPlayWhenReady(wantsPlay);
        updateLoading();
        showControls(true);
    }
    private void switchTo(int index) {
        station = Math.floorMod(index,channels.size());
        wantsPlay = true;
        tune();
    }

    private void chooseChannel() {
        if (channelList != null || player == null) return;
        String[] labels = new String[channels.size()];
        for (int i=0; i<labels.length; i++) labels[i] = String.format(Locale.GERMANY,"%02d   %s",i+1,channels.get(i).name);
        main.removeCallbacks(hide);
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Senderliste")
                .setSingleChoiceItems(labels,station,(d,i) -> { d.dismiss(); switchTo(i); })
                .create();
        channelList = dialog;
        dialog.setOnDismissListener(d -> { channelList = null; if (player != null) showControls(true); });
        dialog.show();
        dialog.getListView().setSelection(station);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (player == null || channelList != null) return super.dispatchKeyEvent(e);
        int key = e.getKeyCode();
        boolean once = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP:
                if (once) switchTo(station+1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (once) switchTo(station-1); return true;
            case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_GUIDE:
                if (once) chooseChannel(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_HEADSETHOOK:
                if (once) togglePlay(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (once && (failed || wantsPlay != (key == KeyEvent.KEYCODE_MEDIA_PLAY))) togglePlay(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (once) { if (!visible()) showControls(true); else chrome.moveSelection(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1,true); scheduleHide(true); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (once) {
                    if (!visible()) showControls(true);
                    else if (chrome.selected() == 0) togglePlay();
                    else chooseChannel();
                }
                return true;
            default: return super.dispatchKeyEvent(e);
        }
    }

    @Override public void onBackPressed() {
        if (visible() && !failed) hideControls(); else finish();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("channel",channels.get(station).id);
        out.putBoolean("play", wantsPlay);
    }
    @Override protected void onStop() {
        ++generation;
        main.removeCallbacksAndMessages(null);
        if (channelList != null) { channelList.dismiss(); channelList = null; }
        if (streamJob != null) streamJob.cancel(true);
        if (guideJob != null) guideJob.cancel(true);
        if (player != null) { player.release(); player = null; }
        loading.setVisibility(View.GONE);
        surface.setKeepScreenOn(false);
        super.onStop();
    }
    @Override protected void onDestroy() {
        streams.shutdownNow();
        schedules.shutdownNow();
        super.onDestroy();
    }
}

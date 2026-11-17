package site.navatv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RecordingsActivity extends Activity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int MINT = 0xffd5eacb,
            INK = 0xff14211b,
            WHITE = 0xfff1f5ee,
            MUTED = 0xffb1c2b7,
            TILE = 0xff21322b,
            BORDER = 0xff42554b;
    private static final int MATCH = -1, WRAP = -2;
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale.UK)
                    .withZone(ZoneId.of("Europe/Berlin"));
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
                    .withZone(ZoneId.of("Europe/Berlin"));
    private LinearLayout shell, list;
    private String selected = "", returnTo = "";
    private int requestedEpisode = -1;
    private Typeface face, headline;
    private boolean visible, refreshing;
    private final IdleTimer idle = new IdleTimer(this, () -> true);
    private View initial;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences records;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow()
                .getDecorView()
                .setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        try {
            face = Typeface.createFromAsset(getAssets(), "assets/fonts/Manrope.ttf");
            headline =
                    Typeface.createFromAsset(
                            getAssets(), "assets/fonts/InstrumentSerif-Regular.ttf");
        } catch (RuntimeException missing) {
            face = Typeface.DEFAULT;
            headline = Typeface.SERIF;
        }
        if (state != null) {
            selected = state.getString("selected", "");
            returnTo = state.getString("returnTo", "");
            requestedEpisode = state.getInt("requestedEpisode", -1);
        } else readPlaybackRequest(getIntent());
        records = getSharedPreferences("tv-recordings-v1", Context.MODE_PRIVATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        idle.resume();
        visible = true;
        records.registerOnSharedPreferenceChangeListener(this);
        render();
        playRequested();
        boolean interrupted = false;
        for (JSONObject row : Recordings.list(this)) if (Recordings.active(row)) interrupted = true;
        if (interrupted)
            try {
                startForegroundService(new Intent(this, RecordingService.class));
            } catch (RuntimeException ignored) {
            }
        if (Recordings.hasLocalWork(this)) {
            Recordings.schedule(this);
            if (!refreshing) {
                refreshing = true;
                worker.execute(
                        () -> {
                            try {
                                Recordings.refresh(this, 120_000);
                            } finally {
                                runOnUiThread(() -> refreshing = false);
                            }
                        });
            }
        }
    }

    @Override
    protected void onPause() {
        idle.pause();
        records.unregisterOnSharedPreferenceChangeListener(this);
        visible = false;
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        idle.refresh();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        idle.interaction();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("selected", selected);
        state.putString("returnTo", returnTo);
        state.putInt("requestedEpisode", requestedEpisode);
    }

    private void readPlaybackRequest(Intent intent) {
        if (intent == null || !intent.hasExtra("playEpisode")) return;
        selected = intent.getStringExtra("recordingId");
        if (selected == null) selected = "";
        requestedEpisode = intent.getIntExtra("playEpisode", -1);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readPlaybackRequest(intent);
        if (visible) { render(); playRequested(); }
    }

    private void playRequested() {
        if (requestedEpisode < 0) return;
        int index = requestedEpisode;
        requestedEpisode = -1;
        Intent next = playbackIntent(this, Recordings.get(this, selected), index);
        if (next != null) startActivity(next);
        else Toast.makeText(this, "This recording is currently unavailable.", Toast.LENGTH_SHORT).show();
    }

    static Intent playbackIntent(Context context, JSONObject row, int index) {
        if (row == null || index < 0 || Recordings.active(row)) return null;
        if (Recordings.ARCHIVE.equals(row.optString("state"))) {
            JSONArray links = row.optJSONArray("links");
            JSONObject p = links == null ? null : links.optJSONObject(index);
            if (p == null || p.optString("detail").isEmpty()) return null;
            return new Intent(context, PlayerActivity.class).putExtra("programme", p.optString("detail"))
                    .putExtra("provider", p.optString("provider")).putExtra("title", p.optString("title"))
                    .putExtra("fromRecordings", true);
        }
        String id = row.optString("id");
        if (index != 0 || Recordings.localUri(context, id) == null) return null;
        return new Intent(context, PlayerActivity.class).putExtra("recording", id);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (visible)
            runOnUiThread(
                    () -> {
                        if (visible) render();
                    });
    }

    @Override
    public void onBackPressed() {
        if (!selected.isEmpty()) {
            returnTo = selected;
            selected = "";
            render();
        } else super.onBackPressed();
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private TextView text(String s, int size, int color, boolean serif) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setTypeface(serif ? headline : face);
        if (!serif) v.setFontVariationSettings("'wght' 570");
        return v;
    }

    private GradientDrawable shape(int fill, int line, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(r));
        g.setStroke(dp(1), line);
        return g;
    }

    private void space(LinearLayout host, int h) {
        host.addView(new View(this), new LinearLayout.LayoutParams(1, dp(h)));
    }

    private void render() {
        Object focus = getCurrentFocus() == null ? null : getCurrentFocus().getTag();
        initial = null;
        shell = column();
        shell.setFocusable(false);
        shell.setPadding(dp(43), dp(25), dp(43), dp(16));
        shell.setBackground(
                new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[] {0xff24372e, 0xff14221e, 0xff111b18}));
        setContentView(shell);
        JSONObject row = selected.isEmpty() ? null : Recordings.get(this, selected);
        if (row == null) selected = "";
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(row == null ? "Recordings" : "Your recording", 38, WHITE, true);
        top.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView back =
                pill(
                        row == null ? "‹   Home" : "‹   All recordings",
                        "back",
                        this::onBackPressed);
        top.addView(back);
        shell.addView(top);
        space(shell, 12);
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        shell.addView(line, new LinearLayout.LayoutParams(MATCH, dp(1)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        shell.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        list = column();
        list.setPadding(dp(3), dp(17), dp(3), dp(4));
        scroll.addView(list);
        if (row == null) index();
        else detail(row);
        View preferred = focus == null ? null : shell.findViewWithTag(focus);
        if (preferred == null && selected.isEmpty() && !returnTo.isEmpty())
            preferred = shell.findViewWithTag(returnTo);
        returnTo = "";
        if (preferred == null) preferred = initial == null ? back : initial;
        preferred.requestFocus();
    }

    private void index() {
        List<JSONObject> rows = Recordings.list(this);
        if (rows.isEmpty()) {
            list.setGravity(Gravity.CENTER);
            TextView dot = text("●", 30, MINT, false);
            list.addView(dot);
            space(list, 8);
            list.addView(text("No recordings yet", 30, WHITE, true));
            space(list, 6);
            list.addView(
                    text(
                            "While watching live TV, press the centre button and choose “Record”.",
                            17,
                            MUTED,
                            false));
            return;
        }
        for (JSONObject row : rows) {
            final String id = row.optString("id");
            addRow(
                    row.optString("title"),
                    metadata(row),
                    status(row),
                    id,
                    () -> {
                        selected = id;
                        render();
                    });
        }
    }

    private static String metadata(JSONObject row) {
        return row.optString("station")
                + "   ·   "
                + DAY.format(Instant.ofEpochMilli(row.optLong("start")))
                + " – "
                + HOUR.format(Instant.ofEpochMilli(row.optLong("until")));
    }

    static String status(JSONObject row) {
        switch (row.optString("state")) {
            case Recordings.FETCH:
                return "Preparing";
            case Recordings.CAPTURE:
                return row.optBoolean("stop") ? "Finishing" : "●  Recording";
            case Recordings.ARCHIVE:
                JSONArray items = row.optJSONArray("links");
                return items != null && items.length() > 1
                        ? items.length() + " programmes · Library"
                        : "Library";
            case Recordings.LOCAL:
                return "On your TV";
            case Recordings.PART:
                return "Partial recording";
            default:
                return "Unavailable";
        }
    }

    private void detail(JSONObject row) {
        final String id = row.optString("id");
        list.addView(text(metadata(row), 15, MUTED, false));
        space(list, 6);
        list.addView(text(row.optString("title"), 36, WHITE, true));
        space(list, 8);
        list.addView(text(status(row), 15, MINT, false));
        space(list, 12);
        if (Recordings.active(row)) {
            TextView stop = pill("Stop recording", "stop", () -> Recordings.stop(this, id));
            list.addView(stop, new LinearLayout.LayoutParams(WRAP, dp(46)));
            initial = stop;
            return;
        }
        JSONArray links = row.optJSONArray("links");
        if (Recordings.ARCHIVE.equals(row.optString("state")) && links != null) {
            for (int i = 0; i < links.length(); i++) {
                JSONObject p = links.optJSONObject(i);
                if (p == null) continue;
                final int episode = i;
                Runnable play = () -> {
                    Intent next = playbackIntent(this, row, episode);
                    if (next != null) startActivity(next);
                };
                if (links.length() == 1) {
                    TextView button = pill("▶   Play programme", "episode-0", play);
                    list.addView(button, new LinearLayout.LayoutParams(WRAP, dp(46)));
                    initial = button;
                } else {
                    addRow(
                            p.optString("title"),
                            HOUR.format(Instant.ofEpochMilli(p.optLong("start")))
                                    + "   ·   Library",
                            "Play  ▶",
                            "episode-" + i,
                            play);
                }
            }
        } else if (Recordings.localUri(this, id) != null) {
            TextView play =
                    pill(
                            "▶   Play recording",
                            "play",
                            () ->
                                    startActivity(
                                            new Intent(this, PlayerActivity.class)
                                                    .putExtra("recording", id)));
            list.addView(play, new LinearLayout.LayoutParams(WRAP, dp(46)));
            initial = play;
        }
        String reason = row.optString("reason");
        if (!reason.isEmpty()) {
            space(list, 8);
            list.addView(text(reason, 15, MUTED, false));
        }
        space(list, 15);
        TextView delete =
                pill(
                        "Delete recording",
                        "delete",
                        () ->
                                new AlertDialog.Builder(this)
                                        .setTitle("Delete recording?")
                                        .setMessage(row.optString("title"))
                                        .setNegativeButton("Keep", null)
                                        .setPositiveButton(
                                                "Delete",
                                                (d, w) -> {
                                                    selected = "";
                                                    Recordings.remove(this, id);
                                                    render();
                                                })
                                        .show());
        list.addView(delete, new LinearLayout.LayoutParams(WRAP, dp(44)));
        if (initial == null) initial = delete;
    }

    private TextView pill(String title, String tag, Runnable onClick) {
        TextView v = text(title, 15, WHITE, false);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine();
        v.setPadding(dp(20), dp(6), dp(20), dp(6));
        v.setMinHeight(dp(40));
        v.setTag(tag);
        v.setFocusable(true);
        v.setClickable(true);
        v.setBackground(shape(TILE, BORDER, 26));
        v.setOnFocusChangeListener(
                (t, f) -> {
                    v.setBackground(shape(f ? MINT : TILE, f ? MINT : BORDER, 26));
                    v.setTextColor(f ? INK : WHITE);
                });
        v.setOnClickListener(w -> onClick.run());
        return v;
    }

    private void addRow(String title, String sub, String state, String tag, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(18), dp(10), dp(18), dp(10));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(shape(TILE, BORDER, 14));
        row.setFocusable(true);
        row.setClickable(true);
        row.setTag(tag);
        LinearLayout labels = column();
        TextView heading = text(title, 20, WHITE, false);
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        TextView meta = text(sub, 13, MUTED, false);
        labels.addView(heading);
        labels.addView(meta);
        row.addView(labels, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView badge = text(state, 14, MINT, false);
        badge.setPadding(dp(12), 0, 0, 0);
        row.addView(badge);
        row.setOnClickListener(v -> click.run());
        row.setOnFocusChangeListener(
                (v, f) -> {
                    row.setBackground(shape(f ? MINT : TILE, f ? MINT : BORDER, 14));
                    heading.setTextColor(f ? INK : WHITE);
                    meta.setTextColor(f ? INK : MUTED);
                    badge.setTextColor(f ? INK : MINT);
                });
        list.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        space(list, 7);
        if (initial == null) initial = row;
    }
}

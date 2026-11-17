package site.navatv.launcher;

import static site.navatv.launcher.PlayerChrome.LAVENDER;
import static site.navatv.launcher.PlayerChrome.MUTED;
import static site.navatv.launcher.PlayerChrome.NAVY;
import static site.navatv.launcher.PlayerChrome.PEARL;
import static site.navatv.launcher.PlayerChrome.VIOLET_EDGE;
import static site.navatv.launcher.PlayerChrome.oneLine;

import site.navatv.launcher.PlayerChrome.ActionButton;
import site.navatv.launcher.PlayerChrome.Icon;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import java.util.ArrayList;
import java.util.List;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** The on-air strip, the channel list and the recording duration picker share Senior's type and colours. */
final class LiveTvControls {
    private static final int INK = 0xff192a20, WHITE = 0xfffaf7ec, MINT = 0xffd9e9cc;
    private static final int QUIET = 0xffbdcbbc, SHELL = 0xf319251f, EDGE = 0xff526457;
    static final int LIVE = 0, SHIFTED = 1, PAUSED = 2, ARCHIVE = 3;
    static final int BUTTON_PLAYBACK = 0, BUTTON_ACTION = 1, BUTTON_LIBRARY = 2;

    /** One line of the channel list. */
    static final class Row {
        int index, fraction = -1;
        String name, now = "", until = "", next = "";
        boolean favourite, playing, loaded;
    }

    private final LiveTvActivity activity;
    private final PlayerChrome chrome;
    private final FrameLayout root;
    final SurfaceView surface;
    private final FrameLayout overlay;
    private final LinearLayout panel, timeline;
    private final TextView number, channelTag, station, badge, title, startTime, endTime, nextLabel, next;
    private final TextView message, digits, panelClock, panelCount, tabMine, tabAll, emptyList;
    private final ActionButton playback, record, library;
    private final ProgressBar progress, spinner;
    private final ListView rowsView;
    private final List<Row> rows = new ArrayList<>();
    private final Typeface serif;
    private float aspect = 16f / 9;
    private boolean expanded, shown, panelShown;
    private int selectedRow;
    Dialog durationDialog;

    LiveTvControls(LiveTvActivity activity) {
        this.activity = activity;
        Typeface display;
        try { display = Typeface.createFromAsset(activity.getAssets(), "assets/fonts/InstrumentSerif-Regular.ttf"); }
        catch (RuntimeException unavailableFont) { display = Typeface.SERIF; }
        serif = display;
        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.setFocusableInTouchMode(true);
        surface = new SurfaceView(activity);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v,l,t,r,b,oldL,oldT,oldR,oldB) -> {
            if (r-l != oldR-oldL || b-t != oldB-oldT) sizeVideo(aspect);
        });
        root.setOnClickListener(v -> activity.toggleStrip());

        overlay = new FrameLayout(activity);
        overlay.setClipChildren(false);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        chrome = new PlayerChrome(activity, overlay, "Live TV");

        LinearLayout details = new LinearLayout(activity);
        details.setOrientation(LinearLayout.VERTICAL);
        overlay.addView(details, chrome.at(-1, -2, Gravity.TOP | Gravity.START, 56, 50, 128, 0));
        LinearLayout eyebrow = new LinearLayout(activity);
        eyebrow.setGravity(Gravity.CENTER_VERTICAL);
        details.addView(eyebrow);
        channelTag = chrome.label(11, 770, LAVENDER);
        channelTag.setLetterSpacing(.14f);
        eyebrow.addView(channelTag);
        TextView dot = chrome.label(11, 680, LAVENDER);
        dot.setPadding(chrome.dp(6), 0, chrome.dp(6), 0);
        dot.setText("·");
        eyebrow.addView(dot);
        badge = chrome.label(11, 770, LAVENDER);
        badge.setLetterSpacing(.14f);
        eyebrow.addView(badge);

        station = chrome.label(37, 800, PEARL);
        station.setLetterSpacing(-.045f);
        station.setShadowLayer(chrome.dp(4), 0, chrome.dp(1), 0x65000000);
        station.setPadding(0, chrome.dp(2), 0, 0);
        oneLine(station);
        details.addView(station);
        title = chrome.label(18, 650, PEARL);
        title.setShadowLayer(chrome.dp(3), 0, chrome.dp(1), 0x80000000);
        oneLine(title);
        details.addView(title);

        timeline = new LinearLayout(activity);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        timeline.setPadding(0, chrome.dp(5), 0, 0);
        details.addView(timeline);
        startTime = chrome.label(12, 600, MUTED);
        timeline.addView(startTime);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(LAVENDER));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x807d7991));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(chrome.dp(74), chrome.dp(3));
        bar.setMargins(chrome.dp(8), 0, chrome.dp(8), 0);
        timeline.addView(progress, bar);
        endTime = chrome.label(12, 600, MUTED);
        timeline.addView(endTime);
        LinearLayout after = new LinearLayout(activity);
        after.setGravity(Gravity.CENTER_VERTICAL);
        after.setPadding(0, chrome.dp(5), 0, 0);
        details.addView(after);
        nextLabel = chrome.label(10, 780, LAVENDER);
        nextLabel.setLetterSpacing(.10f);
        nextLabel.setText("UP NEXT");
        after.addView(nextLabel);
        next = chrome.label(13, 570, MUTED);
        next.setPadding(chrome.dp(7), 0, 0, 0);
        oneLine(next);
        after.addView(next);

        number = chrome.label(148, 800, PEARL);
        number.setLetterSpacing(-.065f);
        number.setGravity(Gravity.END);
        number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        number.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft && right > left) {
                number.getPaint().setShader(new LinearGradient(0, 0, right - left, chrome.dp(32),
                        new int[] {0xff99a7ff, 0xffbd9cf7, 0xfff0a9bf, 0xfff3b59e}, null, Shader.TileMode.CLAMP));
                number.invalidate();
            }
        });
        overlay.addView(number, chrome.at(-2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, 68, 91));

        playback = chrome.button("Pause", Icon.PAUSE, activity::playbackAction);
        record = chrome.button("Record", Icon.RECORD, activity::stripAction);
        library = chrome.button("Library", Icon.LIBRARY, activity::openChannelLibrary);
        chrome.setActions(BUTTON_PLAYBACK, playback, record, library);
        overlay.setVisibility(View.GONE);

        spinner = new ProgressBar(activity);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(LAVENDER));
        root.addView(spinner, new FrameLayout.LayoutParams(chrome.dp(46), chrome.dp(46), Gravity.CENTER));
        spinner.setVisibility(View.GONE);

        message = chrome.label(17, 600, WHITE);
        message.setGravity(Gravity.CENTER);
        message.setPadding(chrome.dp(21), chrome.dp(12), chrome.dp(21), chrome.dp(12));
        message.setBackground(chrome.shape(NAVY, 14, VIOLET_EDGE));
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        mp.setMargins(chrome.dp(48), 0, chrome.dp(48), 0);
        root.addView(message, mp);
        message.setVisibility(View.GONE);

        digits = chrome.label(15, 620, LAVENDER);
        digits.setGravity(Gravity.END);
        digits.setPadding(chrome.dp(20), chrome.dp(10), chrome.dp(20), chrome.dp(12));
        digits.setBackground(chrome.shape(NAVY, 16, VIOLET_EDGE));
        FrameLayout.LayoutParams digitPlace = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        digitPlace.setMargins(0, chrome.dp(30), chrome.dp(40), 0);
        root.addView(digits, digitPlace);
        digits.setVisibility(View.GONE);

        // Channel list
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xff12121e, 0xfc19192a, 0xf4212134}));
        panel.setElevation(chrome.dp(8));
        panel.setClickable(true);
        root.addView(panel, new FrameLayout.LayoutParams(chrome.dp(458), -1, Gravity.START));
        LinearLayout mast = new LinearLayout(activity);
        mast.setGravity(Gravity.CENTER_VERTICAL);
        mast.setPadding(chrome.dp(28), chrome.dp(20), chrome.dp(24), 0);
        panel.addView(mast);
        TextView liveBrand = chrome.label(11, 760, LAVENDER);
        liveBrand.setLetterSpacing(.14f);
        liveBrand.setText("LIVE TV");
        mast.addView(liveBrand, new LinearLayout.LayoutParams(0, -2, 1));
        panelClock = chrome.label(14, 620, MUTED);
        mast.addView(panelClock);
        LinearLayout panelHead = new LinearLayout(activity);
        panelHead.setGravity(Gravity.BOTTOM);
        panelHead.setPadding(chrome.dp(27), chrome.dp(2), chrome.dp(24), chrome.dp(2));
        panel.addView(panelHead);
        TextView heading = chrome.label(33, 580, WHITE);
        heading.setTypeface(serif);
        heading.setText("Channels");
        panelHead.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        panelCount = chrome.label(13, 600, MUTED);
        panelCount.setPadding(0, 0, 0, chrome.dp(5));
        panelHead.addView(panelCount);
        LinearLayout tabs = new LinearLayout(activity);
        tabs.setPadding(chrome.dp(28), chrome.dp(8), chrome.dp(24), chrome.dp(10));
        panel.addView(tabs);
        tabMine = chrome.label(13, 720, WHITE);
        tabAll = chrome.label(13, 720, WHITE);
        for (TextView tab : new TextView[] {tabMine, tabAll}) {
            tab.setPadding(chrome.dp(14), chrome.dp(7), chrome.dp(14), chrome.dp(7));
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-2, -2);
            place.rightMargin = chrome.dp(8);
            tabs.addView(tab, place);
        }
        tabMine.setOnClickListener(v -> activity.showChannelTab(true));
        tabAll.setOnClickListener(v -> activity.showChannelTab(false));
        FrameLayout listBox = new FrameLayout(activity);
        panel.addView(listBox, new LinearLayout.LayoutParams(-1, 0, 1));
        rowsView = new ListView(activity);
        rowsView.setDivider(null);
        rowsView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        rowsView.setFocusable(false);
        rowsView.setItemsCanFocus(false);
        rowsView.setVerticalScrollBarEnabled(false);
        rowsView.setPadding(chrome.dp(16), chrome.dp(2), chrome.dp(14), chrome.dp(10));
        rowsView.setClipToPadding(false);
        rowsView.setAdapter(adapter);
        rowsView.setOnItemClickListener((parent, v, position, id) -> activity.chooseChannelRow(position));
        listBox.addView(rowsView, new FrameLayout.LayoutParams(-1, -1));
        emptyList = chrome.label(15, 560, QUIET);
        emptyList.setGravity(Gravity.CENTER);
        emptyList.setPadding(chrome.dp(34), 0, chrome.dp(34), 0);
        emptyList.setText("No favourite channels yet.\nIn “All channels”, press and hold OK to add a favourite.");
        listBox.addView(emptyList, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = chrome.label(12, 600, MUTED);
        hint.setPadding(chrome.dp(28), chrome.dp(9), chrome.dp(12), chrome.dp(14));
        hint.setText("OK  Watch     •     Hold OK  Favourite     •     ◀ ▶  Lists");
        panel.addView(hint);
        panel.setVisibility(View.GONE);

        activity.setContentView(root);
        root.requestFocus();
    }

    void sizeVideo(float ratio) {
        if (ratio <= 0) return;
        aspect = ratio;
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        if ((float) w / h > ratio) w = Math.round(h * ratio);
        else h = Math.round(w / ratio);
        surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    void update(int channelNumber, String channelName, int kind, String programme, String from, String until, int fraction,
            String following, String time, String action, boolean paused, boolean failed, boolean libraryAvailable) {
        String slot = String.format(java.util.Locale.GERMANY, "%02d", channelNumber);
        set(number, slot);
        set(channelTag, "CHANNEL " + slot);
        set(station, channelName);
        set(badge, kind == LIVE ? "LIVE" : kind == SHIFTED ? "TIME SHIFT" : kind == PAUSED ? "PAUSED" : "LIBRARY");
        set(title, programme);
        title.setVisibility(programme.isEmpty() || "Live guide".equals(programme) ? View.GONE : View.VISIBLE);
        set(startTime, from);
        set(endTime, until);
        set(next, following);
        set(chrome.clock, time);
        nextLabel.setVisibility(following.isEmpty() ? View.GONE : View.VISIBLE);
        next.setVisibility(following.isEmpty() ? View.GONE : View.VISIBLE);
        timeline.setVisibility(from.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(fraction >= 0 ? View.VISIBLE : View.INVISIBLE);
        if (fraction >= 0) progress.setProgress(Math.min(1000, fraction));

        playback.update(failed ? "Reload" : paused ? "Playback" : "Pause",
                failed ? Icon.RETRY : paused ? Icon.PLAY : Icon.PAUSE, true);
        record.update(failed ? "Record" : action, Icon.RECORD, !failed);
        library.update("Library", Icon.LIBRARY, libraryAvailable);
    }

    private static void set(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    void show(boolean manuallyOpened) {
        expanded = manuallyOpened;
        if (!shown) {
            shown = true;
            overlay.animate().cancel();
            if (overlay.getVisibility() != View.VISIBLE) {
                overlay.setAlpha(0);
            }
            overlay.setVisibility(View.VISIBLE);
            select(BUTTON_PLAYBACK);
            overlay.animate().alpha(1).setDuration(180).start();
        }
    }

    void select(int button) {
        chrome.select(button, shown && !panelShown && durationDialog == null);
    }

    boolean moveSelection(int direction) {
        return chrome.moveSelection(direction, shown && !panelShown && durationDialog == null);
    }

    int selected() { return chrome.selected(); }

    boolean isExpanded() { return expanded; }
    boolean isVisible() { return shown; }

    void hide() {
        expanded = false;
        if (!shown) return;
        shown = false;
        overlay.animate().alpha(0).setDuration(150).withEndAction(() -> {
            if (!shown) overlay.setVisibility(View.GONE);
        }).start();
        root.requestFocus();
    }

    void message(String value) {
        message.setText(value);
        message.setVisibility(value == null ? View.GONE : View.VISIBLE);
    }

    void busy(boolean loading) { spinner.setVisibility(loading ? View.VISIBLE : View.GONE); }

    /** Shows the typed channel number together with the station it stands for. */
    void digits(String typed, String name) {
        if (typed == null) { digits.setVisibility(View.GONE); return; }
        SpannableString value = new SpannableString(typed + "\n" + name);
        value.setSpan(new RelativeSizeSpan(2.7f), 0, typed.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        value.setSpan(new android.text.style.ForegroundColorSpan(WHITE), 0, typed.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        digits.setText(value);
        digits.setVisibility(View.VISIBLE);
    }

    // ---- Channel list -------------------------------------------------------------------------

    boolean listVisible() { return panelShown; }

    void showList(List<Row> content, int selection, boolean mine, String time) {
        rows.clear();
        rows.addAll(content);
        selectedRow = Math.max(0, Math.min(selection, rows.size() - 1));
        paintTab(tabMine, "My channels", mine);
        paintTab(tabAll, "All channels", !mine);
        panelCount.setText(rows.size() + " Channel");
        emptyList.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        panelClock.setText(time);
        adapter.notifyDataSetChanged();
        if (!panelShown) {
            panelShown = true;
            panel.animate().cancel();
            panel.setAlpha(0);
            panel.setTranslationX(-chrome.dp(70));
            panel.setVisibility(View.VISIBLE);
            panel.animate().alpha(1).translationX(0).setDuration(190).start();
        }
        rowsView.setSelectionFromTop(selectedRow, chrome.dp(122));
    }

    void refreshList(String time) {
        panelClock.setText(time);
        adapter.notifyDataSetChanged();
    }

    int listSelection() { return selectedRow; }

    void moveList(int direction) {
        if (rows.isEmpty()) return;
        selectedRow = Math.floorMod(selectedRow + direction, rows.size());
        adapter.notifyDataSetChanged();
        rowsView.setSelectionFromTop(selectedRow, chrome.dp(122));
    }

    void hideList() {
        if (!panelShown) return;
        panelShown = false;
        panel.animate().alpha(0).translationX(-chrome.dp(70)).setDuration(160).withEndAction(() -> {
            if (!panelShown) panel.setVisibility(View.GONE);
        }).start();
    }

    private void paintTab(TextView tab, String caption, boolean active) {
        tab.setText(caption);
        tab.setTextColor(active ? PEARL : MUTED);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(chrome.dp(17));
        pill.setColor(active ? 0xff39374e : 0x00000000);
        pill.setStroke(chrome.dp(1), active ? 0xff827eaa : VIOLET_EDGE);
        tab.setBackground(pill);
    }

    private static final class Cells {
        LinearLayout box;
        TextView number, name, now, next, until, onScreen;
        ProgressBar bar;
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return rows.get(position).index; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Cells cells;
            if (recycled == null) {
                cells = new Cells();
                FrameLayout frame = new FrameLayout(activity);
                frame.setPadding(0, chrome.dp(2), 0, chrome.dp(2));
                cells.box = new LinearLayout(activity);
                cells.box.setGravity(Gravity.CENTER_VERTICAL);
                cells.box.setPadding(chrome.dp(8), chrome.dp(9), chrome.dp(14), chrome.dp(9));
                frame.addView(cells.box, new FrameLayout.LayoutParams(-1, -2));
                cells.number = chrome.label(21, 740, LAVENDER);
                cells.number.setFontFeatureSettings("tnum");
                cells.number.setGravity(Gravity.CENTER);
                cells.box.addView(cells.number, new LinearLayout.LayoutParams(chrome.dp(45), chrome.dp(44)));
                LinearLayout column = new LinearLayout(activity);
                column.setOrientation(LinearLayout.VERTICAL);
                column.setPadding(chrome.dp(10), 0, chrome.dp(10), 0);
                cells.box.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
                cells.name = chrome.label(18, 740, PEARL);
                cells.now = chrome.label(14, 540, QUIET);
                cells.next = chrome.label(12, 560, QUIET);
                for (TextView line : new TextView[] {cells.name, cells.now, cells.next}) { oneLine(line); column.addView(line); }
                cells.now.setPadding(0, chrome.dp(2), 0, 0);
                cells.next.setPadding(0, chrome.dp(4), 0, 0);
                LinearLayout side = new LinearLayout(activity);
                side.setOrientation(LinearLayout.VERTICAL);
                side.setGravity(Gravity.END);
                cells.box.addView(side, new LinearLayout.LayoutParams(chrome.dp(74), -2));
                cells.onScreen = chrome.label(10, 780, LAVENDER);
                cells.onScreen.setGravity(Gravity.END);
                cells.onScreen.setLetterSpacing(.09f);
                cells.onScreen.setText("●  ON SCREEN");
                side.addView(cells.onScreen);
                cells.until = chrome.label(12, 620, QUIET);
                cells.until.setGravity(Gravity.END);
                side.addView(cells.until, new LinearLayout.LayoutParams(-1, -2));
                cells.bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
                cells.bar.setMax(1000);
                LinearLayout.LayoutParams barPlace = new LinearLayout.LayoutParams(-1, chrome.dp(3));
                barPlace.topMargin = chrome.dp(7);
                side.addView(cells.bar, barPlace);
                frame.setTag(cells);
                recycled = frame;
            } else cells = (Cells) recycled.getTag();
            Row row = rows.get(position);
            boolean chosen = position == selectedRow;
            cells.box.setBackground(chosen ? chrome.shape(0xffe4e2fb, 15, PEARL)
                    : row.playing ? chrome.shape(0xff2a293b, 15, VIOLET_EDGE) : null);
            cells.number.setText(String.format(java.util.Locale.GERMANY, "%02d", row.index + 1));
            cells.number.setTextColor(chosen ? PEARL : LAVENDER);
            cells.number.setBackground(chrome.shape(chosen ? 0xff45415f : 0xff262638, 10, chosen ? 0xff45415f : VIOLET_EDGE));
            cells.name.setText(row.favourite ? row.name + "  ★" : row.name);
            cells.name.setTextColor(chosen ? 0xff171526 : PEARL);
            cells.now.setText(row.now.isEmpty() ? (row.loaded ? "No programme title available" : "Loading the guide…") : row.now);
            cells.now.setTextColor(chosen ? 0xff444055 : MUTED);
            cells.next.setVisibility(chosen && !row.next.isEmpty() ? View.VISIBLE : View.GONE);
            cells.next.setText(row.next);
            cells.next.setTextColor(0xff544f67);
            cells.onScreen.setVisibility(row.playing ? View.VISIBLE : View.GONE);
            cells.onScreen.setTextColor(chosen ? 0xff4f496e : LAVENDER);
            cells.until.setText(row.until);
            cells.until.setVisibility(row.until.isEmpty() ? View.GONE : View.VISIBLE);
            cells.until.setTextColor(chosen ? 0xff444055 : MUTED);
            cells.bar.setVisibility(row.fraction >= 0 ? View.VISIBLE : View.INVISIBLE);
            if (row.fraction >= 0) cells.bar.setProgress(row.fraction);
            cells.bar.setProgressTintList(ColorStateList.valueOf(chosen ? 0xff59537c : LAVENDER));
            cells.bar.setProgressBackgroundTintList(ColorStateList.valueOf(chosen ? 0x35443b64 : VIOLET_EDGE));
            return recycled;
        }
    };

    void durations(String channel, String programme, long programmeEnd, DurationChoice selected) {
        if (durationDialog != null) return;
        Dialog dialog = new Dialog(activity);
        durationDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(chrome.dp(24), chrome.dp(18), chrome.dp(24), chrome.dp(16));
        box.setBackground(chrome.shape(0xff1e2d24, 20, EDGE));
        TextView stationLabel = chrome.label(13, 680, MINT);
        stationLabel.setText(channel);
        box.addView(stationLabel);
        TextView heading = chrome.label(26, 720, WHITE);
        heading.setText("How long would you like to record?");
        box.addView(heading);
        if (!programme.isEmpty()) {
            TextView subtitle = chrome.label(16, 500, QUIET);
            subtitle.setText(programme);
            oneLine(subtitle);
            box.addView(subtitle);
        }

        long remaining = programmeEnd - System.currentTimeMillis();
        boolean untilEnd = remaining > 1000 && remaining <= 360 * 60000L;
        int[] durations = {1, 5, 30, 60};
        TextView first = null;
        if (untilEnd) {
            first = choice(box, "Until the programme ends  ·  " + LiveTvActivity.clock(programmeEnd), () -> {
                dialog.dismiss();
                selected.accept(Math.max(0, programmeEnd - System.currentTimeMillis()));
            });
        }
        for (int minutes : durations) {
            TextView option = choice(box, minutes == 1 ? "1 minute" : minutes + " minutes", () -> {
                dialog.dismiss();
                selected.accept(minutes * 60000L);
            });
            if (first == null) first = option;
        }
        choice(box, "Choose a different duration…", () -> {
            box.removeAllViews();
            box.addView(stationLabel);
            box.addView(heading);
            final int[] value = {15};
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, chrome.dp(15), 0, chrome.dp(11));
            box.addView(row);
            TextView minus = chrome.label(25, 700, WHITE);
            TextView amount = chrome.label(21, 650, WHITE);
            TextView plus = chrome.label(25, 700, WHITE);
            for (TextView v : new TextView[] {minus, amount, plus}) {
                v.setGravity(Gravity.CENTER);
                row.addView(v, new LinearLayout.LayoutParams(v == amount ? 0 : chrome.dp(53), chrome.dp(48), v == amount ? 1f : 0f));
            }
            minus.setText("−");
            plus.setText("+");
            minus.setContentDescription("Fewer minutes");
            plus.setContentDescription("Add minutes");
            setFocusStyle(minus);
            setFocusStyle(plus);
            TextView start = choice(box, "Start recording", () -> {
                dialog.dismiss();
                selected.accept(value[0] * 60000L);
            });
            Runnable update = () -> amount.setText(value[0] == 1 ? "1 minute" : value[0] + " minutes");
            minus.setOnClickListener(v -> { value[0] = Math.max(1, value[0] - 5); update.run(); });
            plus.setOnClickListener(v -> { value[0] = value[0] == 1 ? 5 : Math.min(360, value[0] + 5); update.run(); });
            update.run();
            start.requestFocus();
        });
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(box);
        dialog.setContentView(scroll);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setDimAmount(.62f);
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            win.getDecorView().setSystemUiVisibility(root.getRootView().getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        dialog.setOnDismissListener(d -> { durationDialog = null; activity.durationDismissed(); });
        dialog.show();
        if (win != null) win.setLayout(Math.min(chrome.dp(448), root.getWidth() - chrome.dp(32)), -2);
        if (first != null) first.requestFocus();
    }

    interface DurationChoice { void accept(long milliseconds); }

    private TextView choice(LinearLayout box, String caption, Runnable action) {
        TextView option = chrome.label(17, 650, WHITE);
        option.setText(caption);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(chrome.dp(14), 0, chrome.dp(14), 0);
        setFocusStyle(option);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, chrome.dp(39));
        p.topMargin = chrome.dp(4);
        box.addView(option, p);
        option.setOnClickListener(v -> action.run());
        return option;
    }

    private void setFocusStyle(TextView view) {
        view.setFocusable(true);
        view.setBackground(chrome.shape(0xff30443a, 15, 0xff738473));
        view.setTextColor(WHITE);
        view.setOnFocusChangeListener((v, selected) -> {
            GradientDrawable button = chrome.shape(selected ? MINT : 0xff30443a, 15, selected ? WHITE : 0xff738473);
            if (selected) button.setStroke(chrome.dp(2), WHITE);
            view.setBackground(button);
            view.setTextColor(selected ? INK : WHITE);
        });
    }

}

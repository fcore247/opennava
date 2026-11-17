package site.navatv.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class TvProgrammeActivity extends Activity {
    private static final int INK = 0xff172920, SHELL = 0xff263b31, WHITE = 0xfffaf8e9;
    private static final int MINT = 0xffd1dfbc, DIM = 0xffc0cec1, EDGE = 0xff4c6153, FOCUS = 0xfff3efdc;
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).withZone(ZONE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.UK);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.UK).withZone(ZONE);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final List<LiveGuide.Channel> channels = LiveGuide.channels(BuildConfig.RTL_ENABLED);
    private final IdleTimer idle = new IdleTimer(this, () -> true);
    private Future<?> lookup;
    private int request;
    private int station;
    private LocalDate day;
    private boolean reminders, resumed;
    private long selectedStart, nextRefresh;
    private List<LiveGuide.Programme> schedule = Collections.emptyList();
    private Typeface regular, bold, serif;
    private TextView selectStation, saved, heading, date, stationName, previousDay, nextDay;
    private final List<Runnable> liveIndicators = new ArrayList<>();
    private long openingUpDownTime = -1;
    private LinearLayout dayBar, rows;
    private ScrollView scroll;
    private Dialog dialog;
    private View selectedRow;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            for (Runnable update : liveIndicators) update.run();
            if (System.currentTimeMillis() >= nextRefresh && dialog == null) {
                if (reminders) showReminders(false);
                else load(false);
            }
            main.postDelayed(this, 15000);
        }
    };

    public static Intent intent(Context context, String channelId) {
        Intent open = new Intent(context, TvProgrammeActivity.class);
        if (channelId != null) open.putExtra("channel", channelId);
        return open;
    }
    public static void open(Context context, String channelId) { context.startActivity(intent(context, channelId)); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        openingUpDownTime = getIntent().getLongExtra("openingUpDownTime", -1L);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        String last = getSharedPreferences("LiveTvActivity", MODE_PRIVATE).getString("channel", "daserste");
        String channelId = getIntent().getStringExtra("channel");
        if (channelId == null) channelId = last;
        day = LocalDate.now(ZONE);
        if (state != null) {
            channelId = state.getString("channel", channelId);
            day = LocalDate.ofEpochDay(state.getLong("day", day.toEpochDay()));
            reminders = state.getBoolean("reminders");
            selectedStart = state.getLong("selection");
        }
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).id.equals(channelId)) station = i;
        regular = font("Manrope.ttf", 495);
        bold = font("Manrope.ttf", 710);
        serif = font("InstrumentSerif-Regular.ttf", 0);
        layout();
        if (reminders) showReminders(true); else load(true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("channel", channel().id);
        state.putLong("day", day.toEpochDay());
        state.putBoolean("reminders", reminders);
        state.putLong("selection", selectedStart);
    }

    private LiveGuide.Channel channel() { return channels.get(station); }
    String companionChannel() { return channel().id; }
    void companionRemindersChanged() {
        if (!resumed || dialog != null) return;
        boolean rowFocused = getCurrentFocus() != null && getCurrentFocus().getParent() == rows;
        if (reminders) showReminders(rowFocused);
        else { refreshHeader(); if (!schedule.isEmpty()) showSchedule(rowFocused); }
    }
    boolean companionReminders() { return reminders; }

    private void layout() {
        LinearLayout root = new LinearLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff24372e, 0xff14241d, 0xff102019}));

        FrameLayout cover = new FrameLayout(this);
        root.addView(cover, new LinearLayout.LayoutParams(0, -1, .33f));
        cover.setBackgroundColor(INK);
        ImageView landscape = new ImageView(this);
        landscape.setScaleType(ImageView.ScaleType.CENTER_CROP);
        landscape.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int hour = LocalTime.now(ZONE).getHour();
        String scene = hour < 6 || hour >= 21 ? "night" : hour < 10 ? "dawn" : hour >= 16 ? "evening" : "day";
        try (InputStream file = getAssets().open("assets/landschaften/" + scene + ".jpg")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            landscape.setImageBitmap(BitmapFactory.decodeStream(file, null, options));
            cover.addView(landscape, new FrameLayout.LayoutParams(-1, -1));
        } catch (IOException ignored) { /* The panel's solid background also supports the UI. */ }
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xc0182b23, 0x481d3227, 0xbb182e23, 0xff13241b}));
        cover.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout navigation = column();
        navigation.setPadding(dp(32), dp(29), dp(26), dp(28));
        cover.addView(navigation, new FrameLayout.LayoutParams(-1, -1));
        TextView title = text("TV guide", 32, serif, WHITE);
        title.setMaxLines(1);
        title.setAutoSizeTextTypeUniformWithConfiguration(20, 32, 1, TypedValue.COMPLEX_UNIT_SP);
        navigation.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));
        View space = new View(this);
        navigation.addView(space, new LinearLayout.LayoutParams(1, 0, 1));
        TextView label = text("CHANNEL", 13, bold, MINT);
        label.setLetterSpacing(.12f);
        navigation.addView(label);
        stationName = text("", 38, serif, WHITE);
        stationName.setMaxLines(3);
        stationName.setEllipsize(TextUtils.TruncateAt.END);
        navigation.addView(stationName, margin(-1, -2, 3));
        selectStation = button("Change channel    ›", this::chooseStation);
        navigation.addView(selectStation, margin(-1, 45, 14));
        saved = button("Reminder set", () -> showReminders(true));
        navigation.addView(saved, margin(-1, 45, 7));

        LinearLayout contents = column();
        contents.setPadding(dp(23), dp(24), dp(31), dp(17));
        root.addView(contents, new LinearLayout.LayoutParams(0, -1, .67f));
        LinearLayout section = line();
        LinearLayout pageTitle = column();
        heading = text("", 34, serif, WHITE);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        date = text("", 15, regular, DIM);
        pageTitle.addView(heading);
        pageTitle.addView(date, margin(-1, -2, 3));
        section.addView(pageTitle, weighted());
        dayBar = line();
        previousDay = button("‹", () -> changeDay(-1));
        previousDay.setContentDescription("Previous day");
        nextDay = button("›", () -> changeDay(1));
        nextDay.setContentDescription("Next day");
        for (TextView step : new TextView[]{previousDay, nextDay}) {
            step.setGravity(Gravity.CENTER);
            step.setPadding(0, 0, 0, dp(2));
            step.setTextSize(26);
        }
        dayBar.addView(previousDay, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout.LayoutParams arrow = new LinearLayout.LayoutParams(dp(38), dp(38));
        arrow.leftMargin = dp(6);
        dayBar.addView(nextDay, arrow);
        section.addView(dayBar);
        contents.addView(section, new LinearLayout.LayoutParams(-1, dp(65)));

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        rows = column();
        rows.setPadding(0, 0, 0, dp(8));
        scroll.addView(rows);
        LinearLayout.LayoutParams viewport = new LinearLayout.LayoutParams(-1, 0, 1);
        viewport.topMargin = dp(13);
        contents.addView(scroll, viewport);
        setContentView(root);
        selectStation.requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP && openingUpDownTime >= 0) {
            if (event.getDownTime() == openingUpDownTime
                    && (event.getRepeatCount() > 0 || event.getAction() == KeyEvent.ACTION_UP)) {
                if (event.getAction() == KeyEvent.ACTION_UP) openingUpDownTime = -1;
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) openingUpDownTime = -1;
        }
        return super.dispatchKeyEvent(event);
    }

    private void changeDay(int step) {
        day = day.plusDays(step);
        selectedStart = 0;
        load(true);
    }

    private void load(boolean focusProgramme) {
        reminders = false;
        int generation = ++request;
        cancelLookup();
        nextRefresh = System.currentTimeMillis() + 180000;
        refreshHeader();
        if (focusProgramme || schedule.isEmpty()) {
            selectedRow = null;
            clearRows();
            notice("Loading the guide…");
        }
        LiveGuide.Channel chosen = channel();
        LocalDate requested = day;
        long now = System.currentTimeMillis();
        long at = requested.equals(LocalDate.now(ZONE)) ? now : requested.atTime(LocalTime.NOON).atZone(ZONE).toInstant().toEpochMilli();
        lookup = worker.submit(() -> {
            try {
                List<LiveGuide.Programme> batch = LiveGuide.programmes(chosen, at);
                List<LiveGuide.Programme> matching = new ArrayList<>();
                long from = requested.atStartOfDay(ZONE).toInstant().toEpochMilli();
                long to = requested.plusDays(1).atTime(5, 30).atZone(ZONE).toInstant().toEpochMilli();
                for (LiveGuide.Programme item : batch) if (item.end > from && item.start < to) matching.add(item);
                main.post(() -> {
                    if (generation != request || isFinishing() || isDestroyed()) return;
                    schedule = matching;
                    showSchedule(focusProgramme || (getCurrentFocus() != null && getCurrentFocus().getParent() == rows));
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (generation != request || isFinishing() || isDestroyed()) return;
                    if (!focusProgramme && !schedule.isEmpty()) return;
                    schedule = Collections.emptyList();
                    clearRows();
                    notice("Could not load the schedule.");
                    action("Reload", () -> load(true), true);
                    action("Watch live", this::openLive, false);
                });
            }
        });
    }

    private void refreshHeader() {
        stationName.setText(channel().name);
        int count = ProgrammeReminders.entries(this).size();
        saved.setText(count == 0 ? "Reminder set" : "Reminder set  ·  " + count);
        dayBar.setVisibility(reminders ? View.GONE : View.VISIBLE);
        LocalDate today = LocalDate.now(ZONE);
        String relative = day.equals(today) ? "Today" : day.equals(today.plusDays(1)) ? "Tomorrow"
                : day.equals(today.minusDays(1)) ? "Yesterday" : day.format(DateTimeFormatter.ofPattern("EEEE", Locale.UK));
        heading.setText(reminders ? "Reminder set" : relative);
        date.setText(reminders ? "All channels" : DATE.format(day));
        previousDay.setVisibility(day.isAfter(today.minusDays(7)) ? View.VISIBLE : View.INVISIBLE);
        nextDay.setVisibility(day.isBefore(today.plusDays(7)) ? View.VISIBLE : View.INVISIBLE);
        View dayStep = previousDay.getVisibility() == View.VISIBLE ? previousDay : nextDay;
        selectStation.setNextFocusUpId(reminders ? View.NO_ID : dayStep.getId());
    }

    private void showSchedule(boolean focus) {
        Integer priorOffset = selectedRow != null && selectedRow.getParent() == rows
                ? selectedRow.getTop() - scroll.getScrollY() : null;
        clearRows();
        selectedRow = null;
        if (schedule.isEmpty()) {
            notice("No listings are available for this day.");
            action("Watch live", this::openLive, focus);
            return;
        }
        long now = System.currentTimeMillis();
        List<ProgrammeReminders.Entry> savedItems = ProgrammeReminders.entries(this);
        View current = null, chosen = null, firstUpcoming = null;
        long boundary = Long.MAX_VALUE;
        for (LiveGuide.Programme p : schedule) {
            boolean onAir = p.start <= now && now < p.end;
            boolean future = p.start > now;
            boolean marked = false;
            for (ProgrammeReminders.Entry entry : savedItems) if (entry.matches(channel().id, p)) { marked = true; break; }
            String label = onAir ? "Watch live now" : future ? (marked ? "Remove reminder" : "Set reminder")
                    : (p.detail.isEmpty() ? "Check availability" : "Watch on demand");
            String dayNote = Instant.ofEpochMilli(p.start).atZone(ZONE).toLocalDate().equals(day)
                    ? "" : SHORT_DATE.format(Instant.ofEpochMilli(p.start));
            View row = programmeRow(p.start, p.end, dayNote, p.title, label, onAir, marked, () -> selectProgramme(p));
            row.setTag(p.start);
            if (selectedStart == p.start) chosen = row;
            if (onAir) { current = row; boundary = Math.min(boundary, p.end); }
            if (future && firstUpcoming == null) { firstUpcoming = row; boundary = Math.min(boundary, p.start); }
        }
        nextRefresh = Math.min(System.currentTimeMillis() + 180000, boundary);
        View target = chosen != null ? chosen : current != null ? current : firstUpcoming != null ? firstUpcoming : rows.getChildAt(0);
        int index = rows.indexOfChild(target);
        View anchor = current == target && index > 0 ? rows.getChildAt(index - 1) : target;
        connect(target, focus, anchor, priorOffset);
    }

    private void selectProgramme(LiveGuide.Programme item) {
        request++;
        cancelLookup();
        selectedStart = item.start;
        long now = System.currentTimeMillis();
        if (item.start > now) {
            boolean added = ProgrammeReminders.toggle(this, channel().id, item);
            Toast.makeText(this, added ? "Reminder set" : "Reminder removed", Toast.LENGTH_SHORT).show();
            refreshHeader();
            showSchedule(true);
        } else if (item.end > now) openLive();
        else lookupArchive(item);
    }

    private void lookupArchive(LiveGuide.Programme item) {
        int generation = ++request;
        cancelLookup();
        // Preview availability before handing the exact event to the native television player.
        final LiveGuide.Channel station = channel();
        lookup = worker.submit(() -> {
            try {
                LiveGuide.Programme archived = LiveGuide.archive(item);
                if (archived == null || archived.detail.isEmpty()) throw new java.io.FileNotFoundException();
                main.post(() -> {
                    if (generation != request || isFinishing() || isDestroyed()) return;
                    if (!resumed) return;
                    Intent open = new Intent(this, LiveTvActivity.class).putExtra("channel", station.id)
                            .putExtra("programmeStart", item.start).putExtra("programmeEnd", item.end)
                            .putExtra("programmeEventId", item.eventId).putExtra("programmeTitle", item.title)
                            .putExtra("programmeProvider", archived.provider).putExtra("programmeDetail", archived.detail);
                    startActivity(open);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (generation != request || isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, failure instanceof java.io.FileNotFoundException ?
                            "The broadcaster does not offer this programme on demand." : "The library is currently unavailable.", Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void openLive() { startActivity(new Intent(this, LiveTvActivity.class).putExtra("channel", channel().id)); }

    private void showReminders(boolean focus) {
        reminders = true;
        request++;
        cancelLookup();
        refreshHeader();
        clearRows();
        selectedRow = null;
        List<ProgrammeReminders.Entry> items = ProgrammeReminders.entries(this);
        if (items.isEmpty()) {
            notice("No reminders yet.");
            action("Open guide", () -> load(true), focus);
            nextRefresh = System.currentTimeMillis() + 180000;
            return;
        }
        long now = System.currentTimeMillis();
        nextRefresh = now + 180000;
        for (ProgrammeReminders.Entry entry : items) {
            boolean onAir = entry.start <= now;
            String context = SHORT_DATE.format(Instant.ofEpochMilli(entry.start)) + "  ·  " + ProgrammeReminders.channelName(entry.channel);
            programmeRow(entry.start, entry.end, context, entry.title, "Open reminder", onAir, true, () -> reminderActions(entry));
            nextRefresh = Math.min(nextRefresh, onAir ? entry.end : entry.start);
        }
        connect(rows.getChildAt(0), focus);
    }

    private void reminderActions(ProgrammeReminders.Entry item) {
        LinearLayout panel = modal(item.title);
        TextView first = null;
        if (item.start <= System.currentTimeMillis() && System.currentTimeMillis() < item.end) {
            first = button("Go to channel", () -> {
                dismissDialog();
                for (int i = 0; i < channels.size(); i++) if (channels.get(i).id.equals(item.channel)) { station = i; break; }
                day = Instant.ofEpochMilli(item.start).atZone(ZONE).toLocalDate();
                selectedStart = item.start;
                load(true);
            });
            panel.addView(first, margin(-1, 44, 8));
        }
        TextView remove = button("Remove reminder", () -> {
            dismissDialog();
            ProgrammeReminders.remove(this, item);
            showReminders(true);
        });
        panel.addView(remove, margin(-1, 44, 8));
        openDialog(first == null ? remove : first);
    }

    private void chooseStation() {
        LinearLayout panel = modal("Choose channel");
        ScrollView list = new ScrollView(this);
        list.setVerticalScrollBarEnabled(false);
        LinearLayout choices = column();
        list.addView(choices);
        panel.addView(list, new LinearLayout.LayoutParams(-1, Math.min(dp(345), getResources().getDisplayMetrics().heightPixels * 2 / 3)));
        View current = null;
        for (int i = 0; i < channels.size(); i++) {
            final int index = i;
            TextView choice = button(channels.get(i).name, () -> {
                dismissDialog();
                station = index;
                selectedStart = 0;
                load(true);
            });
            choices.addView(choice, margin(-1, 43, 4));
            if (i == station) current = choice;
        }
        openDialog(current);
    }

    private LinearLayout modal(String title) {
        dismissDialog();
        dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = column();
        panel.setBackground(shape(0xff263c31, 19, EDGE));
        panel.setPadding(dp(22), dp(16), dp(22), dp(20));
        TextView caption = text(title, 29, serif, WHITE);
        caption.setPadding(0, 0, 0, dp(8));
        panel.addView(caption);
        dialog.setContentView(panel);
        dialog.setOnDismissListener(d -> { if (d == dialog) dialog = null; });
        return panel;
    }

    private void openDialog(View first) {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.setLayout(Math.min(dp(450), getResources().getDisplayMetrics().widthPixels * 4 / 5), -2);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = .55f;
            window.setAttributes(params);
        }
        if (first != null) first.requestFocus();
    }

    private void dismissDialog() {
        if (dialog != null) { Dialog open = dialog; dialog = null; open.dismiss(); }
    }

    private void connect(View target, boolean focus) {
        connect(target, focus, target, null);
    }

    private void connect(View target, boolean focus, View anchor, Integer priorOffset) {
        if (target == null) return;
        selectedRow = target;
        selectStation.setNextFocusRightId(target.getId());
        saved.setNextFocusRightId(target.getId());
        previousDay.setNextFocusDownId(target.getId());
        nextDay.setNextFocusDownId(target.getId());
        if (focus && dialog == null) {
            target.requestFocus();
            scroll.post(() -> {
                if (target != selectedRow || !target.hasFocus()) return;
                int position = priorOffset == null ? anchor.getTop() : target.getTop() - priorOffset;
                scroll.scrollTo(0, Math.max(0, position));
            });
        }
    }

    private void clearRows() {
        rows.removeAllViews();
        liveIndicators.clear();
    }

    private void updateLiveProgress(long start, long end, TextView remaining, ProgressBar meter) {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(0, Math.min(end - start, now - start));
        meter.setProgress((int)(1000 * elapsed / Math.max(1, end - start)));
        long minutes = (Math.max(0, end - now) + 59999) / 60000;
        remaining.setText("Remaining " + minutes + " Min.");
    }

    private View programmeRow(long start, long end, String context, String name, String hint,
                              boolean onAir, boolean marked, Runnable activate) {
        LinearLayout row = column();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setId(View.generateViewId());
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(14), dp(onAir ? 11 : 8), dp(14), dp(onAir ? 11 : 8));

        LinearLayout header = line();
        TextView stamp = text(onAir ? "●   LIVE NOW" : clock(start), onAir ? 13 : 18, bold, MINT);
        if (onAir) stamp.setLetterSpacing(.07f);
        TextView title = text(name, onAir ? 31 : 19, onAir ? serif : bold, WHITE);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView meta = text(context, 14, regular, DIM);
        TextView action = text(hint + "  ›", 14, bold, MINT);
        action.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView duration = text("", 14, bold, MINT);
        ProgressBar meter = null;
        if (onAir) {
            header.addView(stamp);
            String timing = clock(start) + " – " + clock(end);
            meta.setText(context.isEmpty() ? timing : timing + "  ·  " + context);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            meta.setPadding(dp(10), 0, dp(6), 0);
            header.addView(meta, weighted());
            header.addView(duration);
            row.addView(header, new LinearLayout.LayoutParams(-1, -2));
            row.addView(title, margin(-1, -2, 4));
            LinearLayout footer = line();
            meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            meter.setMax(1000);
            meter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            footer.addView(meter, new LinearLayout.LayoutParams(0, dp(4), 1));
            action.setPadding(dp(15), 0, 0, 0);
            footer.addView(action);
            row.addView(footer, margin(-1, -2, 7));
            final ProgressBar progress = meter;
            Runnable update = () -> updateLiveProgress(start, end, duration, progress);
            update.run();
            liveIndicators.add(update);
        } else {
            LinearLayout texts = column();
            texts.addView(title);
            if (!context.isEmpty()) texts.addView(meta, margin(-1, -2, 2));
            header.addView(stamp, new LinearLayout.LayoutParams(dp(70), -2));
            header.addView(texts, weighted());
            action.setText(marked ? "✓" : "›");
            action.setPadding(dp(8), 0, 0, 0);
            header.addView(action);
            row.addView(header);
        }
        ProgressBar bar = meter;
        String times = clock(start) + " bis " + clock(end);
        row.setContentDescription(times + ", " + name + (context.isEmpty() ? "" : ", " + context) + ", " + hint);
        row.setNextFocusLeftId(reminders ? saved.getId() : selectStation.getId());
        row.setOnClickListener(v -> activate.run());
        View.OnFocusChangeListener focusStyle = (v, focused) -> {
            if (focused) row.setBackground(shape(FOCUS, onAir ? 16 : 11, WHITE));
            else if (onAir) row.setBackground(shape(0xff365244, 16, 0xff81977d));
            else row.setBackground(shape(0xff20332a, 11, 0xff354b3e));
            title.setTextColor(focused ? INK : WHITE);
            stamp.setTextColor(focused ? 0xff36583f : MINT);
            meta.setTextColor(focused ? 0xff3b5141 : DIM);
            action.setTextColor(focused ? INK : MINT);
            duration.setTextColor(focused ? INK : MINT);
            if (bar != null) {
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(focused ? 0xff456b4f : 0xffd5e1b7));
                bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(focused ? 0xffb8c6ae : 0xff536f5c));
            } else action.setText(focused ? hint + "  ›" : marked ? "✓" : "›");
            if (focused) {
                selectedRow = row;
                if (row.getTag() instanceof Long) selectedStart = (Long)row.getTag();
                selectStation.setNextFocusRightId(row.getId());
                saved.setNextFocusRightId(row.getId());
            }
        };
        row.setOnFocusChangeListener(focusStyle);
        focusStyle.onFocusChange(row, false);
        row.setMinimumHeight(dp(onAir ? 100 : 51));
        rows.addView(row, margin(-1, -2, 5));
        return row;
    }

    private void action(String title, Runnable activate, boolean focus) {
        TextView entry = button(title, activate);
        entry.setNextFocusLeftId(reminders ? saved.getId() : selectStation.getId());
        rows.addView(entry, margin(-1, 44, 8));
        if (rows.getChildCount() == 2) connect(entry, focus);
    }

    private void notice(String message) {
        TextView label = text(message, 19, regular, MINT);
        label.setPadding(dp(5), dp(12), dp(5), dp(8));
        rows.addView(label);
    }

    private TextView button(String title, Runnable activate) {
        TextView button = text(title, 16, bold, WHITE);
        button.setId(View.generateViewId());
        button.setFocusable(true);
        button.setClickable(true);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(shape(SHELL, 11, EDGE));
        button.setOnFocusChangeListener((v, focus) -> {
            button.setBackground(shape(focus ? FOCUS : SHELL, 11, focus ? WHITE : EDGE));
            button.setTextColor(focus ? INK : WHITE);
        });
        button.setOnClickListener(v -> activate.run());
        return button;
    }
    private LinearLayout column() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }
    private LinearLayout line() { LinearLayout box = new LinearLayout(this); box.setGravity(Gravity.CENTER_VERTICAL); return box; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private LinearLayout.LayoutParams margin(int width, int height, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
        lp.topMargin = dp(top);
        return lp;
    }
    private TextView text(String value, int size, Typeface font, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTypeface(font);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }
    private Typeface font(String file, int weight) {
        try {
            if (weight == 0) return Typeface.createFromAsset(getAssets(), "assets/fonts/" + file);
            return new Typeface.Builder(getAssets(), "assets/fonts/" + file).setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException failure) { return weight == 0 ? Typeface.SERIF : weight > 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT; }
    }
    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), border);
        return shape;
    }
    private String clock(long time) { return TIME.format(Instant.ofEpochMilli(time)); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void cancelLookup() { if (lookup != null) { lookup.cancel(true); lookup = null; } }
    @Override public void onBackPressed() { if (reminders) load(true); else super.onBackPressed(); }
    @Override public void onUserInteraction() { super.onUserInteraction(); idle.interaction(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (idle != null) idle.refresh(); }
    @Override protected void onResume() { super.onResume(); resumed = true; idle.resume(); main.postDelayed(refresh, 15000); }
    @Override protected void onPause() { resumed = false; idle.pause(); main.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { request++; cancelLookup(); worker.shutdownNow(); main.removeCallbacksAndMessages(null); dismissDialog(); super.onDestroy(); }
}

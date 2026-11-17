package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import site.navatv.launcher.PhoneGuide;
import site.navatv.launcher.PhoneGuide.Channel;
import site.navatv.launcher.PhoneGuide.Schedule;
import site.navatv.launcher.PhoneGuide.Show;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;

final class PhoneLivePage {
    private final MainActivity a;
    private final Surface u;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Schedule> schedules = new HashMap<>();
    private final Map<String, Long> fetched = new HashMap<>();
    private final Runnable tick = () -> load(false);
    private Future<?> loading;
    private int request;
    private volatile int tuneRequest;
    private TvConnection.Device playingOn;
    private boolean open;
    private boolean includeEarlier;
    private Dialog mirroredChannelPicker;
    private List<Channel> channels;
    private Channel channel;
    String selectedId = "daserste";
    private LinearLayout now, timeline, actions, playback;
    private TvConnection.LiveState activeLive = TvConnection.LiveState.OTHER;

    PhoneLivePage(MainActivity a) {
        this.a = a;
        u = a.ui;
    }

    void stop() {
        closeMirroredChannelPicker();
        open = false;
        includeEarlier = false;
        cancelLoad();
        tuneRequest++;
        playingOn = null;
        activeLive = TvConnection.LiveState.OTHER;
    }

    void resume() {
        if (!open) build();
        else if (channel != null && loading == null) load(false);
    }

    private void cancelLoad() {
        request++;
        if (loading != null) loading.cancel(true);
        loading = null;
        main.removeCallbacks(tick);
    }

    void build() {
        boolean entering = !open;
        open = true;
        cancelLoad();
        a.body.removeAllViews();
        boolean includesRtl = a.connected() ? a.device.hasRtl : a.devices.prefs.getBoolean("lastLineupHasRtl", false);
        channels = PhoneGuide.channels(includesRtl);
        channel = channels.get(0);
        for (Channel candidate : channels) if (candidate.id.equals(selectedId)) channel = candidate;
        selectedId = channel.id;

        LinearLayout back = u.row();
        back.addView(new Art(a, "left", MUTED), u.lp(15, 15));
        u.gap(back, 3);
        back.addView(u.text("Home", 14, MUTED, 590));
        u.action(back, 0, 12, 0, () -> a.go("home"));
        a.body.addView(back, u.lp(WRAP, 42));
        LinearLayout heading = u.row();
        heading.addView(u.title(guide() ? "TV guide" : "Live TV", 44, INK), u.weight(WRAP));
        heading.addView(u.button("Choose channel", "menu", false, this::chooseChannel), u.lp(WRAP, 48));
        a.body.addView(heading);
        u.space(a.body, 14);

        LinearLayout card = u.column();
        card.setBackground(u.shape(GREEN, 25, 0));
        u.pad(card, 21, 18, 21, 20);
        LinearLayout station = u.row();
        TextView number = u.text(String.format(Locale.ROOT, "%02d", channel.number), 15, 0xffe7f1d8, 690);
        number.setGravity(Gravity.CENTER);
        number.setBackground(u.shape(0xff385b49, 10, 0));
        station.addView(number, u.lp(40, 31));
        u.gap(station, 10);
        station.addView(u.text(channel.name, 17, WHITE, 690), u.weight(WRAP));
        station.addView(new Art(a, "onair", 0xffbfdea3), u.lp(20, 20));
        card.addView(station);
        u.space(card, 20);
        now = u.column();
        card.addView(now);
        a.body.addView(card);
        u.space(a.body, 10);
        actions = u.row();
        a.body.addView(actions);
        playback = u.column();
        a.body.addView(playback);
        drawActions();
        u.space(a.body, 14);
        timeline = u.column();
        a.body.addView(timeline);
        load(false);
        if (entering && !guide() && a.connected() && a.device.supportsLiveTune) {
            Channel initial = channel;
            int untouched = tuneRequest;
            a.runTv(a.tv::liveState, state -> {
                if (!open || guide() || channel != initial || untouched != tuneRequest || state.channel.isEmpty()) return;
                for (Channel tuned : channels) {
                    if (tuned.id.equals(state.channel)) {
                        select(tuned, false);
                        updatePlayback(state);
                        break;
                    }
                }
            }, ignored -> { /* Local programme browsing works without TV status. */ });
        }
    }

    private boolean guide() { return a.page.equals("programme"); }

    void followChannel(String id) {
        if (channel == null || id.isEmpty() || channel.id.equals(id)) return;
        selectedId = id;
        build();
    }

    void updatePlayback(TvConnection.LiveState state) {
        if (!open || guide() || state.equals(activeLive)) return;
        activeLive = state;
        if (state.channel.isEmpty()) playingOn = null;
        drawActions();
    }

    private void drawActions() {
        actions.removeAllViews();
        playback.removeAllViews();
        boolean onAir = !guide() && a.connected() && selectedId.equals(activeLive.channel);
        boolean follows = !guide() && a.connected() && (onAir || playingOn == a.device);
        actions.addView(stepButton(-1, follows), u.lp(48, 48));
        u.gap(actions, 7);
        String label = onAir ? (activeLive.paused ? "Resume" : "Pause") : a.connected() ? "Watch on TV" : "Connect a TV";
        String icon = onAir ? (activeLive.paused ? "resume" : "pause") : "cast";
        actions.addView(u.button(label, icon, true, () -> { if (onAir) control("pause"); else tune(); }), u.weight(48));
        u.gap(actions, 7);
        actions.addView(stepButton(1, follows), u.lp(48, 48));
        if (onAir) {
            u.space(playback, 8);
            LinearLayout buttons = u.row();
            for (String op : new String[]{"rewind", "forward", "record"}) {
                boolean record = op.equals("record");
                String name = record ? (activeLive.recordMode.equals("remember") ? "Save" : "Record")
                        : op.equals("rewind") ? "−10 sec" : "+10 sec";
                View button = u.button(name, null, false, () -> { if (record) record(); else control(op); });
                button.setEnabled(record ? !activeLive.recordMode.equals("unavailable") : activeLive.seekable);
                button.setAlpha(button.isEnabled() ? 1f : .45f);
                if (buttons.getChildCount() > 0) u.gap(buttons, 5);
                buttons.addView(button, u.weight(48));
            }
            playback.addView(buttons);
            u.space(playback, 8);
            playback.addView(u.button("TV guide", "library", false, () -> a.go("programme")), u.lp(MATCH, 48));
        }
    }

    private void control(String action) {
        TvConnection connection = a.tv;
        a.runTv(() -> { connection.liveControl(action); return connection.liveState(); },
                state -> { if (connection == a.tv) updatePlayback(state); });
    }

    private void record() {
        if (!"duration".equals(activeLive.recordMode)) { control("record"); return; }
        String[] labels = activeLive.recordEnd ? new String[]{"Until the programme ends", "1 Minute", "5 minutes", "30 minutes", "60 minutes"}
                : new String[]{"1 Minute", "5 minutes", "30 minutes", "60 minutes"};
        String[] commands = activeLive.recordEnd ? new String[]{"end","1","5","30","60"} : new String[]{"1","5","30","60"};
        String requested = channel.id;
        new AlertDialog.Builder(a).setTitle(channel.name + " · Record").setItems(labels, (dialog, i) -> {
            TvConnection connection = a.tv;
            // A channel change while choosing the length must never record the wrong station.
            a.runTv(() -> {
                if (!connection.liveState().channel.equals(requested)) throw new java.io.IOException("The TV has switched to a different channel.");
                connection.liveControl("record_" + commands[i]);
                return null;
            }, unused -> a.say("Recording requested on your TV."));
        }).setNegativeButton("Cancel", null).show();
    }

    private View stepButton(int direction, boolean follows) {
        FrameLayout button = new FrameLayout(a);
        button.addView(new Art(a, direction < 0 ? "left" : "right", GREEN),
                new FrameLayout.LayoutParams(u.d(21), u.d(21), Gravity.CENTER));
        u.action(button, PALE, 16, LINE, () -> {
            int index = channels.indexOf(channel);
            select(channels.get(Math.floorMod(index + direction, channels.size())), follows);
        });
        button.setContentDescription((direction < 0 ? "Previous channel" : "Next channel")
                + (follows ? " on TV" : " in guide"));
        return button;
    }

    private Dialog chooseChannel() {
        boolean canTune = !guide() && a.connected() && a.device.supportsLiveTune;
        return ChannelPicker.show(a, channels, selectedId, canTune, next -> select(next, canTune && a.connected() && a.device.supportsLiveTune));
    }

    void showTvChannelPicker() {
        if (mirroredChannelPicker != null && mirroredChannelPicker.isShowing()) return;
        mirroredChannelPicker = chooseChannel();
    }

    void closeMirroredChannelPicker() {
        if (mirroredChannelPicker != null) {
            mirroredChannelPicker.dismiss();
            mirroredChannelPicker = null;
        }
    }

    private void select(Channel next, boolean tuneOnTv) {
        if (next.id.equals(selectedId)) { if (tuneOnTv && !next.id.equals(activeLive.channel)) tune(); return; }
        selectedId = next.id;
        includeEarlier = false;
        build();
        if (tuneOnTv) tune();
    }

    private void tune() {
        if (!a.connected()) { a.needConnection(); return; }
        if (!a.device.supportsLiveTune) {
            a.problem("Update the TV app to switch channels from your phone.");
            return;
        }
        Channel selected = channel;
        TvConnection.Device target = a.device;
        int serial = ++tuneRequest;
        a.runTv(() -> {
            if (serial != tuneRequest || a.tv.device() != target) return false;
            a.tv.tune(selected.id);
            return true;
        }, sent -> {
            if (!sent || serial != tuneRequest || !open || !selected.id.equals(selectedId)) return;
            playingOn = target;
            drawActions();
            a.say(selected.name + " selected on TV.");
        }, error -> {
            if (serial != tuneRequest) return;
            playingOn = null;
            if (!a.tv.isConnected()) a.device = null;
            a.render();
            a.problem(a.error(error));
        });
    }

    private void load(boolean force) {
        main.removeCallbacks(tick);
        if ((!a.page.equals("live") && !guide()) || channel == null) return;
        if (!channel.hasGuide) {
            message("No schedule is available for this channel.", false);
            return;
        }
        long at = System.currentTimeMillis();
        Schedule saved = schedules.get(channel.id);
        if (!force && saved != null && at - fetched.get(channel.id) < (saved.current == null ? 60_000 : 300_000)
                && (saved.current == null || at < saved.current.end)
                && (saved.next == null || at < saved.next.start)) {
            display(saved, at);
            return;
        }
        cancelLoad();
        message("Loading schedule…", false);
        final int serial = request;
        Channel selected = channel;
        loading = a.browse.submit(() -> {
            try {
                Schedule data = PhoneGuide.load(selected, at);
                main.post(() -> {
                    if (request != serial) return;
                    loading = null;
                    schedules.put(selected.id, data);
                    fetched.put(selected.id, at);
                    display(data, System.currentTimeMillis());
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (request != serial) return;
                    loading = null;
                    message("The schedule is currently unavailable.", true);
                });
            }
        });
    }

    private void message(String title, boolean retry) {
        now.removeAllViews();
        timeline.removeAllViews();
        now.addView(u.text(title, 17, WHITE, 550));
        if (retry) timeline.addView(u.button("Reload", null, false, () -> load(true)), u.lp(WRAP, 48));
    }

    private void display(Schedule data, long at) {
        now.removeAllViews();
        timeline.removeAllViews();
        Show current = data.current;
        now.addView(u.text("On now", 13, 0xffc7dfa9, 670));
        u.space(now, 5);
        now.addView(u.title(current == null ? "No current programme listed" : current.title, current == null ? 27 : 34, WHITE));
        if (current != null) {
            u.space(now, 14);
            ProgressBar progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            long elapsed = at - current.start, duration = Math.max(1L, current.end - current.start);
            progress.setProgress((int)Math.max(0, Math.min(1000, 1000L * elapsed / duration)));
            progress.setProgressTintList(ColorStateList.valueOf(0xffd1e8b0));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xff527261));
            now.addView(progress, u.lp(MATCH, 6));
            u.space(now, 5);
            LinearLayout times = u.row();
            times.addView(u.text(PhoneGuide.clock(current.start), 12, 0xffdae5d6, 570), u.weight(WRAP));
            times.addView(u.text(PhoneGuide.clock(current.end), 12, 0xffdae5d6, 570));
            now.addView(times);
        }
        if (guide()) {
            timeline.addView(u.text(PhoneGuide.dayLabel(at), 18, INK, 700));
            u.space(timeline, 6);
            if (data.previous != null) {
                timeline.addView(u.button(includeEarlier ? "Hide earlier programmes" : "Show earlier programmes",
                        includeEarlier ? "up" : "down", false, () -> {
                            includeEarlier = !includeEarlier;
                            main.removeCallbacks(tick);
                            display(data, System.currentTimeMillis());
                        }), u.lp(MATCH, 48));
            }
            for (Show slot : data.shows) {
                if (!includeEarlier && slot.end <= at && slot != data.previous) continue;
                if (!PhoneGuide.dayLabel(slot.start).equals(PhoneGuide.dayLabel(at)) && slot != data.previous && slot != data.current && slot != data.next) continue;
                programmeRow(slot == current ? "On now" : PhoneGuide.clock(slot.start), slot, at);
                timeline.addView(u.rule(), u.lp(MATCH, 1));
            }
            if (data.shows.isEmpty()) timeline.addView(u.text("No airtimes available yet.", 15, MUTED, 500));
        } else {
            programmeRow("Previously", data.previous, at);
            timeline.addView(u.rule(), u.lp(MATCH, 1));
            programmeRow("Up next", data.next, at);
        }
        long refresh = 60_000;
        if (current != null) refresh = Math.max(1_000, Math.min(refresh, current.end - at + 100));
        else if (data.next != null) refresh = Math.max(1_000, Math.min(refresh, data.next.start - at + 100));
        main.postDelayed(tick, refresh);
    }

    private void programmeRow(String label, Show show, long at) {
        LinearLayout row = u.row();
        u.pad(row, 2, 12, 2, 12);
        row.addView(u.text(label, 13, MUTED, 650), u.lp(65, WRAP));
        LinearLayout info = u.column();
        info.addView(u.text(show == null ? "No programme listed" : show.title, 15, INK, 680));
        if (show != null) {
            u.space(info, 4);
            String day = PhoneGuide.dayLabel(show.start);
            String when = day.equals(PhoneGuide.dayLabel(at)) ? "" : day + " · ";
            info.addView(u.text(when + PhoneGuide.clock(show.start) + " – " + PhoneGuide.clock(show.end), 12, MUTED, 520));
        }
        row.addView(info, u.weight(WRAP));
        if (show != null && (show.start > at || show.end > at || !show.detail.isEmpty())) {
            row.addView(new Art(a, "right", MUTED), u.lp(16, 16));
            u.action(row, 0, 12, 0, () -> programmeAction(show));
            row.setContentDescription(label + ", " + show.title + ", " + PhoneGuide.clock(show.start));
        }
        timeline.addView(row);
    }

    private void programmeAction(Show show) {
        long now = System.currentTimeMillis();
        String stationId = selectedId;
        if (show.start > now) {
            if (!a.connected()) { a.needConnection(); return; }
            if (a.device.seniorVersion < 39) { a.problem("Update the TV app to add reminders from your phone."); return; }
            new AlertDialog.Builder(a).setTitle(show.title)
                    .setMessage(channel.name + " · " + PhoneGuide.dayLabel(show.start) + " · " + PhoneGuide.clock(show.start))
                    .setPositiveButton("Set reminder", (dialog, which) -> {
                        TvConnection target = a.tv;
                        a.runTv(() -> { target.setReminder(stationId, show.start, show.title, true); return null; },
                                unused -> a.say("Reminder saved under Recordings."));
                    }).setNegativeButton("Close", null).show();
        } else if (now < show.end) {
            tune();
        } else if (!show.detail.isEmpty()) {
            a.play(show.provider, show.detail, show.title, false);
        }
    }
}

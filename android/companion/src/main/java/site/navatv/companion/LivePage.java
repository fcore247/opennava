package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Live television: what is on, a channel wheel and the transport. History and listings open as submenus. */
final class LivePage {
    private static final String[] GROUPS = {"German", "Regional", "US", "Mine"};
    private static final long DAY = 86_400_000L;

    private final MainActivity a;
    private final Surface u;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Schedule> now = new HashMap<>();
    private final Map<String, Long> asked = new HashMap<>();
    private final Runnable tick = () -> {
        drawCard();
        schedule();
    };
    private Runnable tuneLater;
    private List<Channel> channels = new ArrayList<>();
    private ChannelWheel wheel;
    private LinearLayout card, segments, transport;
    private int group;
    String selectedId = "";
    /** The television's channel at the last report, so the wheel only follows real changes. */
    private String followed = "";

    LivePage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    void leave() {
        main.removeCallbacks(tick);
        cancelTune();
        wheel = null;
        card = segments = transport = null;
    }

    private void schedule() {
        main.removeCallbacks(tick);
        if (card != null) main.postDelayed(tick, 20_000);
    }

    private TvConnection.LiveState playing() {
        PhoneTvContextController.Snapshot snapshot = a.snapshot();
        return snapshot == null ? TvConnection.LiveState.OTHER : snapshot.live();
    }

    private Channel channel(String id) {
        for (Channel channel : channels) if (channel.id.equals(id)) return channel;
        return null;
    }

    private Channel selected() {
        Channel channel = channel(selectedId);
        return channel != null ? channel : channels.get(0);
    }

    private static int group(Channel channel) {
        return channel.provider.equals("us") ? 2 : channel.provider.equals("custom") ? 3 : channel.regional ? 1 : 0;
    }

    /** Fills the whole space between header and tabs; this page does not scroll. */
    void build(LinearLayout page) {
        leave();
        channels = a.channels();
        String onTv = playing().channel;
        if (!onTv.isEmpty() && channel(onTv) != null) {
            selectedId = onTv;
            followed = onTv;
        }
        if (channel(selectedId) == null) {
            List<String> recent = a.recentChannels();
            selectedId = !recent.isEmpty() && channel(recent.get(0)) != null ? recent.get(0) : channels.get(0).id;
        }
        group = group(selected());
        boolean compact = a.getResources().getConfiguration().screenHeightDp < 640;

        card = u.column();
        page.addView(card);
        u.space(page, compact ? 6 : 12);
        segments = u.row();
        segments.setBackground(u.shape(CARD, 22, 0));
        u.pad(segments, 4, 4, 4, 4);
        page.addView(segments);

        wheel = new ChannelWheel(a, channel -> {
            if (channel.id.equals(selectedId)) return;
            selectedId = channel.id;
            cancelTune();
            drawCard();
            drawTransport();
        });
        wheel.listen(this::tuneSoon, channel -> {
            cancelTune();
            a.tune(channel.id);
        });
        page.addView(wheel, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        transport = u.column();
        page.addView(transport);
        showGroup();
        drawCard();
        drawTransport();
        schedule();
    }

    private void showGroup() {
        segments.removeAllViews();
        for (int i = 0; i < GROUPS.length; i++) {
            boolean any = false;
            for (Channel channel : channels) if (group(channel) == i) any = true;
            if (!any) continue;
            int index = i;
            boolean on = group == i;
            TextView tab = u.text(GROUPS[i], 13, on ? ON_LILAC : MUTED, on ? 760 : 620);
            tab.setGravity(Gravity.CENTER);
            u.action(tab, on ? LILAC : 0, 18, 0, () -> {
                if (group == index) return;
                group = index;
                cancelTune();
                showGroup();
                drawCard();
                drawTransport();
            });
            tab.setSelected(on);
            segments.addView(tab, u.weight(38));
        }
        List<Channel> shown = new ArrayList<>();
        for (Channel channel : channels) if (group(channel) == group) shown.add(channel);
        wheel.show(shown, selectedId);
        selectedId = wheel.selectedId();
    }

    /** The television moved. Follow it unless the viewer is turning the wheel right now. */
    void tvChanged() {
        if (card == null) return;
        String onTv = playing().channel;
        if (!onTv.isEmpty() && !onTv.equals(followed) && !wheel.busy() && tuneLater == null) {
            followed = onTv;
            Channel channel = channel(onTv);
            if (channel != null && !onTv.equals(selectedId)) {
                selectedId = onTv;
                group = group(channel);
                showGroup();
            }
        }
        drawCard();
        drawTransport();
    }

    private void cancelTune() {
        if (tuneLater != null) main.removeCallbacks(tuneLater);
        tuneLater = null;
    }

    /** A short rest lets the viewer correct the wheel before the television changes. */
    private void tuneSoon(Channel channel) {
        cancelTune();
        if (!a.connected() || channel.id.equals(playing().channel)) return;
        tuneLater = () -> {
            tuneLater = null;
            followed = channel.id;
            a.tune(channel.id);
        };
        main.postDelayed(tuneLater, 600);
    }

    /** What is on the channel now; fetched in the background and drawn when it arrives. */
    Show onAir(Channel channel) {
        if (!channel.hasGuide) return null;
        long time = System.currentTimeMillis();
        Schedule saved = now.get(channel.id);
        boolean fresh = saved != null && saved.current != null && time < saved.current.end;
        Long last = asked.get(channel.id);
        if (!fresh && (last == null || time - last > 60_000)) {
            asked.put(channel.id, time);
            a.browse.execute(() -> {
                try {
                    Schedule loaded = PhoneGuide.load(channel, System.currentTimeMillis());
                    main.post(() -> {
                        now.put(channel.id, loaded);
                        a.scheduleArrived();
                    });
                } catch (Exception unavailable) { /* the channel name stays on screen */ }
            });
        }
        return fresh ? saved.current : null;
    }

    void drawCard() {
        if (card == null) return;
        card.removeAllViews();
        Channel channel = selected();
        TvConnection.LiveState state = playing();
        boolean onScreen = channel.id.equals(state.channel);
        Show show = onAir(channel);
        LinearLayout box = u.column();
        GradientDrawable ground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {onScreen ? 0xff3b2f66 : 0xff222736, 0xff171a26});
        ground.setCornerRadius(u.d(24));
        box.setBackground(ground);
        u.pad(box, 18, 14, 18, 15);
        LinearLayout top = u.row();
        View dot = new View(a);
        dot.setBackground(u.shape(onScreen ? RED : 0xff6b7286, 5, 0));
        top.addView(dot, u.lp(8, 8));
        u.gap(top, 8);
        String badge = onScreen ? (state.paused ? "PAUSED ON TV" : "LIVE ON TV") : "PREVIEW";
        top.addView(u.text(badge, 11, onScreen ? 0xffffc9c4 : MUTED, 800), u.weight(WRAP));
        TextView station = u.text(channel.name, 13, LILAC, 760);
        station.setSingleLine();
        station.setMaxWidth(u.d(190));
        station.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(station);
        box.addView(top);
        u.space(box, 9);
        String name = show != null ? show.title : channel.hasGuide ? channel.name
                : channel.provider.equals("custom") ? "Your own stream" : "Live stream";
        TextView title = u.text(name, 21, WHITE, 800);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(title);
        u.space(box, 10);
        ProgressBar progress = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        if (show != null && show.end > show.start)
            progress.setProgress((int) Math.max(0, Math.min(1000,
                    1000L * (System.currentTimeMillis() - show.start) / (show.end - show.start))));
        progress.setProgressTintList(ColorStateList.valueOf(LILAC));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0xff3a4055));
        box.addView(progress, u.lp(MATCH, 4));
        u.space(box, 3);
        LinearLayout times = u.row();
        times.addView(u.text(show == null ? " " : PhoneGuide.clock(show.start), 11, MUTED, 600), u.weight(WRAP));
        times.addView(u.text(show == null ? " " : PhoneGuide.clock(show.end), 11, MUTED, 600));
        box.addView(times);
        card.addView(box);
    }

    private void drawTransport() {
        if (transport == null) return;
        transport.removeAllViews();
        Channel channel = selected();
        TvConnection.LiveState state = playing();
        boolean onScreen = channel.id.equals(state.channel);
        LinearLayout main_ = u.row();
        main_.setGravity(Gravity.CENTER);
        side(main_, "history", "Earlier", true, () -> earlier(channel));
        boolean pause = onScreen && !state.paused;
        FrameLayout big = new FrameLayout(a);
        big.addView(new Art(a, pause ? "pause" : "resume", ON_LILAC),
                new FrameLayout.LayoutParams(u.d(34), u.d(34), Gravity.CENTER));
        u.action(big, LILAC, 42, 0, () -> {
            if (onScreen) a.liveControl("pause");
            else a.tune(channel.id);
        });
        big.setContentDescription(pause ? "Pause" : onScreen ? "Resume" : "Watch " + channel.name + " on TV");
        main_.addView(big, u.lp(84, 84));
        boolean recordable = onScreen && !state.recordMode.equals("unavailable");
        side(main_, "record", state.recordMode.equals("remember") && onScreen ? "Save" : "Record", recordable,
                () -> {
                    if (recordable) record(channel, state);
                    else a.say(!onScreen ? "Watch this channel on the TV to record it."
                            : "This stream cannot be recorded.");
                });
        transport.addView(main_);
        u.space(transport, 10);
        LinearLayout bottom = u.row();
        bottom.setBackground(u.shape(CARD, 22, 0));
        small(bottom, "minus", "Volume down", () -> a.sendKey(25));
        TextView listings = u.text("TV guide", 14, INK, 720);
        listings.setGravity(Gravity.CENTER);
        u.action(listings, 0, 18, 0, () -> guide(channel));
        bottom.addView(listings, u.weight(48));
        small(bottom, "plus", "Volume up", () -> a.sendKey(24));
        transport.addView(bottom);
        u.space(transport, 8);
    }

    private void side(LinearLayout row, String icon, String label, boolean enabled, Runnable run) {
        LinearLayout item = u.column();
        item.setGravity(Gravity.CENTER);
        FrameLayout disc = new FrameLayout(a);
        disc.setBackground(u.shape(PALE, 29, 0));
        disc.addView(new Art(a, icon, icon.equals("record") ? RED : INK),
                new FrameLayout.LayoutParams(u.d(25), u.d(25), Gravity.CENTER));
        item.addView(disc, u.lp(58, 58));
        u.space(item, 6);
        item.addView(u.text(label, 12, MUTED, 660), u.lp(WRAP, WRAP));
        u.action(item, 0, 20, 0, run);
        item.setAlpha(enabled ? 1f : .38f);
        item.setContentDescription(label);
        row.addView(item, new LinearLayout.LayoutParams(0, WRAP, 1f));
    }

    private void small(LinearLayout row, String icon, String label, Runnable run) {
        FrameLayout button = new FrameLayout(a);
        button.addView(new Art(a, icon, INK), new FrameLayout.LayoutParams(u.d(21), u.d(21), Gravity.CENTER));
        u.action(button, 0, 18, 0, run);
        button.setContentDescription(label);
        row.addView(button, u.lp(70, 48));
    }

    private void record(Channel channel, TvConnection.LiveState state) {
        if (!"duration".equals(state.recordMode)) {
            a.liveControl("record");
            return;
        }
        Sheet sheet = new Sheet(a, state.recordEnd ? .62f : .55f);
        sheet.show("Record " + channel.name, body -> {
            if (state.recordEnd) option(body, "Until the programme ends", () -> startRecording(sheet, channel, "end"));
            for (String minutes : new String[] {"1", "5", "30", "60"})
                option(body, minutes + (minutes.equals("1") ? " minute" : " minutes"),
                        () -> startRecording(sheet, channel, minutes));
        });
    }

    private void startRecording(Sheet sheet, Channel channel, String length) {
        sheet.dismiss();
        TvConnection tv = a.tv;
        a.runTv(() -> {
            // A channel change while choosing the length must never record the wrong station.
            if (!tv.liveState().channel.equals(channel.id))
                throw new java.io.IOException("The TV has switched to a different channel.");
            tv.liveControl("record_" + length);
            return null;
        }, done -> a.say("Recording on your TV."));
    }

    private void option(LinearLayout body, String label, Runnable run) {
        LinearLayout row = u.button(label, null, false, run);
        body.addView(row, u.lp(MATCH, 54));
        u.space(body, 8);
    }

    private void entry(LinearLayout body, String time, String title, String detail, boolean playable, Runnable run) {
        LinearLayout row = u.row();
        row.setBackground(u.shape(0xff202536, 17, 0));
        u.pad(row, 14, 11, 14, 11);
        TextView when = u.text(time, 13, playable ? LILAC : MUTED, 760);
        row.addView(when, u.lp(52, WRAP));
        LinearLayout words = u.column();
        TextView name = u.text(title, 15, playable ? INK : MUTED, 700);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        if (!detail.isEmpty()) {
            u.space(words, 4);
            TextView more = u.text(detail, 12, MUTED, 540);
            more.setSingleLine();
            more.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(more);
        }
        row.addView(words, u.weight(WRAP));
        u.gap(row, 8);
        row.addView(new Art(a, "right", playable ? INK : 0xff555b6e), u.lp(15, 15));
        u.action(row, 0xff202536, 17, 0, run);
        row.setContentDescription(time + ", " + title);
        body.addView(row);
        u.space(body, 7);
    }

    private void note(LinearLayout body, String title, String detail) {
        u.space(body, 20);
        body.addView(u.text(title, 17, INK, 730));
        if (!detail.isEmpty()) {
            u.space(body, 8);
            body.addView(u.text(detail, 14, MUTED, 500));
        }
        u.space(body, 16);
    }

    // ---- Earlier programmes -------------------------------------------------------------------

    private static final class History {
        int day;
        final List<List<Show>> days = new ArrayList<>();
        final List<LibraryHub.Card> own = new ArrayList<>();
        boolean loaded, failed;
    }

    private void earlier(Channel channel) {
        History history = new History();
        Sheet sheet = new Sheet(a, .86f);
        sheet.show("Earlier on " + channel.name, body -> drawHistory(body, sheet, channel, history));
        a.browse.execute(() -> {
            long time = System.currentTimeMillis();
            List<List<Show>> days = new ArrayList<>();
            boolean failed = false;
            for (int back = 0; back < 3 && channel.hasGuide; back++) {
                try {
                    List<Show> past = new ArrayList<>();
                    long at = time - back * DAY;
                    String label = PhoneGuide.dayLabel(at);
                    for (Show show : PhoneGuide.load(channel, at).shows) {
                        if (show.end <= time && PhoneGuide.dayLabel(show.start).equals(label)) past.add(0, show);
                    }
                    days.add(past);
                } catch (Exception unavailable) {
                    failed = back == 0;
                    days.add(new ArrayList<>());
                }
            }
            List<LibraryHub.Card> own = a.hub.catchUp(channel.id, time);
            boolean broken = failed;
            main.post(() -> {
                history.days.addAll(days);
                history.own.addAll(own);
                history.loaded = true;
                history.failed = broken;
                sheet.refresh();
            });
        });
    }

    private void drawHistory(LinearLayout body, Sheet sheet, Channel channel, History history) {
        if (!history.loaded) {
            ProgressBar busy = new ProgressBar(a);
            body.addView(busy, u.lp(MATCH, 70));
            return;
        }
        if (channel.hasGuide) {
            LinearLayout days = u.row();
            String[] names = {"Today", "Yesterday", "2 days ago"};
            for (int i = 0; i < history.days.size(); i++) {
                int index = i;
                boolean on = history.day == i;
                TextView tab = u.text(names[i], 13, on ? ON_LILAC : MUTED, 740);
                tab.setGravity(Gravity.CENTER);
                u.action(tab, on ? LILAC : PALE, 18, 0, () -> {
                    history.day = index;
                    sheet.refresh();
                });
                days.addView(tab, u.weight(40));
                if (i + 1 < history.days.size()) u.gap(days, 7);
            }
            body.addView(days);
            u.space(body, 14);
        }
        if (!history.own.isEmpty()) {
            LinearLayout mine = u.button("From your libraries  ·  " + history.own.size(), "library", false,
                    () -> sheet.show("From your libraries", list -> {
                        for (LibraryHub.Card item : history.own)
                            entry(list, PhoneGuide.clock(item.start), item.title,
                                    PhoneGuide.dayLabel(item.start) + " · " + a.hub.info(item.library).name, true,
                                    () -> ownDetail(sheet, item));
                    }));
            body.addView(mine, u.lp(MATCH, 52));
            u.space(body, 14);
        }
        List<Show> shows = history.day < history.days.size() ? history.days.get(history.day) : new ArrayList<>();
        for (Show show : shows) {
            boolean playable = !show.detail.isEmpty();
            entry(body, PhoneGuide.clock(show.start), show.title, playable ? "Available to watch" : "Not in the library",
                    playable, () -> showDetail(sheet, channel, show));
        }
        if (!shows.isEmpty() || !history.own.isEmpty() && !channel.hasGuide) return;
        if (!channel.hasGuide) {
            note(body, "No earlier shows for this channel yet",
                    "Add a library whose videos name this channel. Your AI agent can build one for you.");
            body.addView(u.button("Set up a library", "plus", true, () -> {
                sheet.dismiss();
                a.go("libraries");
            }), u.lp(MATCH, 54));
        } else note(body, history.failed ? "The guide is unavailable" : "Nothing earlier on this day", "");
    }

    private void showDetail(Sheet sheet, Channel channel, Show show) {
        sheet.show(channel.name, body -> {
            body.addView(u.text(show.title, 24, INK, 800));
            u.space(body, 9);
            body.addView(u.text(PhoneGuide.dayLabel(show.start) + " · " + PhoneGuide.clock(show.start) + " – "
                    + PhoneGuide.clock(show.end), 14, MUTED, 560));
            u.space(body, 22);
            if (show.detail.isEmpty()) {
                body.addView(u.text("The broadcaster does not offer this programme to watch again.", 15, MUTED, 500));
            } else {
                body.addView(u.button("Watch on TV", "resume", true, () -> {
                    sheet.dismiss();
                    a.play(show.provider, show.detail, show.title, false);
                }), u.lp(MATCH, 56));
            }
        });
    }

    private void ownDetail(Sheet sheet, LibraryHub.Card item) {
        sheet.show(a.hub.info(item.library).name, body -> {
            body.addView(u.text(item.title, 24, INK, 800));
            u.space(body, 9);
            body.addView(u.text(PhoneGuide.dayLabel(item.start) + " · " + PhoneGuide.clock(item.start), 14, MUTED, 560));
            u.space(body, 22);
            body.addView(u.button("Watch on TV", "resume", true, () -> {
                sheet.dismiss();
                a.playOwn(item);
            }), u.lp(MATCH, 56));
            if (!item.description.isEmpty()) {
                u.space(body, 17);
                body.addView(u.text(item.description, 15, 0xffd3d6e2, 480));
            }
        });
    }

    // ---- Listings and reminders ---------------------------------------------------------------

    private void guide(Channel channel) {
        Sheet sheet = new Sheet(a, .86f);
        Schedule[] loaded = new Schedule[1];
        boolean[] done = {!channel.hasGuide};
        sheet.show("TV guide · " + channel.name, body -> {
            if (!done[0]) {
                body.addView(new ProgressBar(a), u.lp(MATCH, 70));
                return;
            }
            long time = System.currentTimeMillis();
            boolean any = false;
            if (loaded[0] != null) for (Show show : loaded[0].shows) {
                if (show.end <= time) continue;
                any = true;
                boolean live = show.start <= time;
                entry(body, live ? "Now" : PhoneGuide.clock(show.start), show.title,
                        PhoneGuide.dayLabel(show.start), true, () -> upcoming(sheet, channel, show));
            }
            if (!any) note(body, "No programme guide", "There are no listings for this channel.");
        });
        if (done[0]) return;
        a.browse.execute(() -> {
            Schedule result = null;
            try { result = PhoneGuide.load(channel, System.currentTimeMillis()); }
            catch (Exception unavailable) { /* shown as an empty guide */ }
            Schedule found = result;
            main.post(() -> {
                loaded[0] = found;
                done[0] = true;
                sheet.refresh();
            });
        });
    }

    private void upcoming(Sheet sheet, Channel channel, Show show) {
        sheet.show(channel.name, body -> {
            body.addView(u.text(show.title, 24, INK, 800));
            u.space(body, 9);
            body.addView(u.text(PhoneGuide.dayLabel(show.start) + " · " + PhoneGuide.clock(show.start) + " – "
                    + PhoneGuide.clock(show.end), 14, MUTED, 560));
            u.space(body, 22);
            boolean running = show.start <= System.currentTimeMillis();
            body.addView(u.button(running ? "Watch on TV" : "Remind me on the TV", running ? "resume" : "schedule", true,
                    () -> {
                        sheet.dismiss();
                        if (running) a.tune(channel.id);
                        else remind(channel, show);
                    }), u.lp(MATCH, 56));
        });
    }

    private void remind(Channel channel, Show show) {
        if (!a.connected()) {
            a.needConnection();
            return;
        }
        TvConnection tv = a.tv;
        a.runTv(() -> {
            tv.setReminder(channel.id, show.start, show.title, true);
            return null;
        }, done -> a.say("Reminder saved. You find it under Recordings."));
    }
}

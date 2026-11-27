package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class PhoneRecordingsPage {
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale.ENGLISH).withZone(BERLIN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(BERLIN);
    private final MainActivity a;
    private final Surface u;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::load;
    private LinearLayout list;
    private PhoneLibrary shown;
    private TvConnection.Device device;
    private AlertDialog dialog;
    private boolean open, busy;
    private int generation;

    PhoneRecordingsPage(MainActivity a) { this.a = a; u = a.ui; }

    void build() {
        stop();
        open = true;
        device = a.device;
        list = u.column();
        a.body.addView(list);
        if (!a.connected()) {
            message("Connect to your TV", "Your recordings and reminders are stored on the TV.",
                    "Choose a TV", () -> a.go("devices"));
        } else if (device.seniorVersion < 39) {
            message("TV update required", "Update the TV app to manage recordings from your phone.", null, null);
        } else {
            list.addView(u.text("Loading recordings…", 16, MUTED, 560));
            load();
        }
    }

    void resume() {
        if (!open && a.page.equals("recordings")) { a.body.removeAllViews(); build(); }
    }

    void stop() {
        open = false;
        generation++;
        main.removeCallbacks(poll);
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        shown = null;
        busy = false;
    }

    private void load() {
        if (!open || busy) return;
        final TvConnection tv = a.tv;
        if (tv.device() != device || device == null) { a.render(); return; }
        final int token = ++generation;
        a.runTv(tv::libraryEntries, library -> {
            if (!open || generation != token) return;
            if (shown == null || !shown.revision.equals(library.revision)) show(library);
            main.removeCallbacks(poll);
            main.postDelayed(poll, 15_000);
        }, error -> {
            if (!open || generation != token) return;
            shown = null;
            message("List unavailable", a.error(error), "Reload", this::load);
        });
    }

    private void show(PhoneLibrary library) {
        shown = library;
        list.removeAllViews();
        if (library.recordings.isEmpty() && library.reminders.isEmpty()) {
            message("No saved programmes yet", "Your recordings and reminders from the TV guide will appear here.",
                    "Open Live TV", () -> a.go("live"));
            return;
        }
        section("Saved programmes", library.recordings);
        section("Reminders", library.reminders);
    }

    private void message(String title, String detail, String action, Runnable click) {
        list.removeAllViews();
        LinearLayout card = u.column();
        u.pad(card, 18, 20, 18, 18);
        card.setBackground(u.shape(PALE, 22, LINE));
        card.addView(u.title(title, 28, INK));
        u.space(card, 8);
        card.addView(u.text(detail, 16, MUTED, 500));
        if (action != null) {
            u.space(card, 17);
            card.addView(u.button(action, null, true, click), u.lp(WRAP, 50));
        }
        list.addView(card);
    }

    private void section(String heading, List<PhoneLibrary.Entry> entries) {
        if (entries.isEmpty()) return;
        if (list.getChildCount() != 0) u.space(list, 13);
        list.addView(u.text(heading, 18, INK, 690));
        u.space(list, 8);
        for (PhoneLibrary.Entry entry : entries) {
            LinearLayout card = u.column();
            card.setBackground(u.shape(CARD, 22, LINE));
            u.pad(card, 17, 15, 17, 14);
            if (!entry.station.isEmpty()) card.addView(u.text(entry.station, 14, LILAC, 690));
            u.space(card, 4);
            card.addView(u.title(entry.title, 30, INK));
            if (entry.start > 0) {
                u.space(card, 7);
                card.addView(u.text(times(entry), 14, MUTED, 540));
            }
            if (!entry.subtitle.isEmpty() && !(entry.reminder && entry.subtitle.equalsIgnoreCase("Reminders"))) {
                u.space(card, 4);
                card.addView(u.text(entry.subtitle, 14, MUTED, 540));
            }
            if (entry.playable || entry.deletable) {
                u.space(card, 13);
                LinearLayout actions = u.row();
                if (entry.playable) {
                    String label = entry.episodes.size() > 1 ? "Choose programme" : "Watch on TV";
                    actions.addView(u.button(label, "resume", true, () -> choose(entry)), u.weight(50));
                }
                if (entry.deletable) {
                    if (entry.playable) u.gap(actions, 8);
                    actions.addView(u.button("Delete", null, false, () -> confirmDelete(entry)), u.lp(WRAP, 50));
                    actions.getChildAt(actions.getChildCount()-1).setContentDescription(entry.title + " · Delete");
                }
                card.addView(actions);
            }
            list.addView(card);
            u.space(list, 8);
        }
    }

    private static String times(PhoneLibrary.Entry item) {
        Instant start = Instant.ofEpochMilli(item.start);
        String when = DATE.format(start);
        if (item.end > item.start) {
            Instant end = Instant.ofEpochMilli(item.end);
            boolean sameDay = start.atZone(BERLIN).toLocalDate().equals(end.atZone(BERLIN).toLocalDate());
            when += " – " + (sameDay ? TIME : DATE).format(end);
        }
        return when;
    }

    private void choose(PhoneLibrary.Entry entry) {
        if (busy) return;
        if (entry.episodes.size() <= 1) { act(entry, 0, false); return; }
        dialog = a.homeDialog().setTitle(entry.title)
                .setItems(entry.episodes.toArray(new String[0]), (d, index) -> act(entry, index, false))
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(PhoneLibrary.Entry entry) {
        if (busy) return;
        dialog = a.homeDialog()
                .setTitle(entry.reminder ? "Delete reminder?" : "Delete recording?")
                .setMessage("Remove “" + entry.title + "” from your TV?")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete", (d, w) -> act(entry, 0, true)).show();
    }

    private void act(PhoneLibrary.Entry entry, int episode, boolean delete) {
        if (busy || !open) return;
        busy = true;
        main.removeCallbacks(poll);
        final int token = ++generation;
        TvConnection tv = a.tv;
        TvConnection.Device target = device;
        a.runTv(() -> {
            if (target == null || tv.device() != target) throw new IOException("Your TV connection has changed.");
            if (delete) tv.libraryDelete(entry); else tv.libraryPlay(entry, episode);
            return true;
        }, sent -> {
            if (!open || generation != token) return;
            busy = false;
            a.say(delete ? "Entry deleted." : "Playback requested on your TV.");
            load();
        }, error -> {
            if (!open || generation != token) return;
            busy = false;
            a.problem(a.error(error));
            load();
        });
    }
}

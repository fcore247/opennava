package site.navatv.companion;

import static site.navatv.companion.Surface.*;

import android.app.AlertDialog;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import site.navatv.launcher.CustomStreams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** The viewer's own streams live on the TV. Typing and pasting long addresses happens here. */
final class StreamsPage {
    private static final int PLAYLIST_BYTES = 3 * 1024 * 1024;
    private final MainActivity a;
    private final Surface u;
    private List<CustomStreams.Entry> entries;

    StreamsPage(MainActivity activity) {
        a = activity;
        u = a.ui;
    }

    void show(List<CustomStreams.Entry> latest) {
        boolean changed = entries == null || !CustomStreams.write(entries).equals(CustomStreams.write(latest));
        entries = latest;
        if (changed && (a.page.equals("streams") || a.page.equals("home") || a.page.equals("live"))) a.render();
    }

    void forget() { entries = null; }

    private boolean supported() {
        return a.connected() && a.device.seniorVersion >= TvConnection.FIRST_TV_WITH_OWN_STREAMS;
    }

    void build() {
        u.space(a.body, 6);
        a.body.addView(u.text("Add HLS (.m3u8), DASH or video addresses. They become channels on your TV. "
                + "Only add streams you are allowed to watch.", 14, MUTED, 500));
        u.space(a.body, 15);
        if (!a.connected()) {
            a.body.addView(u.button("Connect a TV", "cast", true, () -> a.go("devices")), u.lp(MATCH, 56));
            return;
        }
        if (!supported()) {
            a.body.addView(u.text("Update OpenNava on your TV to version 1.2.0 or newer.", 15, ACCENT, 650));
            u.space(a.body, 9);
            a.body.addView(u.button("Update the TV", "tv", true, () -> a.go("devices")), u.lp(MATCH, 56));
            return;
        }
        a.body.addView(u.button("Add a stream", "plus", true, this::addDialog), u.lp(MATCH, 56));
        u.space(a.body, 8);
        a.body.addView(u.button("Import a playlist (M3U)", "folder", false, this::importDialog), u.lp(MATCH, 52));
        u.space(a.body, 18);
        if (entries == null) a.body.addView(u.text("Loading from your TV…", 15, MUTED, 500));
        else if (entries.isEmpty()) a.body.addView(u.text("No streams of your own yet.", 15, MUTED, 500));
        else for (CustomStreams.Entry entry : entries) row(entry);
    }

    private void row(CustomStreams.Entry entry) {
        LinearLayout row = u.row();
        row.setBackground(u.shape(WHITE, 17, LINE));
        u.pad(row, 4, 5, 6, 5);
        LinearLayout words = u.column();
        u.pad(words, 11, 8, 8, 8);
        words.addView(u.text(entry.name, 16, INK, 700));
        u.space(words, 4);
        TextView address = u.text(entry.url, 12, MUTED, 500);
        address.setSingleLine();
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        words.addView(address);
        u.action(words, 0, 13, 0, () -> a.tune(entry.id));
        words.setContentDescription("Watch " + entry.name + " on TV");
        row.addView(words, u.weight(WRAP));
        FrameLayout remove = new FrameLayout(a);
        remove.addView(new Art(a, "close", MUTED), new FrameLayout.LayoutParams(u.d(18), u.d(18), Gravity.CENTER));
        u.action(remove, PALE, 14, 0, () -> a.homeDialog().setTitle(entry.name)
                .setMessage("Remove this stream from your TV?")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    TvConnection tv = a.tv;
                    a.runTv(() -> { tv.removeOwnStream(entry.id); return null; }, done -> a.streamsChanged());
                }).show());
        remove.setContentDescription("Remove " + entry.name);
        row.addView(remove, u.lp(48, 48));
        a.body.addView(row);
        u.space(a.body, 8);
    }

    private LinearLayout form(EditText... fields) {
        LinearLayout box = u.column();
        u.pad(box, 24, 8, 24, 0);
        for (EditText field : fields) box.addView(field);
        return box;
    }

    private void addDialog() {
        EditText address = a.field("https://… stream address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, "");
        EditText name = a.field("Name (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS, "");
        AlertDialog dialog = a.homeDialog().setTitle("Add a stream").setView(form(address, name))
                .setNegativeButton("Cancel", null).setPositiveButton("Save to TV", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                CustomStreams.Entry entry = CustomStreams.entry(name.getText().toString(), address.getText().toString());
                dialog.dismiss();
                save(Collections.singletonList(entry));
            } catch (IllegalArgumentException invalid) { address.setError(invalid.getMessage()); }
        }));
        dialog.show();
    }

    private void importDialog() {
        EditText source = a.field("Playlist address, or paste its text",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, "");
        source.setSingleLine(false);
        source.setMaxLines(7);
        a.homeDialog().setTitle("Import a playlist").setView(form(source)).setNegativeButton("Cancel", null)
                .setPositiveButton("Import", (dialog, which) -> {
                    String value = source.getText().toString().trim();
                    if (value.isEmpty()) return;
                    a.say("Reading the playlist…");
                    a.browse.execute(() -> {
                        try {
                            boolean address = value.startsWith("https://") && !value.contains("\n");
                            String text = address ? download(value) : value;
                            // A single stream's own manifest is a channel, not a list of channels.
                            List<CustomStreams.Entry> found = address && text.contains("#EXT-X-")
                                    ? Collections.singletonList(CustomStreams.entry("", value))
                                    : CustomStreams.fromPlaylist(text);
                            a.runOnUiThread(() -> confirm(found));
                        } catch (IOException | IllegalArgumentException failed) {
                            a.runOnUiThread(() -> a.problem("The playlist could not be read. " + a.error(failed)));
                        }
                    });
                }).show();
    }

    private void confirm(List<CustomStreams.Entry> found) {
        if (a.isFinishing() || a.isDestroyed()) return;
        if (found.isEmpty()) {
            a.problem("No web streams were found in this playlist.");
            return;
        }
        int free = CustomStreams.LIMIT - (entries == null ? 0 : entries.size());
        if (free <= 0) {
            a.problem("Your TV already keeps " + CustomStreams.LIMIT + " streams. Remove one first.");
            return;
        }
        List<CustomStreams.Entry> fitting = found.subList(0, Math.min(free, found.size()));
        a.homeDialog().setTitle("Add " + fitting.size() + (fitting.size() == 1 ? " stream?" : " streams?"))
                .setMessage(fitting.size() < found.size() ? "Your TV has room for the first " + free + "."
                        : "They will appear in the channel list on your TV.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dialog, which) -> save(fitting)).show();
    }

    private void save(List<CustomStreams.Entry> added) {
        if (!supported()) { a.needConnection(); return; }
        TvConnection tv = a.tv;
        a.runTv(() -> { tv.addOwnStreams(added); return null; }, done -> {
            a.say(added.size() == 1 ? added.get(0).name + " is now a channel." : added.size() + " channels added.");
            a.streamsChanged();
        });
    }

    private static String download(String address) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
        try {
            http.setConnectTimeout(8000);
            http.setReadTimeout(10000);
            if (http.getResponseCode() != 200) throw new IOException("The server answered " + http.getResponseCode() + ".");
            try (InputStream in = http.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (bytes.size() + count > PLAYLIST_BYTES) throw new IOException("This playlist is too large.");
                    bytes.write(buffer, 0, count);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { http.disconnect(); }
    }
}

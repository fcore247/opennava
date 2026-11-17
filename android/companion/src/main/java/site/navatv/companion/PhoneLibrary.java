package site.navatv.companion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Snapshot of the TV's own recordings and scheduled reminders. */
final class PhoneLibrary {
    private static final Pattern PAYLOAD = Pattern.compile("\\bdata=([A-Za-z0-9_-]+)");
    final String revision;
    final List<Entry> recordings = new ArrayList<>();
    final List<Entry> reminders = new ArrayList<>();

    private PhoneLibrary(String revision) { this.revision = revision; }

    static PhoneLibrary decode(String response) throws IOException {
        Matcher match = PAYLOAD.matcher(response);
        if (!match.find()) throw new IOException("Could not load the list from your TV.");
        String data = match.group(1);
        try {
            JSONArray rows = new JSONArray(new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8));
            PhoneLibrary library = new PhoneLibrary(data);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String kind = row.getString("kind");
                if (kind.equals("recording")) library.recordings.add(new Entry(row, false));
                if (kind.equals("reminder")) library.reminders.add(new Entry(row, true));
            }
            library.recordings.sort((a, b) -> Long.compare(b.start, a.start));
            library.reminders.sort((a, b) -> Long.compare(a.start, b.start));
            return library;
        } catch (IllegalArgumentException | JSONException invalid) {
            throw new IOException("The TV returned an unreadable list.", invalid);
        }
    }

    static final class Entry {
        final String id, title, channel, station, subtitle;
        final boolean reminder, playable, deletable;
        final long start, end;
        final List<String> episodes = new ArrayList<>();

        Entry(JSONObject row, boolean reminder) throws JSONException {
            this.reminder = reminder;
            id = row.getString("id");
            title = row.getString("title");
            channel = row.optString("channel");
            station = row.optString("station");
            subtitle = row.optString("subtitle");
            playable = row.optBoolean("playable");
            deletable = row.optBoolean("deletable", true);
            start = row.optLong("start");
            end = row.optLong("end");
            JSONArray titles = row.optJSONArray("episodes");
            if (titles != null) for (int i = 0; i < titles.length(); i++) episodes.add(titles.getString(i));
        }

        String key() { return (reminder ? "reminder:" : "recording:") + id; }
    }
}

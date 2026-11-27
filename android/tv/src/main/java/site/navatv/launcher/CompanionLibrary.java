package site.navatv.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** The TV owns recordings, files and reminders. Phone actions refer only to their stable IDs. */
final class CompanionLibrary {
    private static final int ENCODING = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;

    static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), ENCODING);
    }

    static Bundle result(boolean done) {
        Bundle out = new Bundle();
        out.putBoolean("handled", done);
        return out;
    }

    private static String reminderId(ProgrammeReminders.Entry entry) {
        String key = entry.channel + "\n" + entry.start + "\n" + entry.title;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static Bundle list(Context context, List<ProgrammeReminders.Entry> reminders) {
        JSONArray items = new JSONArray();
        try {
            for (JSONObject r : Recordings.list(context)) {
                JSONArray titles = new JSONArray();
                JSONArray episodes = r.optJSONArray("links");
                if (episodes != null) for (int i = 0; i < episodes.length(); i++) {
                    JSONObject episode = episodes.optJSONObject(i);
                    titles.put(episode == null ? "" : episode.optString("title"));
                }
                items.put(new JSONObject().put("kind", "recording").put("id", r.optString("id"))
                        .put("title", r.optString("title")).put("channel", r.optString("channel"))
                        .put("station", r.optString("station")).put("start", r.optLong("start"))
                        .put("end", r.optLong("until")).put("episodes", titles).put("state", r.optString("state"))
                        .put("subtitle", RecordingsActivity.status(r)).put("deletable", !Recordings.active(r))
                        .put("playable", RecordingsActivity.playbackIntent(context, r, 0) != null));
            }
            long now = System.currentTimeMillis();
            for (ProgrammeReminders.Entry r : reminders) {
                items.put(new JSONObject().put("kind", "reminder").put("id", reminderId(r))
                        .put("title", r.title).put("channel", r.channel).put("station", ProgrammeReminders.channelName(r.channel))
                        .put("start", r.start).put("end", r.end).put("subtitle", "Reminder set").put("deletable", true)
                        .put("playable", r.start <= now && now < r.end));
            }
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        Bundle out = new Bundle();
        out.putString("data", encode(items.toString()));
        return out;
    }

    private static String key(String arg, String kind) {
        if (arg == null || !arg.startsWith(kind + ":")) return null;
        String id = arg.substring(kind.length() + 1);
        return id.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}") ? id : null;
    }

    static Bundle delete(Context context, String arg) {
        String id = key(arg, "recording");
        if (id != null) {
            Recordings.remove(context, id);
            return result(Recordings.get(context, id) == null);
        }
        String reminder = key(arg, "reminder");
        if (reminder == null) return result(false);
        for (ProgrammeReminders.Entry r : ProgrammeReminders.entries(context)) {
            if (reminderId(r).equals(reminder)) { ProgrammeReminders.remove(context, r); refreshGuide(); break; }
        }
        return result(true);
    }

    private static void refreshGuide() {
        android.app.Activity foreground = ProgrammeReminders.foreground();
        if (foreground instanceof TvProgrammeActivity) ((TvProgrammeActivity) foreground).companionRemindersChanged();
    }

    static Intent play(Context context, String arg, int episode) {
        String id = key(arg, "recording");
        if (id != null) {
            if (RecordingsActivity.playbackIntent(context, Recordings.get(context, id), episode) == null) return null;
            return new Intent(context, RecordingsActivity.class).putExtra("recordingId", id)
                    .putExtra("playEpisode", episode).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        }
        String reminder = key(arg, "reminder");
        if (reminder == null) return null;
        long now = System.currentTimeMillis();
        for (ProgrammeReminders.Entry r : ProgrammeReminders.entries(context)) {
            if (reminderId(r).equals(reminder) && r.start <= now && now < r.end)
                return LiveTvActivity.companionTuneIntent(context, r.channel);
        }
        return null;
    }

    static final class ReminderChange {
        final LiveGuide.Channel channel;
        final long start;
        final String title;
        final boolean enable;

        ReminderChange(String encoded) throws JSONException {
            if (encoded == null || encoded.length() > 8192) throw new IllegalArgumentException("Invalid reminder.");
            JSONObject request = new JSONObject(new String(Base64.decode(encoded, ENCODING), StandardCharsets.UTF_8));
            title = request.getString("title");
            start = request.getLong("start");
            enable = request.getBoolean("enabled");
            String station = request.getString("channel");
            LiveGuide.Channel found = null;
            for (LiveGuide.Channel c : LiveGuide.channels()) if (c.id.equals(station)) { found = c; break; }
            channel = found;
            if (channel == null || title.trim().isEmpty() || title.length() > 600 || start <= 0)
                throw new IllegalArgumentException("Invalid programme.");
        }

        // Fetch broadcaster data on the calling Binder thread, never on the TV's UI thread.
        LiveGuide.Programme resolve() throws Exception {
            if (!enable || start <= System.currentTimeMillis()) return null;
            for (LiveGuide.Programme item : LiveGuide.programmes(channel, start))
                if (start == item.start && title.equals(item.title)) return item;
            return null;
        }

        Bundle apply(Context context, LiveGuide.Programme verified) {
            for (ProgrammeReminders.Entry r : ProgrammeReminders.entries(context)) {
                if (channel.id.equals(r.channel) && start == r.start && title.equals(r.title)) {
                    if (!enable) { ProgrammeReminders.remove(context, r); refreshGuide(); }
                    return result(true);
                }
            }
            if (!enable) return result(true);
            if (verified == null || verified.start <= System.currentTimeMillis()) return result(false);
            boolean added = ProgrammeReminders.toggle(context, channel.id, verified);
            if (added) refreshGuide();
            return result(added);
        }
    }

    private CompanionLibrary() {}
}

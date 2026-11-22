package site.navatv.launcher;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.media3.common.MediaItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

final class Recordings {
    static final String FETCH = "preparing",
            CAPTURE = "recording",
            ARCHIVE = "archive",
            LOCAL = "local",
            PART = "partial",
            FAILED = "failed";
    static final int ARCHIVE_JOB = 42817;

    private Recordings() {}

    private static SharedPreferences store(Context c) {
        return c.getApplicationContext()
                .getSharedPreferences("tv-recordings-v1", Context.MODE_PRIVATE);
    }

    static File directory(Context c, String id) {
        if (id == null || !id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Invalid recording.");
        return new File(new File(c.getFilesDir(), "fernsehaufnahmen"), id);
    }

    static synchronized JSONObject get(Context c, String id) {
        String raw = store(c).getString(id, null);
        try {
            return raw == null ? null : new JSONObject(raw);
        } catch (JSONException invalid) {
            return null;
        }
    }

    static synchronized List<JSONObject> list(Context c) {
        List<JSONObject> result = new ArrayList<>();
        for (String id : store(c).getAll().keySet()) {
            JSONObject row = get(c, id);
            if (row != null) result.add(row);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.optLong("start"), a.optLong("start")));
        return result;
    }

    static boolean active(JSONObject row) {
        return row != null
                && (FETCH.equals(row.optString("state")) || CAPTURE.equals(row.optString("state")));
    }

    private static void save(Context c, JSONObject row) {
        if (!store(c).edit().putString(row.optString("id"), row.toString()).commit())
            throw new IllegalStateException("The TV could not save the recording.");
    }

    static synchronized void status(Context c, String id, String state, String reason) {
        JSONObject row = get(c, id);
        if (row == null) return;
        try {
            row.put("state", state).put("reason", reason);
            save(c, row);
        } catch (JSONException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    static synchronized void stop(Context c, String id) {
        JSONObject row = get(c, id);
        if (!active(row)) return;
        try {
            row.put("stop", true)
                    .put("until", Math.min(System.currentTimeMillis(), row.optLong("until")));
            save(c, row);
        } catch (JSONException invalid) {
            throw new IllegalStateException(invalid);
        }
    }

    static void start(
            Context c,
            LiveGuide.Channel channel,
            String liveUrl,
            LiveGuide.Programme now,
            long durationMs) {
        if (channel == null
                || liveUrl == null
                || !liveUrl.startsWith("https://")
                || durationMs <= 0
                || durationMs > 6L * 3600000)
            throw new IllegalStateException("Invalid recording duration or channel.");
        long time = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        synchronized (Recordings.class) {
            for (JSONObject row : list(c))
                if (active(row) && channel.id.equals(row.optString("channel")))
                    throw new IllegalStateException(
                            "This channel is already being recorded.");
            try {
                JSONObject row =
                        new JSONObject()
                                .put("id", id)
                                .put("channel", channel.id)
                                .put("station", channel.name)
                                .put("start", time)
                                .put("until", time + durationMs)
                                .put("state", FETCH)
                                .put("title", now == null ? channel.name : now.title)
                                .put("live", liveUrl);
                if (now != null) row.put("now", json(now));
                save(c, row);
            } catch (JSONException e) {
                throw new IllegalStateException("Could not create the recording.", e);
            }
        }
        try {
            c.startForegroundService(new Intent(c, RecordingService.class));
        } catch (RuntimeException e) {
            status(c, id, FAILED, "Could not start the recording service.");
            throw new IllegalStateException("The recording service is unavailable.", e);
        }
    }

    /** Called after the player has successfully opened this precise broadcaster episode. */
    static synchronized void remember(
            Context c, LiveGuide.Channel channel, LiveGuide.Programme programme) {
        if (channel == null
                || programme == null
                || programme.detail == null
                || programme.detail.isEmpty())
            throw new IllegalStateException(
                    "No library entry is available for this programme.");
        for (JSONObject row : list(c)) {
            JSONArray links = row.optJSONArray("links");
            if (links == null) continue;
            for (int i = 0; i < links.length(); i++) {
                JSONObject link = links.optJSONObject(i);
                if (link != null && programme.detail.equals(link.optString("detail"))) return;
            }
        }
        try {
            JSONObject row =
                    new JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("channel", channel.id)
                            .put("station", channel.name)
                            .put("title", programme.title)
                            .put("state", ARCHIVE)
                            .put("start", programme.start)
                            .put("until", programme.end)
                            .put("links", new JSONArray().put(json(programme)));
            save(c, row);
        } catch (JSONException invalid) {
            throw new IllegalStateException("Could not save the programme.", invalid);
        }
    }

    static LiveGuide.Channel channel(JSONObject row) {
        for (LiveGuide.Channel ch : LiveGuide.channels())
            if (ch.id.equals(row.optString("channel"))) return ch;
        return null;
    }

    private static JSONObject json(LiveGuide.Programme p) throws JSONException {
        return new JSONObject()
                .put("title", p.title)
                .put("start", p.start)
                .put("end", p.end)
                .put("provider", p.provider)
                .put("detail", p.detail)
                .put("eventId", p.eventId);
    }

    private static LiveGuide.Programme programme(JSONObject obj) {
        return new LiveGuide.Programme(
                obj.optString("title"),
                obj.optLong("start"),
                obj.optLong("end"),
                obj.optString("provider"),
                obj.optString("detail"),
                obj.optString("eventId"));
    }

    private static void include(TreeMap<Long, LiveGuide.Programme> items, LiveGuide.Programme p) {
        if (p == null || p.end <= p.start) return;
        LiveGuide.Programme previous = items.get(p.start);
        if (previous == null
                || previous.detail == null
                || previous.detail.isEmpty()
                || p.detail != null && !p.detail.isEmpty()) items.put(p.start, p);
    }

    /**
     * Only replace a local recording when every programme covering the requested interval actually
     * plays.
     */
    static boolean archive(Context c, JSONObject row) throws Exception {
        String id = row.getString("id");
        long start = row.getLong("start"), end = row.getLong("until");
        LiveGuide.Channel channel = channel(row);
        if (channel == null || end <= start) return false;
        TreeMap<Long, LiveGuide.Programme> guide = new TreeMap<>();
        if (row.has("now")) include(guide, programme(row.getJSONObject("now")));
        try {
            for (LiveGuide.Programme p : LiveGuide.programmes(channel, start)) include(guide, p);
            if (!guide.isEmpty() && guide.lastEntry().getValue().end < end - 1500)
                for (LiveGuide.Programme p : LiveGuide.programmes(channel, end - 1))
                    include(guide, p);
        } catch (Exception unavailable) {
            if (guide.isEmpty()) throw unavailable;
        }
        List<LiveGuide.Programme> selected = new ArrayList<>();
        long covered = start;
        for (LiveGuide.Programme p : guide.values()) {
            if (p.end <= covered || p.start >= end) continue;
            if (p.start > covered + 1500) return false;
            if (p.detail == null || p.detail.isEmpty()) p = LiveGuide.archive(p);
            if (p == null || p.detail == null || p.detail.isEmpty()) return false;
            selected.add(p);
            covered = p.end;
            if (covered >= end - 1500) break;
        }
        if (selected.isEmpty() || covered < end - 1500) return false;
        JSONArray links = new JSONArray();
        for (LiveGuide.Programme p : selected) {
            String resolved = LiveGuide.replay(p);
            if (!RecordingHls.verifyArchive(resolved)) return false;
            links.put(json(p));
        }
        synchronized (Recordings.class) {
            JSONObject actual = get(c, id);
            if (actual == null
                    || actual.optLong("until") != end
                    || actual.optBoolean("stop") && active(actual)) return false;
            actual.put("links", links)
                    .put("state", ARCHIVE)
                    .put("reason", "")
                    .put("title", selected.get(0).title);
            save(c, actual);
            // An already opened local player needs its segments until playback leaves the screen.
            if (!id.equals(playing)) removeFiles(directory(c, id));
        }
        return true;
    }

    private static String playing;

    static synchronized void playing(Context c, String id) {
        if (playing != null && !playing.equals(id)) {
            JSONObject old = get(c, playing);
            if (old == null || ARCHIVE.equals(old.optString("state")))
                removeFiles(directory(c, playing));
        }
        playing = id;
    }

    static synchronized Uri localUri(Context c, String id) {
        if (id == null) return null;
        JSONObject row = get(c, id);
        if (row == null) return null;
        File dir = directory(c, id);
        return RecordingHls.playable(dir) ? Uri.fromFile(new File(dir, "index.m3u8")) : null;
    }

    static MediaItem.ClippingConfiguration clip(Context c, String id, JSONObject row) {
        long offset = 0;
        try {
            File stamp = new File(directory(c, id), "start-offset");
            offset =
                    Math.max(
                            0,
                            Long.parseLong(
                                    new String(
                                            Files.readAllBytes(stamp.toPath()),
                                            StandardCharsets.US_ASCII)));
        } catch (Exception missing) {
            /* Older recordings begin at the first complete segment. */
        }
        return new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(offset)
                .setEndPositionMs(offset + Math.max(1, row.optLong("until") - row.optLong("start")))
                .build();
    }

    static synchronized void remove(Context c, String id) {
        JSONObject row = get(c, id);
        if (row == null || active(row)) return;
        if (store(c).edit().remove(id).commit() && !id.equals(playing)) removeFiles(directory(c, id));
    }

    static void removeFiles(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) removeFiles(child);
        f.delete();
    }

    static void schedule(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(ARCHIVE_JOB) != null) return;
        JobInfo job =
                new JobInfo.Builder(ARCHIVE_JOB, new ComponentName(c, RecordingArchiveJob.class))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPeriodic(6 * 3600000L)
                        .build();
        scheduler.schedule(job);
    }

    static boolean hasLocalWork(Context c) {
        for (JSONObject row : list(c)) if (recheckable(row)) return true;
        return false;
    }

    private static boolean recheckable(JSONObject row) {
        String state = row.optString("state");
        return LOCAL.equals(state) || PART.equals(state) || FAILED.equals(state);
    }

    static void refresh(Context c, long staleMs) {
        long now = System.currentTimeMillis();
        for (JSONObject row : list(c)) {
            if (Thread.currentThread().isInterrupted()) return;
            if (!recheckable(row) || row.optLong("checked") + staleMs > now) continue;
            synchronized (Recordings.class) {
                JSONObject fresh = get(c, row.optString("id"));
                if (fresh == null
                        || !recheckable(fresh)
                        || fresh.optLong("checked") + staleMs > now) continue;
                try {
                    fresh.put("checked", now);
                    save(c, fresh);
                } catch (JSONException ignored) {
                    continue;
                }
            }
            try {
                archive(c, row);
            } catch (Exception unavailable) {
                /* Keep the local copy for the next check. */
            }
        }
    }
}

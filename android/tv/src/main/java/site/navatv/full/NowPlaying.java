package site.navatv.full;

import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * What a player of this app is showing right now. The players publish it twice a second while they
 * are on screen; the paired companion reads it to draw its mini player.
 */
public final class NowPlaying {
    private static final long STALE_MS = 3000;
    private static Object owner;
    private static String report;
    private static long at;

    public static synchronized void publish(Object player, String kind, String videoId, String title, String author,
            long positionMs, long durationMs, boolean playing, boolean live) {
        try {
            report = new JSONObject().put("kind", kind)
                    .put("id", videoId == null ? "" : videoId)
                    .put("title", title == null ? "" : title)
                    .put("author", author == null ? "" : author)
                    .put("position", Math.max(0, positionMs))
                    .put("duration", live || durationMs < 0 ? 0 : durationMs)
                    .put("playing", playing).put("live", live).toString();
            owner = player;
            at = SystemClock.elapsedRealtime();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** A player that closes only removes its own report, not that of the one which replaced it. */
    public static synchronized void clear(Object player) {
        if (owner == player) {
            owner = null;
            report = null;
        }
    }

    public static synchronized String read() {
        return report == null || SystemClock.elapsedRealtime() - at > STALE_MS ? "{}" : report;
    }

    private NowPlaying() {}
}

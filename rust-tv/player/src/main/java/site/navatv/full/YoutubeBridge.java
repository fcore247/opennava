package site.navatv.full;

import android.app.Activity;
import android.os.Looper;
import android.util.Log;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.reactivex.schedulers.Schedulers;
import site.navatv.rustpreview.YoutubeSession;
import site.navatv.rustpreview.YoutubeSession.Viewer;

/** The Rust catalogue worker receives video metadata and local continuation handles. */
public final class YoutubeBridge {
    private static final int BATCH = 24;
    private static final Map<String, Page> cursors = Collections.synchronizedMap(
            new LinkedHashMap<String, Page>(16, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Page> oldest) {
                    return size() > 16;
                }
            });

    private static final class Page {
        final Viewer owner;
        final JSONArray videos;
        final int offset;
        final ArrayDeque<MediaGroup> groups;
        String delivered;

        Page(Viewer owner, JSONArray videos, int offset, ArrayDeque<MediaGroup> groups) {
            this.owner = owner;
            this.videos = videos;
            this.offset = offset;
            this.groups = groups;
        }
    }

    private YoutubeBridge() {}

    public static String videos(Activity activity, String query) {
        requireWorker();
        try {
            Viewer owner = YoutubeSession.forRequest();
            if (query == null || query.trim().isEmpty()) {
                if (owner.signedIn()) return personalFeed(owner);
                return new JSONObject().put("signedIn", false).put("items", new JSONArray()).toString();
            }
            YouTubeServiceManager.instance().refreshCacheIfNeeded();
            List<MediaGroup> groups = content().getSearchObserve(query.trim())
                    .firstOrError().timeout(30, TimeUnit.SECONDS).blockingGet();
            return groupPage(owner, groups, new ArrayDeque<>(), null);
        } catch (Exception e) {
            return failure();
        }
    }

    private static String personalFeed(Viewer owner) throws JSONException {
        List<MediaGroup> groups = content().getHomeObserve().observeOn(Schedulers.io())
                .filter(rows -> cards(rows, 1).length() > 0).first(Collections.emptyList())
                .timeout(18, TimeUnit.SECONDS).onErrorReturnItem(Collections.emptyList()).blockingGet();
        if (groups.isEmpty()) {
            groups = content().getSubscriptionsObserve().map(Collections::singletonList)
                    .first(Collections.emptyList()).timeout(15, TimeUnit.SECONDS).blockingGet();
        }
        return groupPage(owner, groups, new ArrayDeque<>(), null);
    }

    public static void clearPages() {
        cursors.clear();
    }

    /** Safe to retry. A failed request leaves the cursor and the existing Rust shelf intact. */
    public static String more(Activity activity, String cursor) {
        requireWorker();
        try {
            Page page = cursors.get(cursor);
            if (page == null || !page.owner.isCurrent()) return failure();
            synchronized (page) {
                if (!page.owner.isCurrent()) return failure();
                if (page.delivered != null) return page.delivered;
                if (page.offset < page.videos.length()) {
                    page.delivered = batch(page.owner, page.videos, page.offset, page.groups);
                } else {
                    YoutubeSession.forRequest();
                    ArrayDeque<MediaGroup> remaining = new ArrayDeque<>(page.groups);
                    MediaGroup previous = remaining.removeFirst();
                    List<MediaGroup> groups = content().continueGroupObserve(previous)
                            .map(Collections::singletonList).first(Collections.emptyList())
                            .timeout(30, TimeUnit.SECONDS).blockingGet();
                    page.delivered = groupPage(page.owner, groups, remaining, previous.getNextPageKey());
                    Log.i("OpenNava", "YouTube continuation groups=" + groups.size());
                }
                return page.delivered;
            }
        } catch (Exception e) {
            return failure();
        }
    }

    private static String groupPage(Viewer owner, List<MediaGroup> groups,
                                    ArrayDeque<MediaGroup> remaining, String previous) throws JSONException {
        if (!owner.isCurrent()) return failure();
        Set<String> keys = new HashSet<>();
        for (MediaGroup pending : remaining) keys.add(pending.getNextPageKey());
        for (MediaGroup group : groups) {
            if (group == null) continue;
            String key = group.getNextPageKey();
            if (key != null && !key.isEmpty() && !key.equals(previous) && keys.add(key)) {
                remaining.addLast(group);
            }
        }
        return batch(owner, cards(groups, Integer.MAX_VALUE), 0, remaining);
    }

    private static String batch(Viewer owner, JSONArray videos, int offset,
                                ArrayDeque<MediaGroup> remaining) throws JSONException {
        if (!owner.isCurrent()) return failure();
        JSONArray items = new JSONArray();
        int end = Math.min(offset + BATCH, videos.length());
        for (int i = offset; i < end; i++) items.put(videos.getJSONObject(i));
        JSONObject result = new JSONObject().put("items", items).put("signedIn", owner.signedIn());
        if (end < videos.length() || !remaining.isEmpty()) {
            String cursor = UUID.randomUUID().toString();
            cursors.put(cursor, new Page(owner, videos, end, remaining));
            result.put("next", cursor);
        }
        return result.toString();
    }

    private static JSONArray cards(List<MediaGroup> groups, int limit) throws JSONException {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (MediaGroup group : groups) {
            if (group == null) continue;
            List<MediaItem> media = group.getMediaItems();
            if (media == null) continue;
            for (MediaItem video : media) {
                if (video == null) continue;
                String id = video.getVideoId();
                String title = video.getTitle();
                if (!NavaYouTube.validVideoId(id) || title == null
                        || title.trim().isEmpty() || !seen.add(id)) continue;
                out.put(new JSONObject().put("id", id).put("title", title)
                        .put("author", video.getAuthor() == null ? "" : video.getAuthor())
                        .put("thumbnail", "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"));
                if (out.length() >= limit) return out;
            }
        }
        return out;
    }

    private static ContentService content() {
        return YouTubeServiceManager.instance().getContentService();
    }

    private static void requireWorker() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("YouTube catalogue work requires a background thread.");
        }
    }

    private static String failure() {
        return "{\"error\":\"YouTube konnte nicht geladen werden. Bitte erneut versuchen.\",\"items\":[]}";
    }
}

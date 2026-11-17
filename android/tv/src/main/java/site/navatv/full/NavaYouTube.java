package site.navatv.full;

import android.util.Log;
import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

/** Asynchronous typed adapter around SmartTube's native media service. */
final class NavaYouTube {
    interface ResultSink { void send(String requestId, String json); }
    private final ResultSink sink;
    private final AtomicBoolean warmupStarted = new AtomicBoolean();
    private final CompositeDisposable requests = new CompositeDisposable();
    private final AtomicInteger active = new AtomicInteger();
    private final Map<String, MediaGroup> pages = new LinkedHashMap<String, MediaGroup>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, MediaGroup> eldest) { return size() > 128; }
    };

    NavaYouTube(ResultSink sink) { this.sink = sink; }

    static boolean validVideoId(String value) { return value != null && value.matches("[A-Za-z0-9_-]{11}"); }
    static boolean validRequestId(String value) { return value != null && value.matches("[A-Za-z0-9_.:-]{1,96}"); }

    void request(String requestId, String payload) {
        if (!validRequestId(requestId)) return;
        if (payload == null || payload.length() > 8192) { fail(requestId, "Invalid YouTube request."); return; }
        if (active.incrementAndGet() > 4) {
            active.decrementAndGet();
            fail(requestId, "YouTube is still loading. Please try again shortly.");
            return;
        }
        String method;
        JSONObject input;
        try {
            input = new JSONObject(payload);
            method = input.getString("method");
            if (!(method.equals("home") || method.equals("search") || method.equals("channel") || method.equals("continue")))
                throw new IllegalArgumentException();
            if (method.equals("search") && (input.optString("query").trim().isEmpty() || input.optString("query").length() > 300))
                throw new IllegalArgumentException();
            if (method.equals("channel") && !input.optString("channelId").matches("(?:UC[A-Za-z0-9_-]{22}|@[A-Za-z0-9_.-]{1,100})"))
                throw new IllegalArgumentException();
        } catch (Exception badInput) {
            active.decrementAndGet();
            fail(requestId, "Invalid YouTube request.");
            return;
        }
        Log.i("OpenNava", "YouTube request method=" + method);
        final String operation = method;
        final AtomicReference<String> feedLabel = new AtomicReference<>();
        requests.add(Observable.defer(() -> {
            if (warmupStarted.compareAndSet(false, true)) {
                // SmartTube prepares the client scripts in its splash screen. Start that background
                // work when OpenNava's YouTube catalogue is first used, while the viewer is browsing.
                YouTubeServiceManager.instance().refreshCacheIfNeeded();
            }
            ContentService content = YouTubeServiceManager.instance().getContentService();
            switch (operation) {
                case "search": return content.getSearchObserve(input.optString("query").trim());
                case "channel": return content.getChannelObserve(input.optString("channelId"));
                case "continue":
                    MediaGroup previous;
                    synchronized (pages) { previous = pages.get(input.optString("pageToken")); }
                    if (previous == null) return Observable.error(new IllegalArgumentException("expired-page"));
                    return content.continueGroupObserve(previous).map(Collections::singletonList);
                default: return homeWithFallback(content, feedLabel);
            }
        }).subscribeOn(Schedulers.io()).take(1).timeout(30, TimeUnit.SECONDS)
                .observeOn(Schedulers.io()) // The native feed otherwise delivers on the main thread.
                .defaultIfEmpty(Collections.emptyList()).doFinally(active::decrementAndGet)
                .subscribe(groups -> {
                    try {
                        JSONObject result = serialize(groups, feedLabel.get());
                        Log.i("OpenNava", "YouTube result method=" + operation + " items=" + result.getJSONArray("items").length()
                                + " groups=" + result.getJSONArray("groups").length());
                        sink.send(requestId, result.toString());
                    } catch (Exception failure) { fail(requestId, "YouTube did not return readable data."); }
                }, failure -> {
                    Log.w("OpenNava", "YouTube request failed method=" + operation + " type=" + failure.getClass().getSimpleName());
                    fail(requestId, failure instanceof TimeoutException ? "YouTube is not responding. Please try again."
                            : failure instanceof IllegalArgumentException ? "This list has expired. Please reload it."
                            : "Could not load YouTube. Check your connection and try again.");
                }));
    }

    private Observable<List<MediaGroup>> homeWithFallback(ContentService content, AtomicReference<String> feedLabel) {
        // SmartTube can emit chips/navigation groups before its video shelves.
        // Filter those before the outer take(1), otherwise a valid later row is lost.
        return content.getHomeObserve().observeOn(Schedulers.io()).filter(groups -> playableGroups(groups, "home"))
                .switchIfEmpty(Observable.defer(() -> {
                    Log.i("OpenNava", "YouTube home fallback=trending");
                    return content.getTrendingObserve().observeOn(Schedulers.io()).filter(groups -> playableGroups(groups, "trending"));
                }))
                .switchIfEmpty(Observable.defer(() -> {
                    // An explicitly labelled topic selection, never presented as personalized or trending.
                    Log.i("OpenNava", "YouTube home fallback=topic-search");
                    feedLabel.set("Explore nature");
                    return content.getSearchObserve("Natur Dokumentation").observeOn(Schedulers.io())
                            .filter(groups -> playableGroups(groups, "topic-search"));
                }));
    }

    private static boolean playableGroups(List<MediaGroup> groups, String feed) {
        int groupCount = 0, itemCount = 0, playable = 0;
        if (groups != null) for (MediaGroup group : groups) {
            if (group == null) continue;
            groupCount++;
            List<MediaItem> items = group.getMediaItems();
            if (items != null) for (MediaItem item : items) {
                if (item == null) continue;
                itemCount++;
                if (validVideoId(item.getVideoId())) playable++;
            }
        }
        Log.i("OpenNava", "YouTube feed=" + feed + " incomingGroups=" + groupCount
                + " incomingItems=" + itemCount + " playable=" + playable);
        return playable > 0;
    }

    private JSONObject serialize(List<MediaGroup> input, String feedLabel) throws Exception {
        JSONArray groups = new JSONArray();
        JSONArray flattened = new JSONArray();
        int total = 0;
        int maxItems = feedLabel == null ? 180 : 40;
        if (input != null) for (MediaGroup group : input) {
            if (group == null || groups.length() >= 12 || total >= maxItems) continue;
            JSONArray items = new JSONArray();
            if (group.getMediaItems() != null) for (MediaItem item : group.getMediaItems()) {
                if (item == null || !validVideoId(item.getVideoId()) || items.length() >= 40 || total >= maxItems) continue;
                JSONObject entry = new JSONObject();
                String thumbnail = ThumbnailProxy.normalized(item.getCardImageUrl());
                if (thumbnail.isEmpty()) thumbnail = "https://i.ytimg.com/vi/" + item.getVideoId() + "/hqdefault.jpg";
                entry.put("id", item.getVideoId()).put("title", text(item.getTitle()))
                        .put("author", text(item.getAuthor())).put("thumbnail", thumbnail)
                        .put("duration", item.isLive() ? "LIVE" : duration(item.getDurationMs())).put("views", "")
                        .put("channelId", text(item.getChannelId())).put("isLive", item.isLive())
                        .put("subtitle", item.getSecondTitle() == null ? "" : item.getSecondTitle().toString());
                items.put(entry); flattened.put(entry); total++;
            }
            if (items.length() == 0) continue;
            JSONObject entry = new JSONObject().put("title", feedLabel == null ? text(group.getTitle()) : feedLabel).put("items", items);
            if (group.getNextPageKey() != null && !group.getNextPageKey().isEmpty()) {
                String handle = UUID.randomUUID().toString();
                synchronized (pages) { pages.put(handle, group); }
                entry.put("pageToken", handle);
            }
            groups.put(entry);
        }
        JSONObject result = new JSONObject().put("items", flattened).put("groups", groups);
        if (feedLabel != null) result.put("feedLabel", feedLabel).put("feedKind", "topic-search");
        if (groups.length() == 1 && groups.getJSONObject(0).has("pageToken"))
            result.put("pageToken", groups.getJSONObject(0).getString("pageToken"));
        return result;
    }

    private void fail(String requestId, String error) {
        try { sink.send(requestId, new JSONObject().put("items", new JSONArray()).put("error", error).toString()); }
        catch (Exception ignored) { }
    }

    private static String text(String value) { return value == null ? "" : value; }
    private static String duration(long millis) {
        if (millis <= 0) return "";
        long seconds = millis / 1000;
        return seconds >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    void close() { requests.dispose(); synchronized (pages) { pages.clear(); } }
}

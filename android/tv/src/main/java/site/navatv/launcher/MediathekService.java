package site.navatv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.util.LruCache;
import android.webkit.WebResourceResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Anonymous broadcaster APIs. Web content receives opaque IDs, never stream or API URLs. */
final class MediathekService {
    interface ResultSink { void send(String requestId, String json); }
    static final class Playback {
        final String url, title, author, channel, mimeType;
        final boolean live;
        Playback(String url, Entry entry, String mimeType) {
            this.url = url; title = entry.title; author = entry.author;
            channel = entry.provider.toUpperCase(Locale.ROOT);
            live = entry.live; this.mimeType = mimeType;
        }
    }
    private static final String ARD = "https://api.ardmediathek.de/";
    private static final String ZDF = "https://zdf-prod-futura.zdf.de/mediathekV2/";
    private static final String TAGESSCHAU = ARD + "page-gateway/pages/ard/compilation/tagesschau-20-uhr?embedded=true";
    private static final String DAS_ERSTE_ID = "Y3JpZDovL2Rhc2Vyc3RlLmRlL2xpdmUvY2xpcC9hYmNhMDdhMy0zNDc2LTQ4NTEtYjE2Mi1mZGU4ZjY0NmQ0YzQ";
    private static final String DAS_ERSTE_LIVE = ARD + "page-gateway/pages/daserste/item/" + DAS_ERSTE_ID + "?devicetype=pc&embedded=true";
    private static final String ZDF_LIVE_ID = "zdf-live-beitrag-100";
    private static final String ZDF_LIVE = ZDF + "document/" + ZDF_LIVE_ID;
    private static final int PAGE_SIZE = 24;
    private static SharedPreferences catalogueStore;
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<String, Entry>(512, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, MediathekService.Entry> e) { return size() > 512; }
    };
    private static final LruCache<String, byte[]> IMAGES = new LruCache<String, byte[]>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, byte[] value) { return value.length; }
    };
    static final class Entry {
        final String id, provider, title, author, thumbnail, detail, publishedAt, subtitle;
        final boolean live;
        final long duration;
        volatile Playback playback;
        volatile long resolvedAt;
        Entry(String provider, String title, String author, String thumbnail, String detail,
                String publishedAt, String subtitle, long duration, boolean live) {
            this.provider = provider; this.title = title; this.author = author; this.thumbnail = thumbnail;
            this.detail = detail; this.publishedAt = publishedAt; this.subtitle = subtitle;
            this.duration = duration; this.live = live;
            id = UUID.nameUUIDFromBytes((provider + ":" + detail).getBytes(StandardCharsets.UTF_8)).toString();
        }
    }
    private static final class Page {
        final String query, channel, arteZone;
        final int number;
        Page(String query, String channel, int number) { this(query, channel, number, ""); }
        Page(String query, String channel, int number, String arteZone) {
            this.query = query; this.channel = channel; this.number = number; this.arteZone = arteZone;
        }
    }
    private final ResultSink sink;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final AtomicInteger active = new AtomicInteger();
    private final Map<String, Page> pages = new LinkedHashMap<String, Page>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Page> e) { return size() > 64; }
    };
    private volatile boolean closed;
    private volatile JSONObject ardHome;
    private volatile long ardHomeAt;

    MediathekService(Context context, ResultSink sink) {
        this.sink = sink;
        initialize(context);
    }

    /** May also be called by a player restored directly by Android after the app process was killed. */
    static void initialize(Context context) {
        synchronized (ENTRIES) {
            if (catalogueStore != null) return;
            catalogueStore = context.getApplicationContext().getSharedPreferences("catalogue-v1", Context.MODE_PRIVATE);
            try {
                JSONArray saved = new JSONArray(catalogueStore.getString("entries", "[]"));
                for (int i = Math.max(0, saved.length()-512); i < saved.length(); i++) {
                    JSONObject data = saved.optJSONObject(i);
                    if (data == null) continue;
                    String provider = data.optString("provider");
                    String detail = data.optString("detail");
                    boolean live = data.optBoolean("live");
                    if (provider.equals("arte")) {
                        if (!(live ? ArteCatalog.LIVE.equals(detail) : ArteCatalog.isProgrammeConfig(detail))) continue;
                    } else {
                        if (!(provider.equals("ard") && detail.startsWith(ARD) || provider.equals("zdf") && detail.startsWith(ZDF)) || !isApiUrl(detail)) continue;
                        if (live && !(provider.equals("ard") ? DAS_ERSTE_LIVE : ZDF_LIVE).equals(detail)) continue;
                    }
                    String image = data.optString("image");
                    Entry entry = new Entry(provider, data.optString("title"), data.optString("author"),
                            allowedImage(image) ? image : "", detail, data.optString("publishedAt"),
                            data.optString("subtitle"), data.optLong("duration"), live);
                    ENTRIES.put(entry.id, entry);
                }
            } catch (Exception unreadable) { catalogueStore.edit().remove("entries").apply(); }
        }
    }

    private static void saveCatalogue() throws Exception {
        synchronized (ENTRIES) {
            if (catalogueStore == null) return;
            JSONArray saved = new JSONArray();
            for (Entry entry : ENTRIES.values()) {
                saved.put(new JSONObject().put("provider", entry.provider).put("detail", entry.detail)
                        .put("title", entry.title).put("author", entry.author).put("image", entry.thumbnail)
                        .put("publishedAt", entry.publishedAt).put("subtitle", entry.subtitle)
                        .put("duration", entry.duration).put("live", entry.live));
            }
            catalogueStore.edit().putString("entries", saved.toString()).apply();
        }
    }

    void request(String id, String payload) {
        if (closed || id == null || !id.matches("[A-Za-z0-9_.:-]{1,96}")) return;
        if (payload == null || payload.length() > 8192) { fail(id, "Invalid library request."); return; }
        if (active.incrementAndGet() > 4) {
            active.decrementAndGet(); fail(id, "The library is still loading. Please try again in a moment."); return;
        }
        try {
            JSONObject input = new JSONObject(payload);
            String method = input.getString("method"), channel = input.optString("channel");
            if (!(channel.equals("ard") || channel.equals("zdf") || channel.equals("arte"))) throw new IOException();
            if (!(method.equals("home") || method.equals("live") || method.equals("search") || method.equals("continue"))) throw new IOException();
            if (method.equals("search") && (input.optString("query").trim().isEmpty() || input.optString("query").length() > 300)) throw new IOException();
            workers.execute(() -> {
                try {
                    JSONObject result;
                    if (method.equals("home")) result = home(channel);
                    else if (method.equals("live")) result = live(channel);
                    else {
                        Page page = new Page(input.optString("query").trim(), channel, 0);
                        if (method.equals("continue")) {
                            synchronized (pages) { page = pages.get(input.optString("pageToken")); }
                            if (page == null || !page.channel.equals(channel)) throw new IOException("These results have expired. Please search again.");
                        }
                        result = search(page);
                    }
                    saveCatalogue();
                    Log.i("Libraries", "Mediathek result method=" + method + " items=" + result.getJSONArray("items").length());
                    if (!closed) sink.send(id, result.toString());
                } catch (Exception e) { fail(id, message(e)); }
                finally { active.decrementAndGet(); }
            });
        } catch (Exception e) { active.decrementAndGet(); fail(id, "Invalid library request."); }
    }

    private JSONObject home(String channel) throws Exception {
        JSONArray groups = new JSONArray();
        JSONArray items = new JSONArray();
        if (channel.equals("arte")) {
            boolean partial = false;
            for (JSONObject zone : ArteCatalog.homeZones()) {
                try { addArteShelf(groups, items, ArteCatalog.homeShelf(zone)); }
                catch (Exception unavailable) { partial = true; }
            }
            JSONObject response = result(items, groups, channel);
            if (items.length() == 0) response.put("error", "ARTE recommendations are currently unavailable.");
            else if (partial) response.put("warning", "Some ARTE recommendations could not be loaded.");
            return response;
        }
        List<String> warnings = new ArrayList<>();
        try {
            List<Entry> news = channel.equals("ard") ? tagesschau() : heute();
            addGroup(groups, items, channel.equals("ard") ? "Tagesschau um 20 Uhr" : "heute um 19 Uhr", news, "");
        } catch (Exception error) { warnings.add("Could not load the latest news."); }
        try {
            if (channel.equals("ard")) {
                JSONArray shelves = ardHome().optJSONArray("widgets");
                addEditorialShelves(shelves, true, groups, items);
            } else {
                JSONArray shelves = getJson(ZDF + "start-page").optJSONArray("cluster");
                addEditorialShelves(shelves, false, groups, items);
            }
        } catch (Exception error) { warnings.add("Could not load the broadcaster’s recommendations."); }
        JSONObject result = result(items, groups, channel);
        if (!warnings.isEmpty()) result.put(items.length() == 0 ? "error" : "warning", String.join(" ", warnings));
        return result;
    }

    private static List<Entry> tagesschau() throws Exception {
        List<Entry> entries = ardEntries(getJson(TAGESSCHAU), 8);
        entries.removeIf(entry -> entry.live || entry.duration < 600 || future(entry.publishedAt));
        entries.sort((a, b) -> b.publishedAt.compareTo(a.publishedAt));
        return entries;
    }

    private static List<Entry> heute() throws Exception {
        JSONObject brand = getJson(ZDF + "document/heute-19-uhr-102");
        JSONArray clusters = brand.optJSONArray("cluster");
        List<Entry> news = new ArrayList<>();
        if (clusters == null) return news;
        for (int i = 0; i < clusters.length(); i++) {
            JSONObject cluster = clusters.optJSONObject(i);
            if (cluster == null || !cluster.optString("name").equalsIgnoreCase("The current programme")) continue;
            JSONArray teasers = cluster.optJSONArray("teaser");
            if (teasers == null) continue;
            for (int k = 0; k < Math.min(teasers.length(), 2); k++) {
                JSONObject teaser = teasers.optJSONObject(k);
                if (teaser == null || !isApiUrl(teaser.optString("url"))) continue;
                Entry entry = zdfEntry(getJson(teaser.optString("url")).optJSONObject("document"));
                if (entry != null && !future(entry.publishedAt)) news.add(entry);
            }
        }
        return news;
    }

    // Teasers in show and collection rows lead to another catalogue. Here each card starts a programme.
    private static void addEditorialShelves(JSONArray shelves, boolean ard, JSONArray groups, JSONArray items) throws Exception {
        if (shelves == null) return;
        String[] preferred = ard
                ? new String[]{"Film-Empfehlungen", "Spannende Dokus und Reportagen", "Best-of Entertainment", "Die besten Videos aus der ARD Mediathek"}
                : new String[]{"Neu in der Mediathek", "Spannende Filme für Dich", "Dokus und Reportagen", "Familienfilme und Märchen"};
        java.util.Set<String> used = new java.util.HashSet<>();
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (String wanted : preferred) {
            for (int i = 0; i < shelves.length(); i++) {
                JSONObject shelf = shelves.optJSONObject(i);
                if (shelf == null) continue;
                String title = shelf.optString(ard ? "title" : "name");
                if (title.startsWith(wanted)) addShelf(shelf, title, ard, groups, items, used, titles);
            }
        }
        // Keep the catalogue usable when the broadcaster renames its editorial rows.
        if (titles.size() < 2) {
            for (int i = 0; i < shelves.length() && titles.size() < 4; i++) {
                JSONObject shelf = shelves.optJSONObject(i);
                if (shelf == null) continue;
                String title = shelf.optString(ard ? "title" : "name");
                if (!title.isEmpty()) addShelf(shelf, title, ard, groups, items, used, titles);
            }
        }
    }

    private static void addShelf(JSONObject shelf, String title, boolean ard, JSONArray groups, JSONArray items,
            java.util.Set<String> used, java.util.Set<String> titles) throws Exception {
        if (titles.contains(title)) return;
        List<Entry> entries = ard ? ardEntries(shelf, 24) : zdfEntries(shelf.optJSONArray("teaser"), 24);
        List<Entry> selected = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.live || used.contains(entry.id)) continue;
            selected.add(entry);
            if (selected.size() == 8) break;
        }
        if (selected.size() < 2) return;
        for (Entry entry : selected) used.add(entry.id);
        titles.add(title);
        addGroup(groups, items, title, selected, "");
    }

    private JSONObject live(String channel) throws Exception {
        JSONArray items = new JSONArray();
        JSONArray groups = new JSONArray();
        if (channel.equals("arte")) {
            JSONObject document = getJson(ArteCatalog.LIVE);
            Entry entry = ArteCatalog.liveEntry(document);
            resolveDocument(entry, document);
            addGroup(groups, items, "ARTE live", Collections.singletonList(entry), "");
            return result(items, groups, channel);
        }
        boolean ard = channel.equals("ard");
        String detail = ard ? DAS_ERSTE_LIVE : ZDF_LIVE;
        JSONObject data = getJson(detail);
        Entry mainChannel;
        if (ard) {
            JSONObject widget = dasErsteWidget(data);
            if (widget == null) throw new IOException("Das Erste is currently unavailable.");
            JSONObject image = widget.optJSONObject("image");
            String thumbnail = image == null ? "" : image.optString("src").replace("{width}", "640");
            mainChannel = new Entry("ard", "Das Erste", "Das Erste", allowedImage(thumbnail) ? thumbnail : "",
                    detail, "", widget.optString("synopsis"), 0, true);
        } else {
            JSONObject document = data.optJSONObject("document");
            if (!isZdfMainLive(document)) throw new IOException("The ZDF live stream is currently unavailable.");
            JSONObject images = document.optJSONObject("teaserBild");
            JSONObject image = images == null ? null : images.optJSONObject("768");
            String thumbnail = image == null ? "" : image.optString("url");
            mainChannel = new Entry("zdf", "ZDF live", "ZDF", allowedImage(thumbnail) ? thumbnail : "",
                    detail, "", document.optString("beschreibung"), 0, true);
        }
        // Resolve the fetched document before exposing the button target. The player reuses this result.
        resolveDocument(mainChannel, data);
        addGroup(groups, items, ard ? "ARD live" : "ZDF live", Collections.singletonList(mainChannel), "");
        return result(items, groups, channel);
    }

    private static JSONObject dasErsteWidget(JSONObject data) {
        JSONArray widgets = data.optJSONArray("widgets");
        if (widgets == null) return null;
        for (int i = 0; i < widgets.length(); i++) {
            JSONObject widget = widgets.optJSONObject(i);
            if (widget == null || !widget.optString("type").equals("player_live") || !DAS_ERSTE_ID.equals(widget.optString("id"))) continue;
            JSONObject publisher = widget.optJSONObject("publicationService");
            if (publisher != null && "urn:ard:publisher:a42e4291cd9eaf0b".equals(publisher.optString("coreId"))) return widget;
        }
        return null;
    }

    private static boolean isZdfMainLive(JSONObject doc) {
        return doc != null && ZDF_LIVE_ID.equals(doc.optString("id"))
                && "livevideo".equals(doc.optString("type")) && "ZDF".equals(doc.optString("channel"));
    }

    private JSONObject search(Page page) throws Exception {
        JSONArray groups = new JSONArray(), items = new JSONArray(), warnings = new JSONArray();
        if (page.channel.equals("arte")) {
            if (page.arteZone.isEmpty()) {
                for (ArteCatalog.Shelf shelf : ArteCatalog.search(encode(page.query))) addArteShelf(groups, items, shelf);
            } else {
                addArteShelf(groups, items, ArteCatalog.loadZone(page.query, page.arteZone));
            }
            return result(items, groups, page.channel);
        }
        boolean next = false;
        if (page.channel.equals("ard")) try {
            JSONObject data = getJson(ARD + "search-system/search/vods/ard?query=" + encode(page.query)
                    + "&pageNumber=" + page.number + "&pageSize=" + PAGE_SIZE + "&platform=MEDIA_THEK&sortingCriteria=SCORE_DESC");
            List<Entry> entries = ardEntries(data, PAGE_SIZE);
            JSONObject pagination = data.optJSONObject("pagination");
            boolean more = pagination != null && (page.number + 1) * PAGE_SIZE < pagination.optInt("totalElements");
            String token = more ? pageToken(new Page(page.query, "ard", page.number+1)) : "";
            addGroup(groups, items, "ARD Mediathek", entries, token); next |= more;
        } catch (Exception e) { warnings.put("ARD: " + message(e)); }
        if (page.channel.equals("zdf")) try {
            JSONObject data = getJson(zdfSearchUrl(page.query, page.number));
            List<Entry> entries = zdfEntries(data.optJSONArray("results"), PAGE_SIZE);
            boolean more = data.optBoolean("nextPage");
            String token = more ? pageToken(new Page(page.query, "zdf", page.number+1)) : "";
            addGroup(groups, items, "ZDF Mediathek", entries, token); next |= more;
        } catch (Exception e) { warnings.put("ZDF: " + message(e)); }
        JSONObject result = result(items, groups, page.channel);
        if (next) result.put("pageToken", pageToken(new Page(page.query, page.channel, page.number+1)));
        if (warnings.length() > 0) result.put(items.length() == 0 ? "error" : "warning", warnings.join(" · ").replace("\"", ""));
        return result;
    }

    private void addArteShelf(JSONArray groups, JSONArray items, ArteCatalog.Shelf shelf) throws Exception {
        String token = shelf.next.isEmpty() ? "" : pageToken(new Page(shelf.title, "arte", 0, shelf.next));
        addGroup(groups, items, shelf.title, shelf.entries, token);
    }

    private static String zdfSearchUrl(String query, int page) throws Exception {
        return ZDF + "search?q=" + encode(query) + "&page=" + (page+1) + "&synth=true";
    }
    private static List<Entry> zdfSearch(String query, int page) throws Exception {
        return zdfEntries(getJson(zdfSearchUrl(query,page)).optJSONArray("results"), PAGE_SIZE);
    }
    private JSONObject ardHome() throws Exception {
        JSONObject hit = ardHome;
        if (hit != null && System.currentTimeMillis() - ardHomeAt < 300000) return hit;
        JSONObject fresh = getJson(ARD + "page-gateway/pages/ard/home");
        ardHome = fresh; ardHomeAt = System.currentTimeMillis(); return fresh;
    }
    private String pageToken(Page page) {
        String id = UUID.randomUUID().toString(); synchronized (pages) { pages.put(id, page); } return id;
    }
    private static JSONObject result(JSONArray items, JSONArray groups, String channel) throws Exception {
        return new JSONObject().put("items", items).put("groups", groups).put("source", channel.toUpperCase(Locale.ROOT));
    }
    private static void addGroup(JSONArray groups, JSONArray flat, String title, List<Entry> entries, String token) throws Exception {
        JSONArray items = new JSONArray();
        for (Entry e : entries) {
            synchronized (ENTRIES) { ENTRIES.put(e.id, e); }
            JSONObject value = new JSONObject().put("id", e.id).put("title", e.title).put("author", e.author)
                    .put("provider", e.provider).put("channel", e.provider.toUpperCase(Locale.ROOT))
                    .put("duration", e.duration).put("views", "").put("subtitle", e.subtitle)
                    .put("publishedAt", e.publishedAt).put("isLive", e.live)
                    .put("thumbnail", e.thumbnail.isEmpty() ? "" : "https://nava.local/mediathek/image/" + e.id);
            items.put(value); flat.put(value);
        }
        if (items.length() > 0) groups.put(new JSONObject().put("title", title).put("items", items).put("pageToken", token));
    }

    private static List<Entry> ardEntries(JSONObject data, int limit) {
        LinkedHashMap<String, Entry> found = new LinkedHashMap<>();
        collectArd(data.optJSONArray("teasers"), found);
        JSONArray widgets = data.optJSONArray("widgets");
        if (widgets != null) for (int n=0; n<widgets.length(); n++) {
            JSONObject widget = widgets.optJSONObject(n);
            if (widget != null) collectArd(widget.optJSONArray("teasers"), found);
        }
        List<Entry> entries = new ArrayList<>(found.values());
        return entries.size() <= limit ? entries : new ArrayList<>(entries.subList(0, limit));
    }
    private static void collectArd(JSONArray teasers, Map<String, Entry> found) {
        if (teasers == null) return;
        for (int i=0; i<teasers.length(); i++) {
            JSONObject t = teasers.optJSONObject(i); if (t == null) continue;
            String type = t.optString("type");
            if (!(type.equals("ondemand") || type.equals("poster"))) continue;
            if (t.optString("coreAssetType").contains("LIVESTREAM")) continue;
            JSONObject links=t.optJSONObject("links"), target=links==null?null:links.optJSONObject("target");
            String detail=target==null?"":target.optString("href");
            if (!isApiUrl(detail) || !detail.contains("/item/")) continue;
            JSONObject images=t.optJSONObject("images"), image=images==null?null:images.optJSONObject("aspect16x9");
            String thumbnail=image==null?"":image.optString("src").replace("{width}","640");
            JSONObject publication=t.optJSONObject("publicationService"), show=t.optJSONObject("show");
            String author=publication==null?"ARD":publication.optString("name","ARD");
            String title=t.optString("longTitle",t.optString("mediumTitle",t.optString("shortTitle")));
            Entry entry=new Entry("ard",title,author,allowedImage(thumbnail)?thumbnail:"",detail,
                    t.optString("broadcastedOn"),show==null?"":show.optString("title"),t.optLong("duration"),false);
            found.put(entry.id,entry);
        }
    }
    private static List<Entry> zdfEntries(JSONArray values, int limit) {
        List<Entry> entries=new ArrayList<>(); if(values==null)return entries;
        for(int i=0;i<values.length() && entries.size()<limit;i++) {
            Entry entry=zdfEntry(values.optJSONObject(i)); if(entry!=null)entries.add(entry);
        }
        return entries;
    }
    private static Entry zdfEntry(JSONObject t) {
        if(t==null || !t.optString("type").equals("video"))return null;
        if (t.optString("currentVideoType").toLowerCase(Locale.ROOT).contains("live")
                || ZDF_LIVE_ID.equals(t.optString("id"))) return null;
        // ZDF search includes ARD partner content. Keep provider labels truthful and avoid duplicate ARD results.
        if(!t.optString("channel","ZDF").toUpperCase(Locale.ROOT).startsWith("ZDF"))return null;
        String detail=t.optString("url"); if(!isApiUrl(detail)||!detail.startsWith(ZDF+"document/"))return null;
        JSONObject images=t.optJSONObject("teaserBild"), image=images==null?null:images.optJSONObject("768");
        if(image==null && images!=null)image=images.optJSONObject("384");
        String thumbnail=image==null?"":image.optString("url");
        String published=date(t.optString("airtime",t.optString("date")));
        if(published.isEmpty() && t.optJSONObject("footline")!=null)published=date(t.optJSONObject("footline").optString("title"));
        return new Entry("zdf",t.optString("titel"),t.optString("brandTitle",t.optString("channel","ZDF")),
                allowedImage(thumbnail)?thumbnail:"",detail,published,t.optString("beschreibung"),t.optLong("length"),false);
    }
    private static String date(String text) {
        try { return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                .atZone(ZoneId.of("Europe/Berlin")).toInstant().toString(); } catch(Exception ignored) { }
        try { return LocalDate.parse(text,DateTimeFormatter.ofPattern("dd.MM.yyyy")).toString(); } catch(Exception ignored) { return ""; }
    }
    private static boolean future(String value) {
        try {
            if (value.length() == 10) return LocalDate.parse(value).isAfter(LocalDate.now(ZoneId.of("Europe/Berlin")));
            return Instant.parse(value).isAfter(Instant.now());
        } catch(Exception ignored) { return false; }
    }

    /** Same native catalogue for the TV shelves and the paired phone. */
    List<Entry> browse(String provider, String query) throws Exception {
        if (!("ard".equals(provider) || "zdf".equals(provider) || "arte".equals(provider)))
            throw new IOException("Unknown channel.");
        JSONObject result = query == null ? home(provider) : search(new Page(query, provider, 0));
        if (result.has("error")) throw new IOException(result.optString("error"));
        JSONArray rows = result.getJSONArray("items");
        LinkedHashMap<String, Entry> found = new LinkedHashMap<>();
        synchronized (ENTRIES) {
            for (int i = 0; i < rows.length(); i++) {
                Entry entry = ENTRIES.get(rows.getJSONObject(i).getString("id"));
                if (entry != null && !entry.live) found.put(entry.id, entry);
            }
        }
        return new ArrayList<>(found.values());
    }

    static String acceptProgramme(String provider, String detail, String title) {
        Uri uri = safeHttps(detail);
        if (uri == null || provider == null || title == null || title.trim().isEmpty() || title.length() > 500 ||
                detail.length() > 4096 || uri.getFragment() != null) return null;
        String path = uri.getEncodedPath();
        if (path == null) return null;
        boolean valid;
        switch (provider) {
            case "ard":
                valid = "api.ardmediathek.de".equals(uri.getHost()) &&
                        path.matches("/page-gateway/pages/[A-Za-z0-9_-]+/item/[A-Za-z0-9_+=%-]+");
                break;
            case "zdf":
                valid = "zdf-prod-futura.zdf.de".equals(uri.getHost()) &&
                        path.matches("/mediathekV2/document/[A-Za-z0-9_-]+");
                break;
            case "arte": valid = ArteCatalog.isProgrammeConfig(detail); break;
            default: return null;
        }
        if (!valid) return null;
        Entry item = new Entry(provider, title.trim(), provider.toUpperCase(Locale.ROOT), "", detail, "", "", 0, false);
        synchronized (ENTRIES) {
            if (!ENTRIES.containsKey(item.id)) ENTRIES.put(item.id, item);
        }
        return item.id;
    }

    static boolean contains(String id) { synchronized(ENTRIES) { return id!=null && ENTRIES.containsKey(id); } }
    static boolean belongsTo(String id, String provider) {
        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(id);
            return entry != null && provider.equals(entry.provider);
        }
    }
    static Playback resolve(String id) throws Exception {
        Entry entry; synchronized(ENTRIES) { entry=ENTRIES.get(id); }
        if(entry==null)throw new IOException("This programme is no longer cached. Please reopen the list.");
        long cacheMillis = entry.provider.equals("arte") ? (entry.live ? 30000 : 0) : 300000;
        if(entry.playback!=null && System.currentTimeMillis()-entry.resolvedAt<cacheMillis)return entry.playback;
        return resolveDocument(entry, getJson(entry.detail));
    }
    private static Playback resolveDocument(Entry entry, JSONObject data) throws Exception {
        String selected="",mime=""; int best=-1;
        if(entry.provider.equals("arte")) {
            selected = ArteCatalog.stream(entry, data);
            mime = "application/x-mpegURL";
        } else if(entry.provider.equals("ard")) {
            JSONArray widgets=data.optJSONArray("widgets");
            JSONObject mainLive = entry.live ? dasErsteWidget(data) : null;
            if(widgets!=null)for(int i=0;i<widgets.length();i++) {
                JSONObject w=widgets.optJSONObject(i); if(w==null)continue;
                if (entry.live ? w != mainLive : !"player_ondemand".equals(w.optString("type"))) continue;
                if(w.optBoolean("blockedByFsk"))throw new IOException("This programme requires age verification or is available later.");
                JSONObject collection=w.optJSONObject("mediaCollection"), embedded=collection==null?null:collection.optJSONObject("embedded");
                if(embedded==null)continue;
                JSONArray streams=embedded.optJSONArray("streams"); if(streams==null)continue;
                for(int n=0;n<streams.length();n++) {
                    JSONObject stream=streams.optJSONObject(n); if(stream==null)continue;
                    if(!stream.optString("kind","main").equals("main"))continue;
                    JSONArray media=stream.optJSONArray("media"); if(media==null)continue;
                    for(int m=0;m<media.length();m++) {
                        JSONObject variant=media.optJSONObject(m); if(variant==null)continue;
                        String url=variant.optString("url"),type=variant.optString("mimeType");
                        if (entry.live && !isMainStream("ard", url)) continue;
                        int score=streamScore(url,type)+Math.min(variant.optInt("maxVResolutionPx"),2160);
                        if(isTrustedStreamUrl(url)&&score>best) {best=score;selected=url;mime=type;}
                    }
                }
                // isGeoBlocked describes geographic rights, not the requester's actual location.
                // Let the CDN enforce them; never try another territory or modify geographic headers.
            }
        } else {
            JSONObject document=data.optJSONObject("document");
            if (entry.live && !isZdfMainLive(document)) throw new IOException("The ZDF live stream is currently unavailable.");
            if (!entry.live && document != null && "livevideo".equals(document.optString("type")))
                throw new IOException("This content is no longer available from the broadcaster.");
            JSONObject format = zdfFormat(document, entry.live);
            if (format != null) {
                selected = format.optString("url");
                mime = format.optString("mimeType");
            }
        }
        if(selected.isEmpty())throw new IOException("This programme has no supported stream. It may have expired or be restricted by region or airtime.");
        Playback playback=new Playback(selected,entry,mime);
        entry.playback=playback;entry.resolvedAt=System.currentTimeMillis();return playback;
    }
    private static boolean isMainStream(String provider, String value) {
        Uri uri = safeHttps(value);
        if (uri == null || uri.getPath() == null) return false;
        if (provider.equals("ard"))
            return "daserste-live.ard-mcdn.de".equals(uri.getHost()) && uri.getPath().startsWith("/daserste/live/hls/");
        return "zdf-hls-15.akamaized.net".equals(uri.getHost()) && uri.getPath().startsWith("/hls/live/2016498/");
    }

    private static JSONObject zdfFormat(JSONObject document, boolean live) {
        JSONArray formats = document == null ? null : document.optJSONArray("formitaeten");
        if (formats == null) return null;
        JSONObject selected = null;
        int best = -1;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);
            if (format == null || !format.optString("class", "main").equals("main")) continue;
            if (!format.optString("language", "deu").equals("deu")) continue;
            String url = format.optString("url");
            if (!isTrustedStreamUrl(url) || (live && !isMainStream("zdf", url))) continue;
            int score = streamScore(url, format.optString("mimeType"));
            if (format.optString("quality").equals("auto")) score += 3000;
            else if (format.optBoolean("hd")) score += 1000;
            if (score > best) { best = score; selected = format; }
        }
        return selected;
    }
    private static int streamScore(String url,String mime) {
        String type=mime.toLowerCase(Locale.ROOT),path=Uri.parse(url).getPath();
        if(type.contains("mpegurl") || path!=null&&path.endsWith(".m3u8"))return 10000;
        if(type.equals("video/mp4") || path!=null&&path.endsWith(".mp4"))return 1000;
        return -100000;
    }
    static boolean isTrustedStreamUrl(String value) {
        Uri uri=safeHttps(value); if(uri==null)return false;String h=uri.getHost().toLowerCase(Locale.ROOT);
        return ArteCatalog.isStreamUri(uri) || domain(h,"ard-mcdn.de") || domain(h,"tagesschau.de") || domain(h,"daserste.de")
                || h.equals("mcdn.br.de") || h.equals("mcdn.ndr.de") || h.equals("mcdn.swr.de") || h.equals("mcdn-one.ard.de")
                || h.equals("adaptive.ndr.de") || h.equals("wdradaptiv-vh.akamaihd.net") || h.equals("vod.unified.mdr.de")
                || h.equals("rbhlslive.akamaized.net") || h.equals("arteliveext.akamaized.net")
                || h.equals("brevent.akamaized.net") || h.equals("dwamdstream102.akamaized.net")
                || h.equals("br-i.akamaihd.net") || h.equals("cdn-storage.br.de")
                || h.equals("srod-vh.akamaihd.net") || h.equals("srstorage01-a.akamaihd.net")
                || h.equals("zdf-hls-16.akamaized.net") || h.equals("zdf-hls-17.akamaized.net")
                || h.equals("zdf-hls-15.akamaized.net") || h.equals("zdf-hls-18.akamaized.net") || h.equals("zdf-hls-19.akamaized.net")
                || h.equals("zdfvod.akamaized.net") || h.equals("zdfvod-rwrtr.akamaized.net")
                || h.equals("nrodlzdf-a.akamaihd.net") || h.equals("rodlzdf-a.akamaihd.net")
                || h.equals("pdvideosdaserste-a.akamaihd.net");
    }
    private static boolean domain(String host,String domain) {return host.equals(domain)||host.endsWith("."+domain);}
    static Uri safeHttps(String value) {
        if(value==null||value.length()>8192||value.indexOf('\\')>=0)return null;
        Uri uri=Uri.parse(value);
        return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null
                &&(uri.getPort()==-1||uri.getPort()==443)?uri:null;
    }
    private static boolean isApiUrl(String value) {
        Uri uri=safeHttps(value); if(uri==null)return false;
        return ArteCatalog.isApiUri(uri) || "api.ardmediathek.de".equals(uri.getHost())&&(uri.getPath().startsWith("/page-gateway/")||uri.getPath().startsWith("/search-system/"))
                || "zdf-prod-futura.zdf.de".equals(uri.getHost())&&uri.getPath().startsWith("/mediathekV2/");
    }
    static boolean allowedImage(String value) {
        Uri uri=safeHttps(value); if(uri==null)return false;String host=uri.getHost();
        return ("api-cdn.arte.tv".equals(host) && uri.getPath().startsWith("/img/v2/image/"))
                || "api.ardmediathek.de".equals(host)&&uri.getPath().startsWith("/image-service/")
                || "www.zdf.de".equals(host)&&uri.getPath().startsWith("/assets/")
                || "www.zdfheute.de".equals(host)&&uri.getPath().startsWith("/assets/")
                || "epg-image.zdf.de".equals(host);
    }
    static JSONObject getJson(String url) throws Exception {
        return new JSONObject(new String(fetch(url,false),StandardCharsets.UTF_8));
    }
    private static byte[] fetch(String url,boolean image) throws Exception {
        for(int redirect=0;redirect<4;redirect++) {
            if(image?!allowedImage(url):!isApiUrl(url))throw new IOException("Unsupported channel address.");
            HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept",image?"image/jpeg,image/png,image/webp":"application/json");
                connection.setRequestProperty("User-Agent","Mediatheken/1.0 (Android TV)");
                int status=connection.getResponseCode();
                if(status>=300&&status<400) {
                    String location=connection.getHeaderField("Location");if(location==null)throw new IOException();
                    url=new URL(new URL(url),location).toString();continue;
                }
                if(status==403||status==451)throw new IOException("The broadcaster declined access. This content may be limited by region or airtime.");
                if(status==404||status==410)throw new IOException("This content is no longer available from the broadcaster.");
                if(status!=200)throw new IOException("The library returned error "+status+".");
                String type=connection.getContentType();
                if(type==null || (image?!type.toLowerCase(Locale.ROOT).startsWith("image/"):!type.toLowerCase(Locale.ROOT).contains("json")))throw new IOException("Unexpected response from the library.");
                int limit = image ? 4 * 1024 * 1024 : 8 * 1024 * 1024;
                if(connection.getContentLengthLong()>limit)throw new IOException("The broadcaster’s response is too large.");
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[8192];int count;
                    while((count=input.read(buffer))!=-1) {
                        if(Thread.currentThread().isInterrupted())throw new IOException("Request cancelled.");
                        if(bytes.size()+count>limit)throw new IOException("The broadcaster’s response is too large.");
                        bytes.write(buffer,0,count);
                    }
                    return bytes.toByteArray();
                }
            } finally {connection.disconnect();}
        }
        throw new IOException("Could not resolve the channel address.");
    }
    WebResourceResponse image(String id) {
        Entry entry;synchronized(ENTRIES){entry=ENTRIES.get(id);}
        try {
            if(entry==null||entry.thumbnail.isEmpty())throw new IOException();
            byte[] data=IMAGES.get(entry.thumbnail);
            if(data==null) {data=fetch(entry.thumbnail,true);if(rasterMime(data)==null)throw new IOException();IMAGES.put(entry.thumbnail,data);}
            return new WebResourceResponse(rasterMime(data),null,200,"OK",Collections.singletonMap("X-Content-Type-Options","nosniff"),new ByteArrayInputStream(data));
        } catch(Exception ignored) {
            return new WebResourceResponse("text/plain","UTF-8",404,"Image unavailable",Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(new byte[0]));
        }
    }
    private static String rasterMime(byte[] b) {
        if(b.length<12)return null;
        if(b[0]==(byte)0xff&&b[1]==(byte)0xd8)return "image/jpeg";
        if(b[0]==(byte)0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G')return "image/png";
        if(b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='E'&&b[10]=='B'&&b[11]=='P')return "image/webp";
        return null;
    }
    private static String encode(String value) throws Exception {return URLEncoder.encode(value,"UTF-8");}
    private static String message(Exception e) {
        String known = playbackErrorMessage(e);
        if (known != null) return known;
        if ("These results have expired. Please search again.".equals(e.getMessage()))
            return "These results have expired. Please search again.";
        return "Could not load the library. Check your connection and try again.";
    }
    /** Only messages generated here become player copy; server text and URLs are never forwarded. */
    static String playbackErrorMessage(Throwable error) {
        Throwable cause = error;
        for (int depth=0; cause!=null && depth<12; depth++, cause=cause.getCause()) {
            if (!(cause instanceof IOException)) continue;
            String value = cause.getMessage();
            if ("This programme requires age verification or is available later.".equals(value))
                return "This programme requires age verification or is available later.";
            if ("The broadcaster declined access. This content may be limited by region or airtime.".equals(value))
                return "The broadcaster declined access. This content may be limited by region or airtime.";
            if ("This content is no longer available from the broadcaster.".equals(value))
                return "The broadcaster no longer offers this programme.";
            if ("This programme is no longer cached. Please reopen the list.".equals(value))
                return "Please reopen the programme from the library.";
        }
        return null;
    }
    private void fail(String id,String message) {
        if(closed)return;
        try{sink.send(id,new JSONObject().put("items",new JSONArray()).put("groups",new JSONArray()).put("error",message).toString());}catch(Exception ignored){}
    }
    void close() {closed=true;workers.shutdownNow();synchronized(pages){pages.clear();}}
}

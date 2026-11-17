package site.navatv.full;

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
            channel = entry.provider.equals("zdf") ? "ZDF" : "ARD";
            live = entry.live; this.mimeType = mimeType;
        }
    }
    private static final String ARD = "https://api.ardmediathek.de/";
    private static final String ZDF = "https://zdf-prod-futura.zdf.de/mediathekV2/";
    private static final String TAGESSCHAU = ARD + "page-gateway/pages/ard/compilation/tagesschau-20-uhr?embedded=true";
    private static final int PAGE_SIZE = 24;
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<String, Entry>(512, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, MediathekService.Entry> e) { return size() > 512; }
    };
    private static final LruCache<String, byte[]> IMAGES = new LruCache<String, byte[]>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, byte[] value) { return value.length; }
    };
    private static final class Entry {
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
        final String query, channel; final int number;
        Page(String query, String channel, int number) { this.query = query; this.channel = channel; this.number = number; }
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

    MediathekService(ResultSink sink) { this.sink = sink; }

    void request(String id, String payload) {
        if (closed || id == null || !id.matches("[A-Za-z0-9_.:-]{1,96}")) return;
        if (payload == null || payload.length() > 8192) { fail(id, "Invalid library request."); return; }
        if (active.incrementAndGet() > 4) {
            active.decrementAndGet(); fail(id, "The library is still loading. Please try again in a moment."); return;
        }
        try {
            JSONObject input = new JSONObject(payload);
            String method = input.getString("method"), channel = input.optString("channel", "all");
            if (!(channel.equals("all") || channel.equals("ard") || channel.equals("zdf"))) throw new IOException();
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
                            if (page == null) throw new IOException("These results have expired. Please search again.");
                        }
                        result = search(page);
                    }
                    Log.i("OpenNava", "Mediathek result method=" + method + " items=" + result.getJSONArray("items").length());
                    if (!closed) sink.send(id, result.toString());
                } catch (Exception e) { fail(id, message(e)); }
                finally { active.decrementAndGet(); }
            });
        } catch (Exception e) { active.decrementAndGet(); fail(id, "Invalid library request."); }
    }

    private JSONObject home(String channel) throws Exception {
        JSONArray groups = new JSONArray(), items = new JSONArray(), warnings = new JSONArray();
        if (!channel.equals("zdf")) {
            try {
                List<Entry> latest = ardEntries(getJson(TAGESSCHAU), 8, false);
                latest.removeIf(e -> e.live || e.duration < 600 || future(e.publishedAt));
                latest.sort((a,b) -> b.publishedAt.compareTo(a.publishedAt));
                addGroup(groups, items, "Tagesschau · 20:00", latest, "");
            } catch (Exception e) { warnings.put("ARD: " + message(e)); }
        }
        if (!channel.equals("ard")) {
            try {
                JSONObject brand = getJson(ZDF + "document/heute-19-uhr-102");
                List<Entry> latest = new ArrayList<>();
                JSONArray clusters = brand.optJSONArray("cluster");
                if (clusters != null) for (int n=0; n<clusters.length(); n++) {
                    JSONObject c = clusters.optJSONObject(n);
                    if (c == null || !c.optString("name").toLowerCase(Locale.ROOT).contains("aktuelle sendung")) continue;
                    JSONArray teasers = c.optJSONArray("teaser");
                    if (teasers == null) continue;
                    for (int i=0; i<Math.min(teasers.length(), 2); i++) {
                        JSONObject t = teasers.optJSONObject(i);
                        if (t == null || !isApiUrl(t.optString("url"))) continue;
                        Entry entry = zdfEntry(getJson(t.optString("url")).optJSONObject("document"));
                        if (entry != null && !future(entry.publishedAt)) latest.add(entry);
                    }
                }
                // Additional complete recent news bulletins, explicitly labelled by their real title.
                for (Entry entry : zdfSearch("heute", 0)) {
                    if (entry.duration >= 600 && !future(entry.publishedAt)
                            && latest.stream().noneMatch(e -> e.id.equals(entry.id))) latest.add(entry);
                    if (latest.size() >= 8) break;
                }
                latest.sort((a,b) -> b.publishedAt.compareTo(a.publishedAt));
                addGroup(groups, items, "ZDF · Latest programmes", latest, "");
            } catch (Exception e) { warnings.put("ZDF: " + message(e)); }
        }
        JSONObject result = result(items, groups);
        if (warnings.length() > 0) result.put("warning", warnings.join(" · ").replace("\"", ""));
        if (items.length() == 0 && warnings.length() > 0) result.put("error", result.optString("warning"));
        return result;
    }

    private JSONObject live(String channel) throws Exception {
        JSONArray items = new JSONArray(), groups = new JSONArray();
        if (!channel.equals("zdf")) {
            List<Entry> entries = ardEntries(ardHome(), 30, true);
            // The ARD home feed has duplicated editorial links. Use the programme row's actual station titles.
            entries.sort((a,b) -> Integer.compare(liveOrder(a.author), liveOrder(b.author)));
            addGroup(groups, items, "ARD · Live TV", entries, "");
        }
        return result(items, groups);
    }

    private JSONObject search(Page page) throws Exception {
        JSONArray groups = new JSONArray(), items = new JSONArray(), warnings = new JSONArray();
        boolean next = false;
        if (!page.channel.equals("zdf")) try {
            JSONObject data = getJson(ARD + "search-system/search/vods/ard?query=" + encode(page.query)
                    + "&pageNumber=" + page.number + "&pageSize=" + PAGE_SIZE + "&platform=MEDIA_THEK&sortingCriteria=SCORE_DESC");
            List<Entry> entries = ardEntries(data, PAGE_SIZE, false);
            JSONObject pagination = data.optJSONObject("pagination");
            boolean more = pagination != null && (page.number + 1) * PAGE_SIZE < pagination.optInt("totalElements");
            String token = more ? pageToken(new Page(page.query, "ard", page.number+1)) : "";
            addGroup(groups, items, "ARD Mediathek", entries, token); next |= more;
        } catch (Exception e) { warnings.put("ARD: " + message(e)); }
        if (!page.channel.equals("ard")) try {
            JSONObject data = getJson(zdfSearchUrl(page.query, page.number));
            List<Entry> entries = zdfEntries(data.optJSONArray("results"), PAGE_SIZE);
            boolean more = data.optBoolean("nextPage");
            String token = more ? pageToken(new Page(page.query, "zdf", page.number+1)) : "";
            addGroup(groups, items, "ZDF Mediathek", entries, token); next |= more;
        } catch (Exception e) { warnings.put("ZDF: " + message(e)); }
        JSONObject result = result(items, groups);
        if (next) result.put("pageToken", pageToken(new Page(page.query, page.channel, page.number+1)));
        if (warnings.length() > 0) result.put(items.length() == 0 ? "error" : "warning", warnings.join(" · ").replace("\"", ""));
        return result;
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
    private static JSONObject result(JSONArray items, JSONArray groups) throws Exception {
        return new JSONObject().put("items", items).put("groups", groups).put("source", "ARD / ZDF");
    }
    private static void addGroup(JSONArray groups, JSONArray flat, String title, List<Entry> entries, String token) throws Exception {
        JSONArray items = new JSONArray();
        for (Entry e : entries) {
            synchronized (ENTRIES) { ENTRIES.put(e.id, e); }
            JSONObject value = new JSONObject().put("id", e.id).put("title", e.title).put("author", e.author)
                    .put("provider", e.provider).put("channel", e.provider.equals("zdf") ? "ZDF" : "ARD")
                    .put("duration", e.duration).put("views", "").put("subtitle", e.subtitle)
                    .put("publishedAt", e.publishedAt).put("isLive", e.live)
                    .put("thumbnail", e.thumbnail.isEmpty() ? "" : "https://nava.local/mediathek/image/" + e.id);
            items.put(value); flat.put(value);
        }
        if (items.length() > 0) groups.put(new JSONObject().put("title", title).put("items", items).put("pageToken", token));
    }

    private static List<Entry> ardEntries(JSONObject data, int limit, boolean onlyLive) {
        LinkedHashMap<String, Entry> found = new LinkedHashMap<>();
        collectArd(data.optJSONArray("teasers"), found, onlyLive);
        JSONArray widgets = data.optJSONArray("widgets");
        if (widgets != null) for (int n=0; n<widgets.length(); n++) {
            JSONObject widget = widgets.optJSONObject(n);
            if (widget != null) collectArd(widget.optJSONArray("teasers"), found, onlyLive);
        }
        List<Entry> entries = new ArrayList<>(found.values());
        return entries.size() <= limit ? entries : new ArrayList<>(entries.subList(0, limit));
    }
    private static void collectArd(JSONArray teasers, Map<String, Entry> found, boolean onlyLive) {
        if (teasers == null) return;
        for (int i=0; i<teasers.length(); i++) {
            JSONObject t = teasers.optJSONObject(i); if (t == null) continue;
            boolean live = t.optString("type").equals("live");
            if (onlyLive ? !live : !(live || t.optString("type").equals("ondemand"))) continue;
            JSONObject links=t.optJSONObject("links"), target=links==null?null:links.optJSONObject("target");
            String detail=target==null?"":target.optString("href");
            if (!isApiUrl(detail) || !detail.contains("/item/")) continue;
            JSONObject images=t.optJSONObject("images"), image=images==null?null:images.optJSONObject("aspect16x9");
            String thumbnail=image==null?"":image.optString("src").replace("{width}","640");
            JSONObject publication=t.optJSONObject("publicationService"), show=t.optJSONObject("show");
            String author=publication==null?"ARD":publication.optString("name","ARD");
            String title=t.optString("longTitle",t.optString("mediumTitle",t.optString("shortTitle")));
            Entry entry=new Entry("ard",live?author:title,author,allowedImage(thumbnail)?thumbnail:"",detail,
                    live?"":t.optString("broadcastedOn"),show==null?"":show.optString("title"),t.optLong("duration"),live);
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
        try { return Instant.parse(value).isAfter(Instant.now()); } catch(Exception ignored) { return false; }
    }
    private static int liveOrder(String name) { return name.equals("Das Erste")?0:name.equals("tagesschau24")?1:2; }

    static boolean contains(String id) { synchronized(ENTRIES) { return id!=null && ENTRIES.containsKey(id); } }
    static Playback resolve(String id) throws Exception {
        Entry entry; synchronized(ENTRIES) { entry=ENTRIES.get(id); }
        if(entry==null)throw new IOException("This programme is no longer cached. Please reopen the list.");
        if(entry.playback!=null && System.currentTimeMillis()-entry.resolvedAt<300000)return entry.playback;
        JSONObject data=getJson(entry.detail);
        String selected="",mime=""; int best=-1;
        if(entry.provider.equals("ard")) {
            JSONArray widgets=data.optJSONArray("widgets");
            if(widgets!=null)for(int i=0;i<widgets.length();i++) {
                JSONObject w=widgets.optJSONObject(i); if(w==null)continue;
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
                        int score=streamScore(url,type)+Math.min(variant.optInt("maxVResolutionPx"),2160);
                        if(isTrustedStreamUrl(url)&&score>best) {best=score;selected=url;mime=type;}
                    }
                }
                // isGeoBlocked describes geographic rights, not the requester's actual location.
                // Let the CDN enforce them; never try another territory or modify geographic headers.
            }
        } else {
            JSONObject document=data.optJSONObject("document");
            JSONArray formats=document==null?null:document.optJSONArray("formitaeten");
            if(formats!=null)for(int i=0;i<formats.length();i++) {
                JSONObject format=formats.optJSONObject(i); if(format==null)continue;
                if(!format.optString("class","main").equals("main"))continue;
                if(!format.optString("language","deu").equals("deu"))continue;
                String url=format.optString("url"),type=format.optString("mimeType");
                int score=streamScore(url,type)+(format.optString("quality").equals("auto")?3000:format.optBoolean("hd")?1000:0);
                if(isTrustedStreamUrl(url)&&score>best) {best=score;selected=url;mime=type;}
            }
        }
        if(selected.isEmpty())throw new IOException("This programme has no supported stream. It may have expired or be restricted by region or airtime.");
        Playback playback=new Playback(selected,entry,mime);
        entry.playback=playback;entry.resolvedAt=System.currentTimeMillis();return playback;
    }
    private static int streamScore(String url,String mime) {
        String type=mime.toLowerCase(Locale.ROOT),path=Uri.parse(url).getPath();
        if(type.contains("mpegurl") || path!=null&&path.endsWith(".m3u8"))return 10000;
        if(type.equals("video/mp4") || path!=null&&path.endsWith(".mp4"))return 1000;
        return -100000;
    }
    static boolean isTrustedStreamUrl(String value) {
        Uri uri=safeHttps(value); if(uri==null)return false;String h=uri.getHost().toLowerCase(Locale.ROOT);
        return domain(h,"ard-mcdn.de") || domain(h,"tagesschau.de") || domain(h,"daserste.de")
                || h.equals("mcdn.br.de") || h.equals("mcdn.ndr.de") || h.equals("mcdn.swr.de") || h.equals("mcdn-one.ard.de")
                || h.equals("adaptive.ndr.de") || h.equals("wdradaptiv-vh.akamaihd.net") || h.equals("vod.unified.mdr.de")
                || h.equals("rbhlslive.akamaized.net") || h.equals("arteliveext.akamaized.net")
                || h.equals("zdf-hls-18.akamaized.net") || h.equals("zdf-hls-19.akamaized.net")
                || h.equals("zdfvod.akamaized.net") || h.equals("zdfvod-rwrtr.akamaized.net")
                || h.equals("nrodlzdf-a.akamaihd.net") || h.equals("rodlzdf-a.akamaihd.net")
                || h.equals("pdvideosdaserste-a.akamaihd.net");
    }
    private static boolean domain(String host,String domain) {return host.equals(domain)||host.endsWith("."+domain);}
    private static Uri safeHttps(String value) {
        if(value==null||value.length()>8192||value.indexOf('\\')>=0)return null;
        Uri uri=Uri.parse(value);
        return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null
                &&(uri.getPort()==-1||uri.getPort()==443)?uri:null;
    }
    private static boolean isApiUrl(String value) {
        Uri uri=safeHttps(value); if(uri==null)return false;
        return "api.ardmediathek.de".equals(uri.getHost())&&(uri.getPath().startsWith("/page-gateway/")||uri.getPath().startsWith("/search-system/"))
                || "zdf-prod-futura.zdf.de".equals(uri.getHost())&&uri.getPath().startsWith("/mediathekV2/");
    }
    private static boolean allowedImage(String value) {
        Uri uri=safeHttps(value); if(uri==null)return false;String host=uri.getHost();
        return "api.ardmediathek.de".equals(host)&&uri.getPath().startsWith("/image-service/")
                || "www.zdf.de".equals(host)&&uri.getPath().startsWith("/assets/")
                || "www.zdfheute.de".equals(host)&&uri.getPath().startsWith("/assets/")
                || "epg-image.zdf.de".equals(host);
    }
    private static JSONObject getJson(String url) throws Exception {
        return new JSONObject(new String(fetch(url,false),StandardCharsets.UTF_8));
    }
    private static byte[] fetch(String url,boolean image) throws Exception {
        for(int redirect=0;redirect<4;redirect++) {
            if(image?!allowedImage(url):!isApiUrl(url))throw new IOException("Unsupported channel address.");
            HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept",image?"image/jpeg,image/png,image/webp":"application/json");
                connection.setRequestProperty("User-Agent","OpenNava-TV/0.4");
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
                int limit=4*1024*1024;
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
        return e instanceof IOException&&e.getMessage()!=null&&e.getMessage().length()<300?e.getMessage():"Could not load the library. Check your connection and try again.";
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

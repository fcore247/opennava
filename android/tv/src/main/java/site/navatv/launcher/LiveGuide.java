package site.navatv.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The public broadcasters' linear channels, programme schedules and exact catch-up references. */
final class LiveGuide {
    private static final String ARD_ITEM = "https://api.ardmediathek.de/page-gateway/pages/ard/item/";
    private static final String ARD_EPG = "https://programm-api.ard.de/";
    private static final String ZDF = "https://zdf-prod-futura.zdf.de/mediathekV2/";
    private static final String ARTE_DAY = "https://api.arte.tv/api/emac/v4/de/web/pages/TV_GUIDE/?day=";
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter ZDF_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.UK);

    static final class Channel {
        final String id, name, provider;
        private final String sourceId;
        final boolean regional;
        private Channel(String id, String name, String crid, boolean regional) {
            this(id, name, crid.isEmpty() ? "zdf" : "arte".equals(id) ? "arte" : "ard",
                    crid.isEmpty() ? id + "-live-beitrag-100" :
                            Base64.getUrlEncoder().withoutPadding().encodeToString(crid.getBytes(StandardCharsets.UTF_8)),
                    regional);
        }
        private Channel(String id, String name, String provider, String sourceId, boolean regional) {
            this.id = id;
            this.name = name;
            this.provider = provider;
            this.sourceId = sourceId;
            this.regional = regional;
        }
    }

    static final class Programme {
        final String title, provider, detail, eventId;
        final long start, end;
        Programme(String title, long start, long end, String provider, String detail) {
            this(title, start, end, provider, detail, "");
        }
        Programme(String title, long start, long end, String provider, String detail, String eventId) {
            this.title = title;
            this.start = start;
            this.end = end;
            this.provider = provider;
            this.detail = detail;
            this.eventId = eventId;
        }
    }

    // IDs are the broadcasters' permanent live assets. Resolve their current CDN URLs at playback.
    private static final List<Channel> CHANNELS = buildChannels();

    private static List<Channel> buildChannels() {
        List<Channel> channels = new ArrayList<>(java.util.Arrays.asList(
            new Channel("daserste", "Das Erste", "crid://daserste.de/live/clip/abca07a3-3476-4851-b162-fde8f646d4c4", false),
            new Channel("zdf", "ZDF", "", false),
            new Channel("arte", "ARTE", "crid://arte.de/Livestream-ARTE", false),
            new Channel("ndr", "NDR Niedersachsen", "crid://ndr.de/Livestream-NDR-Niedersachsen", false),
            new Channel("zdfneo", "ZDFneo", "", false),
            new Channel("zdfinfo", "ZDFinfo", "", false),
            new Channel("3sat", "3sat", "crid://3sat.de/Livestream-3sat", false),
            new Channel("phoenix", "phoenix", "crid://wdr.de/Beitrag-17a88d7f-b950-4727-93a4-a7727b9165de", false),
            new Channel("kika", "KiKA", "crid://kika.de/Livestream-KiKa", false),
            new Channel("tagesschau24", "tagesschau24", "crid://daserste.de/tagesschau/livestream", false),
            new Channel("one", "ONE", "crid://wdr.de/Beitrag-1e604aae-5b87-4c34-8d8f-889b5f16d573", false),
            new Channel("alpha", "ARD alpha", "crid://br.de/Livestream-ARD-Alpha", false),
            new Channel("br", "BR Süd", "crid://br.de/Livestream-BR-Süd", false),
            new Channel("hr", "hr-fernsehen", "crid://hr.de/Livestream-HR", false),
            new Channel("mdr", "MDR Sachsen", "crid://mdr.de/Livestream-MDR-Sachsen", false),
            new Channel("rbb", "rbb Berlin", "crid://rbb-online.de/rbbfernsehen/live_berlin/sendeplatz---livestream---berlin---hls1", false),
            new Channel("radiobremen", "Radio Bremen TV", "crid://radiobremen.de/radio_bremen_livestream", false),
            new Channel("sr", "SR Fernsehen", "crid://sr-online.de/284860/livestream", false),
            new Channel("swr", "SWR Baden-Württemberg", "crid://swr.de/13481042", false),
            new Channel("wdr", "WDR Köln", "crid://wdr.de/Beitrag-3da664de-18c2-4650-a4ff-4f641472072b", false),
            new Channel("dw", "Deutsche Welle", "crid://deutschewelle.de/Livestream-DeutscheWelle", true),
            new Channel("br-nord", "BR Nord", "crid://br.de/Livestream-BR-Nord", true),
            new Channel("mdr-sachsen-anhalt", "MDR Sachsen-Anhalt", "crid://mdr.de/Livestream-MDR-Sachsen-Anhalt", true),
            new Channel("mdr-thueringen", "MDR Thüringen", "crid://mdr.de/Livestream-MDR-Thüringen", true),
            new Channel("ndr-hamburg", "NDR Hamburg", "crid://ndr.de/Livestream-NDR-Hamburg", true),
            new Channel("ndr-mecklenburg-vorpommern", "NDR Mecklenburg-Vorpommern", "crid://ndr.de/Livestream-NDR-Mecklenburg-Vorpommern", true),
            new Channel("ndr-schleswig-holstein", "NDR Schleswig-Holstein", "crid://ndr.de/Livestream-NDR-Schleswig-Holstein", true),
            new Channel("rbb-brandenburg", "rbb Brandenburg", "crid://rbb-online.de/rbbfernsehen/live_brandenburg/sendeplatz---livestream---brandenburg---hls1", true),
            new Channel("swr-rheinland-pfalz", "SWR Rheinland-Pfalz", "crid://swr.de/13481230", true),
            new Channel("wdr-aachen", "WDR Aachen", "crid://wdr.de/Beitrag-a7852020-d227-470f-952e-2b9d1372ed30", true),
            new Channel("wdr-bielefeld", "WDR Bielefeld", "crid://wdr.de/Beitrag-f9cce747-8190-46cf-9dfd-a5c0aabb9d42", true),
            new Channel("wdr-bonn", "WDR Bonn", "crid://wdr.de/Beitrag-42cafba1-dc0e-4099-b468-ca4bcb9673a0", true),
            new Channel("wdr-dortmund", "WDR Dortmund", "crid://wdr.de/Beitrag-04e75135-4075-49cc-9f52-27d363057463", true),
            new Channel("wdr-duesseldorf", "WDR Düsseldorf", "crid://wdr.de/Beitrag-604543a6-76b0-4a9b-a03c-396bc7d5ae0a", true),
            new Channel("wdr-duisburg", "WDR Duisburg", "crid://wdr.de/Beitrag-e6d79eba-4112-4943-8e54-c943ee5d554d", true),
            new Channel("wdr-essen", "WDR Essen", "crid://wdr.de/Beitrag-264ee0cb-3869-4dc4-a3f5-76c935781cf0", true),
            new Channel("wdr-muenster", "WDR Münster", "crid://wdr.de/Beitrag-b6b9c9fb-c3ca-4d8e-883a-2b06981be4eb", true),
            new Channel("wdr-siegen", "WDR Siegen", "crid://wdr.de/Beitrag-da55e910-a559-4aff-bac2-40d79a1253a7", true),
            new Channel("wdr-wuppertal", "WDR Wuppertal", "crid://wdr.de/Beitrag-b2b36d90-01f6-4927-bfca-279d8b7e7a95", true),
            new Channel("panoramabilder", "BR Panoramabilder", "crid://br.de/Event-Livestream-Panoramabilder", true)));
        return Collections.unmodifiableList(channels);
    }

    static List<Channel> channels() { return CHANNELS; }
    static List<Channel> channels(boolean includeRtl) { return channels(); }

    private static final Map<String, String> STREAMS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> STREAM_TIMES = new java.util.concurrent.ConcurrentHashMap<>();

    /** A recently resolved live address, so that changing channel does not wait for the broadcaster's API. */
    static String cachedStream(Channel channel) {
        Long at = STREAM_TIMES.get(channel.id);
        return at != null && System.currentTimeMillis() - at < 20 * 60000L ? STREAMS.get(channel.id) : null;
    }

    static String resolveStream(Channel channel) throws Exception {
        String url = stream(channel);
        STREAMS.put(channel.id, url);
        STREAM_TIMES.put(channel.id, System.currentTimeMillis());
        return url;
    }

    static void forgetStream(Channel channel) { STREAM_TIMES.remove(channel.id); }

    private static final Map<String, Object[]> NOW_NEXT = new java.util.concurrent.ConcurrentHashMap<>();

    /** The programme on air and the one after it. Either element may be null. */
    static Programme[] nowNext(Channel channel, long now) throws Exception {
        Object[] cached = NOW_NEXT.get(channel.id);
        if (cached != null && now - (Long) cached[0] < 90000) {
            Programme[] pair = (Programme[]) cached[1];
            if (pair[0] != null && now < pair[0].end) return pair;
        }
        List<Programme> rows;
        if ("ard".equals(channel.provider) && !channel.id.equals("panoramabilder")) {
            TreeMap<Long, Programme> light = new TreeMap<>();
            readArdRegional(channel, 6, light);
            rows = new ArrayList<>(light.values());
        } else rows = programmes(channel, now);
        Programme[] pair = pick(rows, now);
        NOW_NEXT.put(channel.id, new Object[] {now, pair});
        return pair;
    }

    /** Whatever is already known without touching the network. */
    static Programme[] knownNowNext(Channel channel, long now) {
        Object[] cached = NOW_NEXT.get(channel.id);
        Programme[] pair = cached == null ? new Programme[2] : pick(java.util.Arrays.asList((Programme[]) cached[1]), now);
        if (pair[0] == null) pair = pick(known(channel, now), now);
        return pair;
    }

    static List<Programme> known(Channel channel, long now) {
        String key = channel.id + "/" + (channel.regional ? "24" : tvDay(now).toString());
        CachedGuide cached;
        synchronized (CACHE) { cached = CACHE.get(key); }
        return cached == null ? Collections.<Programme>emptyList() : cached.rows;
    }

    private static Programme[] pick(List<Programme> rows, long now) {
        Programme[] pair = new Programme[2];
        for (Programme row : rows) {
            if (row == null) continue;
            if (row.start <= now && now < row.end) pair[0] = row;
            else if (row.start > now && (pair[1] == null || row.start < pair[1].start)) pair[1] = row;
        }
        return pair;
    }

    static String stream(Channel channel) throws Exception {

        if ("arte".equals(channel.provider)) {
            JSONObject live = MediathekService.getJson(ArteCatalog.LIVE);
            return ArteCatalog.stream(ArteCatalog.liveEntry(live), live);
        }
        if ("zdf".equals(channel.provider)) {
            JSONObject document = MediathekService.getJson(ZDF + "document/" + channel.sourceId).getJSONObject("document");
            if (channel.sourceId.equals(document.optString("id")) && "livevideo".equals(document.optString("type"))) {
                for (JSONObject variant : objects(document.optJSONArray("formitaeten"))) {
                    if ("main".equals(variant.optString("class")) && "deu".equals(variant.optString("language", "deu"))
                            && hls(variant)) return variant.getString("url");
                }
            }
        } else {
            JSONObject page = MediathekService.getJson(ARD_ITEM + channel.sourceId + "?embedded=true&mcV6=true");
            for (JSONObject widget : objects(page.optJSONArray("widgets"))) {
                if (!"player_live".equals(widget.optString("type")) || !channel.sourceId.equals(widget.optString("id"))
                        || widget.optBoolean("blockedByFsk")) continue;
                JSONObject media = child(child(widget, "mediaCollection"), "embedded");
                for (JSONObject track : objects(media.optJSONArray("streams"))) {
                    if (!"main".equals(track.optString("kind", "main"))) continue;
                    for (JSONObject variant : objects(track.optJSONArray("media"))) {
                        if (hls(variant)) return variant.getString("url");
                    }
                }
            }
        }
        throw new IOException(channel.name + " does not currently have a supported live stream.");
    }

    private static boolean hls(JSONObject variant) {
        return variant.optString("mimeType").toLowerCase(Locale.ROOT).contains("mpegurl")
                && MediathekService.isTrustedStreamUrl(variant.optString("url"));
    }

    private static final class CachedGuide {
        final List<Programme> rows;
        final long fetchedAt = System.currentTimeMillis();
        CachedGuide(List<Programme> rows) { this.rows = Collections.unmodifiableList(rows); }
    }

    private static final Map<String, CachedGuide> CACHE = new LinkedHashMap<String, CachedGuide>(72, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedGuide> eldest) { return size() > 200; }
    };

    static List<Programme> programmes(Channel channel, long atMillis) throws Exception {

        LocalDate day = tvDay(atMillis);
        List<Programme> rows = load(channel, day, atMillis);
        if (channel.regional) return rows;

        // Broadcasters use morning-to-morning TV days, with a different first slot on each station.
        TreeMap<Long, Programme> combined = new TreeMap<>();
        merge(combined, rows);
        if (rows.isEmpty() || atMillis <= rows.get(0).end) {
            try { merge(combined, load(channel, day.minusDays(1), atMillis)); } catch (IOException ignored) {}
        }
        if (rows.isEmpty() || atMillis >= rows.get(rows.size() - 1).start) {
            try { merge(combined, load(channel, day.plusDays(1), atMillis)); } catch (IOException ignored) {}
        }
        return new ArrayList<>(combined.values());
    }

    private static List<Programme> load(Channel channel, LocalDate day, long atMillis) throws Exception {
        int hours = channel.regional ? Math.max(24, (int)Math.min(168,
                Math.max(0, System.currentTimeMillis() - atMillis) / 3600000L + 4)) : 0;
        String key = channel.id + "/" + (channel.regional ? Integer.toString(hours) : day.toString());
        CachedGuide cached;
        synchronized (CACHE) { cached = CACHE.get(key); }
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 180000) return cached.rows;
        TreeMap<Long, Programme> rows = new TreeMap<>();
        try {
            if (channel.id.equals("panoramabilder")) return Collections.emptyList();
            if (channel.provider.equals("arte")) readArte(day, rows);
            else if (channel.provider.equals("zdf")) readZdf(channel.name, day, rows);
            else if (channel.regional) readArdRegional(channel, hours, rows);
            else readArdDay(channel, day, rows);
        } catch (Exception error) {
            if (cached != null) return cached.rows;
            throw error;
        }
        CachedGuide fresh = new CachedGuide(new ArrayList<>(rows.values()));
        synchronized (CACHE) { CACHE.put(key, fresh); }
        return fresh.rows;
    }

    private static void merge(TreeMap<Long, Programme> result, List<Programme> shows) {
        for (Programme item : shows) result.put(item.start, item);
    }

    private static void add(TreeMap<Long, Programme> result, Programme show) {
        if (show.start > 0 && show.end > show.start && !show.title.trim().isEmpty()) result.put(show.start, show);
    }

    private static void readArdDay(Channel channel, LocalDate day, TreeMap<Long, Programme> result) throws Exception {
        JSONObject guide = guideJson(ARD_EPG + "program/api/program?channelIds=" + channel.id + "&day=" + day);
        for (JSONObject station : objects(guide.optJSONArray("channels"))) {
            if (!channel.id.equals(station.optString("id")) || !channel.sourceId.equals(station.optString("crid"))) continue;
            JSONArray slots = station.optJSONArray("timeSlots");
            for (int i = 0; slots != null && i < slots.length(); i++) {
                for (JSONObject row : objects(slots.optJSONArray(i))) {
                    add(result, new Programme(row.optString("title"), instant(row, "broadcastedOn"), instant(row, "broadcastEnd"),
                            "ard", ardDetail(row), row.optString("id")));
                }
            }
        }
    }

    private static void readArdRegional(Channel channel, int hours, TreeMap<Long, Programme> result) throws Exception {
        JSONObject guide = guideJson(ARD_EPG + "nownext/api/channel?channel=" + channel.sourceId + "&pastHours=" + hours + "&futureEvents=30");
        if (!channel.sourceId.equals(guide.optString("id"))) return;
        for (JSONObject row : objects(guide.optJSONArray("events"))) {
            if (!row.optString("id").matches("[0-9a-f]{24}")) continue;  // DW may publish a whole-day ident.
            // endDate follows the actual playout clock. The next currentStartDate shares that boundary.
            long start = instant(row, "currentStartDate");
            if (start == 0) start = instant(row, "startDate");
            add(result, new Programme(child(row, "title").optString("short"), start,
                    instant(row, "endDate"), "ard", "", row.optString("id")));
        }
    }

    private static String ardDetail(JSONObject teaser) {
        JSONObject target = child(child(teaser, "links"), "target");
        JSONObject video = child(teaser, "video");
        String asset = target.optString("urlId");
        if (!asset.matches("[A-Za-z0-9_=-]+") || !"ard".equals(target.optString("partner"))
                || video.optBoolean("isTrailer") || !video.optBoolean("isMain", true)
                || !video.optBoolean("isPublished", true)) return "";
        return ARD_ITEM + asset + "?embedded=true&mcV6=true";
    }

    private static void readZdf(String channel, LocalDate day, TreeMap<Long, Programme> result) throws Exception {
        JSONObject guide = zdfDay(day);
        for (JSONObject cluster : objects(guide.optJSONArray("epgCluster"))) {
            if (!channel.equalsIgnoreCase(cluster.optString("name"))) continue;
            for (JSONObject row : objects(cluster.optJSONArray("teaser"))) {
                String id = row.optString("id");
                String url = row.optString("url");
                boolean fullVideo = "video".equals(row.optString("type")) && row.optBoolean("hasVideo")
                        && id.matches("[A-Za-z0-9_-]+") && url.equals(ZDF + "document/" + id);
                add(result, new Programme(row.optString("titel"), zdfMillis(row, "airtime"), zdfMillis(row, "airtimeEnd"),
                        "zdf", fullVideo ? url : "", channel + "/" + id));
            }
        }
    }

    private static String zdfDayKey;
    private static JSONObject zdfDayDocument;
    private static long zdfDayAt;

    private static synchronized JSONObject zdfDay(LocalDate day) throws Exception {
        String key = day.toString();
        if (!key.equals(zdfDayKey) || System.currentTimeMillis() - zdfDayAt > 170000) {
            zdfDayDocument = MediathekService.getJson(ZDF + "live-tv/" + day);
            zdfDayKey = key;
            zdfDayAt = System.currentTimeMillis();
        }
        return zdfDayDocument;
    }

    private static void readArte(LocalDate day, TreeMap<Long, Programme> result) throws Exception {
        JSONObject guide = guideJson(ARTE_DAY + day);
        for (JSONObject zone : objects(guide.optJSONArray("zones"))) {
            if (!"listing_TV_GUIDE".equals(zone.optString("code"))) continue;
            List<JSONObject> rows = objects(child(zone, "content").optJSONArray("data"));
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = rows.get(i), available = child(row, "availability");
                long start = instant(available, "upcomingDate");
                long end = i + 1 < rows.size() ? instant(child(rows.get(i + 1), "availability"), "upcomingDate") : 0;
                if (end <= start) end = start + 1000L * row.optInt("duration");
                String detail = child(row, "player").optString("config");
                boolean hasFull = false;
                for (JSONObject sticker : objects(row.optJSONArray("stickers"))) {
                    if ("FULL_VIDEO".equals(sticker.optString("code"))) hasFull = true;
                }
                if (!available.optBoolean("hasVideoStreams") || !hasFull || !ArteCatalog.isProgrammeConfig(detail)) detail = "";
                String title = row.optString("title");
                String subtitle = row.isNull("subtitle") ? "" : row.optString("subtitle");
                if (!subtitle.isEmpty() && !title.equals(subtitle)) title += " – " + subtitle;
                add(result, new Programme(title, start, end, "arte", detail, row.optString("programId")));
            }
        }
    }

    /** Look up the very same broadcast again when a programme is requested or a recording is reopened. */
    static Programme archive(Programme item) throws Exception {
        if (item == null || !item.detail.isEmpty()) return item;
        if ("ard".equals(item.provider) && item.eventId.matches("[0-9a-f]{24}")) {
            JSONObject teaser = guideJson(ARD_EPG + "program/api/teaser?teaserId=" + item.eventId);
            if (!item.eventId.equals(teaser.optString("id"))) return item;
            String detail = ardDetail(teaser);
            if (!detail.isEmpty()) return new Programme(item.title, item.start, item.end, "ard", detail, item.eventId);
            String station = child(teaser, "channel").optString("id");
            if (station.equals("3sat") || station.equals("kika") || station.equals("phoenix")) {
                LocalDate day = tvDay(item.start);
                TreeMap<Long, Programme> partners = new TreeMap<>();
                readZdf(station, day, partners);
                Programme partner = partners.get(item.start);
                if (partner != null && partner.title.equalsIgnoreCase(item.title) && !partner.detail.isEmpty()) return partner;
            }
            return item;
        }
        String channelId = item.provider.equals("arte") ? "arte" : item.provider.equals("zdf") ?
                item.eventId.split("/", 2)[0].toLowerCase(Locale.ROOT) : "";
        for (Channel channel : CHANNELS) {
            if (!channel.id.equals(channelId)) continue;
            for (Programme refreshed : programmes(channel, item.start)) {
                if (refreshed.start == item.start && refreshed.title.equalsIgnoreCase(item.title) && !refreshed.detail.isEmpty()) return refreshed;
            }
        }
        return item;
    }

    static String replay(Programme item) throws Exception {
        Programme found = archive(item);
        if (found != null && !found.detail.isEmpty()) {
            String id = MediathekService.acceptProgramme(found.provider, found.detail, found.title);
            if (id != null) return MediathekService.resolve(id).url;
        }
        throw new IOException("The broadcaster has not made this programme available to watch again.");
    }

    private static LocalDate tvDay(long stamp) {
        ZonedDateTime date = Instant.ofEpochMilli(stamp).atZone(BERLIN);
        return date.toLocalTime().isBefore(LocalTime.of(5, 30)) ? date.toLocalDate().minusDays(1) : date.toLocalDate();
    }

    private static long instant(JSONObject row, String key) {
        try { return java.time.OffsetDateTime.parse(row.optString(key)).toInstant().toEpochMilli(); }
        catch (java.time.DateTimeException ignored) { return 0; }
    }

    private static long zdfMillis(JSONObject row, String key) {
        try { return LocalDateTime.parse(row.optString(key), ZDF_TIME).atZone(BERLIN).toInstant().toEpochMilli(); }
        catch (java.time.DateTimeException ignored) { return 0; }
    }

    private static List<JSONObject> objects(JSONArray values) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; values != null && i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null) result.add(value);
        }
        return result;
    }

    private static JSONObject child(JSONObject parent, String key) {
        JSONObject value = parent.optJSONObject(key);
        return value == null ? new JSONObject() : value;
    }

    private static JSONObject guideJson(String address) throws Exception {
        // Only constructed internally from official endpoints and channel/date/event identifiers.
        HttpURLConnection http = (HttpURLConnection)new URL(address).openConnection();
        try {
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(7000);
            http.setReadTimeout(8000);
            http.setRequestProperty("Accept", "application/json");
            http.setRequestProperty("User-Agent", "Mediatheken/2 (Android TV)");
            int status = http.getResponseCode();
            if (status == 404 || status == 410) return new JSONObject();
            if (status != 200) throw new IOException("The channel has no schedule data at the moment.");
            try (InputStream input = http.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled.");
                    if (bytes.size() + count > 4 * 1024 * 1024) throw new IOException("Too many programme listings.");
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { http.disconnect(); }
    }

    private LiveGuide() {}
}

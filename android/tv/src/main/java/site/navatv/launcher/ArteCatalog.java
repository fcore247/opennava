package site.navatv.launcher;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** German ARTE editorial catalogue and the broadcaster's public player configuration. */
final class ArteCatalog {
    private static final String HOST = "https://api.arte.tv";
    private static final String WEB = "/api/emac/v4/de/web/";
    private static final String CONFIG = "/api/player/v2/config/de/";
    static final String LIVE = HOST + CONFIG + "LIVE";
    private static final Pattern PROGRAMME = Pattern.compile("[0-9]{6}-[0-9]{3}-[A-Z]");
    private static final Pattern ZONE = Pattern.compile(WEB + "zones/[a-fA-F0-9-]{36}/content");

    static final class Shelf {
        final String title, next;
        final List<MediathekService.Entry> entries;
        Shelf(String title, List<MediathekService.Entry> entries, String next) {
            this.title = title; this.entries = entries; this.next = next;
        }
    }

    static boolean isProgrammeConfig(String url) {
        return url.startsWith(HOST + CONFIG) && PROGRAMME.matcher(url.substring((HOST + CONFIG).length())).matches();
    }

    static boolean isApiUri(Uri uri) {
        if (!"api.arte.tv".equals(uri.getHost())) return false;
        String path = uri.getEncodedPath();
        if (path == null) return false;
        return path.equals(WEB + "pages/home") || path.equals(WEB + "search/results") || ZONE.matcher(path).matches()
                || LIVE.equals(uri.toString()) || isProgrammeConfig(uri.toString());
    }

    static boolean isStreamUri(Uri uri) {
        String path = uri.getEncodedPath();
        if (path == null) return false;
        String host = uri.getHost();
        return "manifest-arte.akamaized.net".equals(host) && path.startsWith("/api/manifest/")
                || "arte-cmafhls.akamaized.net".equals(host) && path.endsWith(".m3u8")
                || "artesimulcast.akamaized.net".equals(host) && path.startsWith("/hls/live/") && path.endsWith(".m3u8");
    }

    private static boolean isZone(String url) {
        Uri uri = MediathekService.safeHttps(url);
        return uri != null && "api.arte.tv".equals(uri.getHost()) && uri.getEncodedPath() != null
                && ZONE.matcher(uri.getEncodedPath()).matches();
    }

    static List<JSONObject> homeZones() throws Exception {
        JSONObject data = MediathekService.getJson(HOST + WEB + "pages/home?bypassESI=true");
        JSONArray zones = data.getJSONArray("zones");
        List<JSONObject> selected = new ArrayList<>();
        for (int i = 0; i < zones.length(); i++) {
            JSONObject zone = zones.optJSONObject(i);
            if (zone == null) continue;
            String code = zone.optString("code");
            if (code.equals("incontournables") || code.equals("films") || code.equals("documentaires")
                    || "New on ARTE".equalsIgnoreCase(zone.optString("title"))) selected.add(zone);
        }
        return selected;
    }

    static Shelf homeShelf(JSONObject zone) throws Exception {
        JSONObject content = zone.getJSONObject("content");
        String title = text(zone, "title");
        if (content.has("data")) return shelf(title, content);
        return loadZone(title, content.optString("esiInclude"));
    }

    static Shelf loadZone(String title, String url) throws Exception {
        if (!isZone(url)) throw new IOException("Unsupported channel address.");
        return shelf(title, MediathekService.getJson(url));
    }

    static List<Shelf> search(String encodedQuery) throws Exception {
        JSONObject data = MediathekService.getJson(HOST + WEB + "search/results?query=" + encodedQuery + "&page=1");
        JSONArray zones = data.getJSONArray("zones");
        List<Shelf> results = new ArrayList<>();
        for (int i = 0; i < zones.length(); i++) {
            JSONObject zone = zones.optJSONObject(i);
            if (zone == null || zone.optJSONObject("content") == null) continue;
            String title = text(zone, "title");
            results.add(shelf(title.isEmpty() ? "ARTE Mediathek" : title, zone.getJSONObject("content")));
        }
        return results;
    }

    private static Shelf shelf(String title, JSONObject content) throws Exception {
        JSONArray rows = content.getJSONArray("data");
        Map<String, MediathekService.Entry> found = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            MediathekService.Entry entry = programme(rows.optJSONObject(i));
            if (entry != null) found.put(entry.id, entry);
        }
        JSONObject pagination = content.optJSONObject("pagination");
        JSONObject links = pagination == null ? null : pagination.optJSONObject("links");
        String next = text(links, "next");
        return new Shelf(title, new ArrayList<>(found.values()), isZone(next) ? next : "");
    }

    private static MediathekService.Entry programme(JSONObject row) {
        if (row == null) return null;
        JSONObject kind = row.optJSONObject("kind");
        JSONObject rights = row.optJSONObject("availability");
        String id = text(row, "programId"), title = text(row, "title");
        if (kind == null || kind.optBoolean("isCollection") || !PROGRAMME.matcher(id).matches() || title.isEmpty()
                || rights == null || !"VOD".equals(rights.optString("type")) || !rights.optBoolean("hasVideoStreams")) return null;
        String start = text(rights, "start"), end = text(rights, "end");
        if (outsideWindow(start, end)) return null;
        String subtitle = text(row, "subtitle");
        if (!subtitle.isEmpty() && !title.equalsIgnoreCase(subtitle)) title += " – " + subtitle;
        String image = text(row.optJSONObject("mainImage"), "url").replace("__SIZE__", "640x360");
        return new MediathekService.Entry("arte", title, "ARTE", trustedImage(image), HOST + CONFIG + id, start,
                text(row, "shortDescription"), row.optLong("duration"), false);
    }

    private static boolean outsideWindow(String start, String end) {
        Instant now = Instant.now();
        try {
            return !start.isEmpty() && now.isBefore(Instant.parse(start))
                    || !end.isEmpty() && !now.isBefore(Instant.parse(end));
        } catch (DateTimeParseException unknown) { return false; }
    }

    private static String text(JSONObject object, String key) {
        return object == null || object.isNull(key) ? "" : object.optString(key).trim();
    }

    private static String trustedImage(String url) { return MediathekService.allowedImage(url) ? url : ""; }

    static MediathekService.Entry liveEntry(JSONObject document) throws Exception {
        JSONObject metadata = document.getJSONObject("data").getJSONObject("attributes").getJSONObject("metadata");
        JSONArray images = metadata.optJSONArray("images");
        String image = images == null ? "" : text(images.optJSONObject(0), "url");
        return new MediathekService.Entry("arte", "ARTE live", "ARTE", trustedImage(image), LIVE,
                "", text(metadata, "title"), 0, true);
    }

    static String stream(MediathekService.Entry entry, JSONObject document) throws Exception {
        JSONObject data = document.getJSONObject("data"), config = data.getJSONObject("attributes");
        if (!"ConfigPlayer".equals(data.optString("type")) || !"arte".equals(config.optString("provider"))
                || !config.has("live") || entry.live != config.optBoolean("live"))
            throw new IOException("This content is no longer available from the broadcaster.");
        JSONObject metadata = config.optJSONObject("metadata");
        if (!entry.live && !entry.detail.equals(HOST + CONFIG + text(metadata, "providerId")))
            throw new IOException("This content is no longer available from the broadcaster.");
        JSONObject rights = config.optJSONObject("rights");
        if (outsideWindow(text(rights, "begin"), text(rights, "end")) && !entry.live)
            throw new IOException("This content is no longer available from the broadcaster.");
        JSONObject restriction = config.optJSONObject("restriction");
        JSONObject geo = restriction == null ? null : restriction.optJSONObject("geoblocking");
        if (geo != null && geo.optBoolean("restrictedArea"))
            throw new IOException("The broadcaster declined access. This content may be limited by region or airtime.");
        JSONArray streams = config.optJSONArray("streams");
        String selected = "";
        int best = 0;
        for (int i = 0; streams != null && i < streams.length(); i++) {
            JSONObject stream = streams.optJSONObject(i);
            if (stream == null || !stream.optString("protocol").contains("HLS")) continue;
            String url = text(stream, "url");
            Uri uri = MediathekService.safeHttps(url);
            if (uri == null || !isStreamUri(uri)) continue;
            int score = languageScore(stream.optJSONArray("versions"), entry.live);
            if (score > best) { best = score; selected = url; }
        }
        return selected;
    }

    private static int languageScore(JSONArray versions, boolean live) {
        int best = 0;
        for (int i = 0; versions != null && i < versions.length(); i++) {
            JSONObject version = versions.optJSONObject(i);
            if (version == null) continue;
            if (live && "liveDE".equals(version.optString("code"))) return 4;
            if (live) continue;
            if ("de".equals(version.optString("audioLanguage"))) {
                if (!version.optBoolean("audioDescription")) return 4;
                best = Math.max(best, 2);
            } else if ("de".equals(version.optString("subtitleLanguage"))) best = Math.max(best, 3);
            else best = Math.max(best, 1);
        }
        return best;
    }

    private ArteCatalog() {}
}

package site.navatv.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * A viewer's own media library: either an opennava.mediathek/1 JSON document or a video RSS/Atom feed.
 * See guide/MEDIATHEK_FORMAT.md. Shared by the television and the phone; free of Android classes.
 */
public final class OwnLibrary {
    public static final String FORMAT = "opennava.mediathek/1";
    public static final int ITEM_LIMIT = 400, LIBRARY_LIMIT = 20;
    /** Larger catalogues are published at an address; an inline document travels through one adb command. */
    public static final int INLINE_LIMIT = 60_000;

    public static final class Item {
        public String title = "", subtitle = "", description = "", image = "", url = "", channel = "";
        /** Seconds; zero when unknown. */
        public long duration;
        /** Broadcast time in epoch milliseconds; zero when unknown. Links an item to a live channel. */
        public long start;
    }

    public static final class Section {
        public final String title;
        public final List<Item> items = new ArrayList<>();
        Section(String title) { this.title = title; }
    }

    public static final class Content {
        public String name = "";
        public final List<Section> sections = new ArrayList<>();

        public List<Item> all() {
            List<Item> result = new ArrayList<>();
            for (Section section : sections) result.addAll(section.items);
            return result;
        }
    }

    /** A registered library: where its content comes from. */
    public static final class Source {
        public final String id, name, color, url, document;

        private Source(String name, String color, String url, String document) {
            this.name = name;
            this.color = color;
            this.url = url;
            this.document = document;
            this.id = "lib-" + fingerprint(url.isEmpty() ? name.toLowerCase(Locale.ROOT) : url);
        }
    }

    /** Official, free video feeds that stand where German viewers find the broadcasters' libraries. */
    public static List<Source> americanPresets() {
        List<Source> result = new ArrayList<>();
        result.add(source("UCTV Science", "#2f8f7f", "https://podcast.uctv.tv/uctv_video_science.rss", ""));
        result.add(source("UCTV Health", "#b5567a", "https://podcast.uctv.tv/uctv_video_health.rss", ""));
        result.add(source("This Week in Tech", "#4a6fd1", "https://feeds.twit.tv/twit_video_hd.xml", ""));
        return result;
    }

    /** @throws IllegalArgumentException with a message that can be shown to the viewer. */
    public static Source source(String name, String color, String url, String document) {
        String label = clean(name, 40);
        String address = url == null ? "" : url.trim();
        String inline = document == null ? "" : document.trim();
        if (address.isEmpty() == inline.isEmpty())
            throw new IllegalArgumentException("Give either a feed address or a library document.");
        if (!address.isEmpty() && web(address).isEmpty())
            throw new IllegalArgumentException("Use a feed address that starts with https:// or http://.");
        if (!inline.isEmpty()) {
            if (inline.getBytes(StandardCharsets.UTF_8).length > INLINE_LIMIT)
                throw new IllegalArgumentException("This library is too large. Publish it at a web address instead.");
            Content content = parse(inline);
            if (label.isEmpty()) label = content.name;
        }
        if (label.isEmpty() && !address.isEmpty()) label = clean(URI.create(address).getHost(), 40);
        if (label.isEmpty()) throw new IllegalArgumentException("Give the library a name.");
        String tint = color == null || !color.matches("#[0-9a-fA-F]{6}") ? "#8b7cf6" : color.toLowerCase(Locale.ROOT);
        return new Source(label, tint, address, inline);
    }

    public static List<Source> parseSources(String json) {
        List<Source> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray rows = new JSONArray(json);
            for (int i = 0; i < rows.length() && result.size() < LIBRARY_LIMIT; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                try {
                    result.add(source(row.optString("name"), row.optString("color"), row.optString("url"),
                            row.optString("document")));
                } catch (IllegalArgumentException skipped) { /* keep the remaining libraries */ }
            }
        } catch (JSONException unreadable) { return new ArrayList<>(); }
        return result;
    }

    public static String writeSources(List<Source> sources) {
        JSONArray rows = new JSONArray();
        try {
            for (Source s : sources) rows.put(new JSONObject().put("id", s.id).put("name", s.name)
                    .put("color", s.color).put("url", s.url).put("document", s.document));
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return rows.toString();
    }

    /** @throws IllegalArgumentException when the text contains no playable video. */
    public static Content parse(String text) {
        String value = text == null ? "" : text.replace("﻿", "").trim();
        Content content = value.startsWith("{") ? json(value) : feed(value);
        if (content.all().isEmpty()) throw new IllegalArgumentException("No playable videos were found.");
        return content;
    }

    private static Content json(String text) {
        try {
            JSONObject root = new JSONObject(text);
            if (!FORMAT.equals(root.optString("format")))
                throw new IllegalArgumentException("The document needs \"format\": \"" + FORMAT + "\".");
            Content content = new Content();
            content.name = clean(root.optString("name"), 40);
            JSONArray sections = root.optJSONArray("sections");
            int count = 0;
            for (int i = 0; sections != null && i < sections.length(); i++) {
                JSONObject part = sections.optJSONObject(i);
                if (part == null) continue;
                Section section = new Section(clean(part.optString("title"), 80));
                JSONArray items = part.optJSONArray("items");
                for (int n = 0; items != null && n < items.length() && count < ITEM_LIMIT; n++) {
                    JSONObject row = items.optJSONObject(n);
                    if (row == null) continue;
                    Item item = new Item();
                    item.title = clean(row.optString("title"), 220);
                    item.url = web(row.optString("url"));
                    item.subtitle = clean(row.optString("subtitle"), 220);
                    item.description = clean(row.optString("description"), 1200);
                    item.image = web(row.optString("image"));
                    item.duration = Math.max(0, row.optLong("duration"));
                    item.channel = row.optString("channel").matches("[a-z0-9-]{1,64}") ? row.optString("channel") : "";
                    item.start = time(row.optString("start"));
                    if (item.title.isEmpty() || item.url.isEmpty()) continue;
                    section.items.add(item);
                    count++;
                }
                if (!section.items.isEmpty()) content.sections.add(section);
            }
            return content;
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("This is not valid JSON.");
        }
    }

    private static Content feed(String text) {
        // Reject document type declarations outright; a video feed has no use for entities.
        if (text.contains("<!DOCTYPE") || text.contains("<!ENTITY"))
            throw new IllegalArgumentException("This feed contains unsupported XML declarations.");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            org.w3c.dom.Document xml = factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
            Content content = new Content();
            NodeList titles = xml.getElementsByTagName("title");
            if (titles.getLength() > 0) content.name = clean(titles.item(0).getTextContent(), 40);
            Section section = new Section("Latest");
            NodeList entries = xml.getElementsByTagName("item");
            if (entries.getLength() == 0) entries = xml.getElementsByTagName("entry");
            for (int i = 0; i < entries.getLength() && section.items.size() < ITEM_LIMIT; i++) {
                Element entry = (Element) entries.item(i);
                Item item = new Item();
                item.title = clean(childText(entry, "title"), 220);
                String summary = childText(entry, "description");
                if (summary.isEmpty()) summary = childText(entry, "media:description");
                if (summary.isEmpty()) summary = childText(entry, "summary");
                item.description = clean(summary.replaceAll("<[^>]*>", " "), 1200);
                NodeList all = entry.getElementsByTagName("*");
                for (int n = 0; n < all.getLength(); n++) {
                    Element element = (Element) all.item(n);
                    String tag = element.getTagName();
                    String address = web(element.getAttribute("url"));
                    String type = element.getAttribute("type").toLowerCase(Locale.ROOT);
                    boolean video = type.startsWith("video/") || type.contains("mpegurl")
                            || "video".equals(element.getAttribute("medium"));
                    if (item.url.isEmpty() && (tag.equals("enclosure") || tag.equals("media:content")) && video)
                        item.url = address;
                    if (item.image.isEmpty() && (tag.equals("media:thumbnail")
                            || tag.equals("media:content") && type.startsWith("image/"))) item.image = address;
                    if (item.image.isEmpty() && tag.equals("itunes:image")) item.image = web(element.getAttribute("href"));
                    if (item.duration == 0 && tag.equals("media:content"))
                        item.duration = seconds(element.getAttribute("duration"));
                }
                if (item.duration == 0) item.duration = seconds(childText(entry, "itunes:duration"));
                if (!item.title.isEmpty() && !item.url.isEmpty()) section.items.add(item);
            }
            if (!section.items.isEmpty()) content.sections.add(section);
            return content;
        } catch (IllegalArgumentException rejected) {
            throw rejected;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("This is neither an OpenNava library nor a video feed.");
        }
    }

    private static String childText(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element && ((Element) node).getTagName().equals(tag))
                return node.getTextContent().trim();
        return "";
    }

    private static long seconds(String value) {
        long total = 0;
        for (String part : value.trim().split(":")) {
            if (!part.matches("[0-9]{1,6}")) return 0;
            total = total * 60 + Long.parseLong(part);
        }
        return total;
    }

    private static long time(String value) {
        try { return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
        catch (java.time.DateTimeException invalid) { return 0; }
    }

    /** The address itself when it is a plain web address, otherwise empty. */
    private static String web(String value) {
        String address = value == null ? "" : value.trim();
        if (address.isEmpty() || address.length() > 1500) return "";
        try {
            URI uri = new URI(address);
            boolean web = "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            return web && uri.getHost() != null && uri.getRawUserInfo() == null ? address : "";
        } catch (URISyntaxException invalid) { return ""; }
    }

    private static String clean(String value, int limit) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return result.length() > limit ? result.substring(0, limit).trim() : result;
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 6; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private OwnLibrary() {}
}

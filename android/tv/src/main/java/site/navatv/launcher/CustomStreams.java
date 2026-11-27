package site.navatv.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The viewer's own stream addresses. Shared by the television and the phone; free of Android classes. */
public final class CustomStreams {
    public static final int LIMIT = 60;
    public static final int NAME_LENGTH = 60;
    public static final int URL_LENGTH = 1500;

    public static final class Entry {
        public final String id, name, url;

        private Entry(String name, String url) {
            this.id = "custom-" + fingerprint(url);
            this.name = name;
            this.url = url;
        }
    }

    /** @throws IllegalArgumentException with a message that can be shown to the viewer. */
    public static Entry entry(String name, String address) {
        String url = address == null ? "" : address.trim();
        if (url.isEmpty()) throw new IllegalArgumentException("Enter a stream address.");
        if (url.length() > URL_LENGTH) throw new IllegalArgumentException("This stream address is too long.");
        URI uri;
        try { uri = new URI(url); }
        catch (URISyntaxException invalid) { throw new IllegalArgumentException("This is not a valid stream address."); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http"))
            throw new IllegalArgumentException("Use an address that starts with https:// or http://.");
        String authority = uri.getRawAuthority();
        if (authority == null || authority.isEmpty() || uri.getRawUserInfo() != null)
            throw new IllegalArgumentException("This is not a valid stream address.");
        String label = name == null ? "" : name.replaceAll("[\\p{Cntrl}]+", " ").trim();
        if (label.isEmpty()) label = uri.getHost() == null ? "My stream" : uri.getHost();
        if (label.length() > NAME_LENGTH) label = label.substring(0, NAME_LENGTH).trim();
        return new Entry(label, url);
    }

    /** Reads a stored or transferred list. Rows that are no longer valid are left out. */
    public static List<Entry> parse(String json) {
        List<Entry> result = new ArrayList<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONArray rows = new JSONArray(json);
            for (int i = 0; i < rows.length() && result.size() < LIMIT; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                try { add(result, entry(row.optString("name"), row.optString("url"))); }
                catch (IllegalArgumentException skipped) { /* keep the remaining streams */ }
            }
        } catch (JSONException unreadable) { return new ArrayList<>(); }
        return result;
    }

    public static String write(List<Entry> entries) {
        JSONArray rows = new JSONArray();
        try {
            for (Entry item : entries)
                rows.put(new JSONObject().put("id", item.id).put("name", item.name).put("url", item.url));
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return rows.toString();
    }

    /** Adding an address that is already saved renames it in place. */
    public static boolean add(List<Entry> list, Entry added) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(added.id)) { list.set(i, added); return true; }
        }
        if (list.size() >= LIMIT) return false;
        list.add(added);
        return true;
    }

    /** Accepts an extended M3U playlist or plain lines of addresses. */
    public static List<Entry> fromPlaylist(String text) {
        List<Entry> result = new ArrayList<>();
        if (text == null) return result;
        String name = "";
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.replace("﻿", "").trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int comma = titleSeparator(line);
                name = comma < 0 ? "" : line.substring(comma + 1).trim();
            } else if (!line.startsWith("#")) {
                try { add(result, entry(name, line)); }
                catch (IllegalArgumentException skipped) { /* not a web stream */ }
                name = "";
                if (result.size() >= LIMIT) break;
            }
        }
        return Collections.unmodifiableList(result);
    }

    // Quoted attributes such as group-title="News, local" may contain commas themselves.
    private static int titleSeparator(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) return i;
        }
        return -1;
    }

    private static String fingerprint(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 6; i++) out.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private CustomStreams() {}
}

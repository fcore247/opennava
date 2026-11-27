package site.navatv.companion;

import android.content.Context;
import android.content.SharedPreferences;

import site.navatv.launcher.OwnLibrary;
import site.navatv.launcher.PhoneCatalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One shape for every media library, built in or added by the viewer. */
final class LibraryHub {
    static final class Card {
        String title = "", subtitle = "", description = "", image = "", detail = "", channel = "";
        long duration, start;
        /** "ard", "zdf", "arte" or the ID of an own library. */
        String library = "";
    }

    static final class Shelf {
        final String title;
        final List<Card> cards;
        /** A grid shows many titles at once; a row is swiped sideways. */
        final boolean grid;
        Shelf(String title, List<Card> cards, boolean grid) {
            this.title = title;
            this.cards = cards;
            this.grid = grid;
        }
    }

    static final class Info {
        final String id, name;
        final int color;
        Info(String id, String name, int color) {
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    private static final Info[] BUILT_IN = {
            new Info("ard", "ARD", 0xff3d7bff), new Info("zdf", "ZDF", 0xffff8a3d), new Info("arte", "ARTE", 0xffff5a4e)
    };
    private static final long FRESH = 5 * 60_000;

    private final SharedPreferences prefs;
    private final PhoneCatalog catalogue;
    private volatile List<OwnLibrary.Source> own;
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();

    /** In the USA the German broadcasters make way for American libraries. */
    private volatile boolean american;

    void setAmerican(boolean value) {
        if (american == value) return;
        american = value;
        cache.clear();
    }

    private List<OwnLibrary.Source> shown() {
        // Until the TV has reported its list, the phone shows the same presets the TV adds.
        return american && own.isEmpty() ? OwnLibrary.americanPresets() : own;
    }

    LibraryHub(Context context, PhoneCatalog catalogue) {
        this.catalogue = catalogue;
        prefs = context.getSharedPreferences("libraries", Context.MODE_PRIVATE);
        // The last list stays browsable while the TV is out of reach.
        own = OwnLibrary.parseSources(prefs.getString("sources", ""));
    }

    /** Returns true when the television's list differs from the known one. */
    boolean update(List<OwnLibrary.Source> latest) {
        String next = OwnLibrary.writeSources(latest);
        if (next.equals(OwnLibrary.writeSources(own))) return false;
        own = new ArrayList<>(latest);
        cache.clear();
        prefs.edit().putString("sources", next).apply();
        return true;
    }

    List<OwnLibrary.Source> sources() { return Collections.unmodifiableList(own); }

    List<Info> all() {
        List<Info> result = new ArrayList<>();
        if (!american) Collections.addAll(result, BUILT_IN);
        for (OwnLibrary.Source source : shown()) result.add(new Info(source.id, source.name, colour(source.color)));
        return result;
    }

    Info info(String id) {
        for (Info info : all()) if (info.id.equals(id)) return info;
        List<Info> everything = all();
        return everything.isEmpty() ? BUILT_IN[0] : everything.get(0);
    }

    static boolean builtIn(String id) { return id.equals("ard") || id.equals("zdf") || id.equals("arte"); }

    private static int colour(String value) {
        try { return android.graphics.Color.parseColor(value); }
        catch (IllegalArgumentException invalid) { return 0xff8b7cf6; }
    }

    /** Call off the UI thread. An empty query returns the library's front page. */
    @SuppressWarnings("unchecked")
    List<Shelf> load(String id, String query) throws Exception {
        String term = query == null ? "" : query.trim();
        String key = id + "|" + term;
        Object[] saved = cache.get(key);
        if (saved != null && System.currentTimeMillis() - (Long) saved[0] < FRESH) return (List<Shelf>) saved[1];
        List<Shelf> shelves = builtIn(id) ? broadcaster(id, term) : ownShelves(id, term);
        if (cache.size() > 30) cache.clear();
        cache.put(key, new Object[] {System.currentTimeMillis(), shelves});
        return shelves;
    }

    private List<Shelf> broadcaster(String id, String query) throws Exception {
        List<Card> cards = new ArrayList<>();
        for (PhoneCatalog.Item item : query.isEmpty() ? catalogue.home(id) : catalogue.search(id, query)) {
            Card card = new Card();
            card.title = item.title;
            card.subtitle = item.subtitle;
            card.image = item.image;
            card.detail = item.detail;
            card.duration = item.duration;
            card.library = id;
            cards.add(card);
        }
        return arrange(query.isEmpty() ? "Recommended" : "Results", cards, !query.isEmpty());
    }

    private static List<Shelf> arrange(String name, List<Card> cards, boolean search) {
        List<Shelf> shelves = new ArrayList<>();
        if (cards.isEmpty()) return shelves;
        if (search || cards.size() <= 5) {
            shelves.add(new Shelf(name, cards, true));
        } else {
            int split = Math.min(10, cards.size());
            shelves.add(new Shelf(name, cards.subList(0, split), false));
            if (split < cards.size()) shelves.add(new Shelf("More to watch", cards.subList(split, cards.size()), true));
        }
        return shelves;
    }

    private OwnLibrary.Content content(String id) throws Exception {
        for (OwnLibrary.Source source : shown()) {
            if (source.id.equals(id))
                return OwnLibrary.parse(source.document.isEmpty() ? download(source.url) : source.document);
        }
        throw new IOException("This library is no longer on your TV.");
    }

    private static Card card(String library, OwnLibrary.Item item) {
        Card card = new Card();
        card.title = item.title;
        card.subtitle = item.subtitle;
        card.description = item.description;
        card.image = item.image;
        card.detail = item.url;
        card.duration = item.duration;
        card.channel = item.channel;
        card.start = item.start;
        card.library = library;
        return card;
    }

    private List<Shelf> ownShelves(String id, String query) throws Exception {
        OwnLibrary.Content content = content(id);
        if (!query.isEmpty()) {
            String wanted = query.toLowerCase(Locale.ROOT);
            List<Card> found = new ArrayList<>();
            for (OwnLibrary.Item item : content.all()) {
                if ((item.title + " " + item.subtitle + " " + item.description).toLowerCase(Locale.ROOT).contains(wanted))
                    found.add(card(id, item));
            }
            return arrange("Results", found, true);
        }
        List<Shelf> shelves = new ArrayList<>();
        for (OwnLibrary.Section section : content.sections) {
            List<Card> cards = new ArrayList<>();
            for (OwnLibrary.Item item : section.items) cards.add(card(id, item));
            if (content.sections.size() == 1) return arrange(section.title.isEmpty() ? "Videos" : section.title, cards, false);
            shelves.add(new Shelf(section.title.isEmpty() ? "Videos" : section.title, cards, false));
        }
        return shelves;
    }

    /** Videos in the viewer's own libraries that are linked to a live channel, newest first. Off the UI thread. */
    List<Card> catchUp(String channel, long now) {
        List<Card> result = new ArrayList<>();
        for (OwnLibrary.Source source : shown()) {
            try {
                for (OwnLibrary.Item item : content(source.id).all()) {
                    if (channel.equals(item.channel) && item.start <= now) result.add(card(source.id, item));
                }
            } catch (Exception unavailable) { /* the other libraries still answer */ }
        }
        result.sort((a, b) -> Long.compare(b.start, a.start));
        return result;
    }

    static String download(String address) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
        try {
            http.setConnectTimeout(8000);
            http.setReadTimeout(10000);
            http.setRequestProperty("User-Agent", "OpenNava (Android)");
            if (http.getResponseCode() != 200) throw new IOException("The server answered " + http.getResponseCode() + ".");
            try (InputStream in = http.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (bytes.size() + count > 4 * 1024 * 1024) throw new IOException("This file is too large.");
                    bytes.write(buffer, 0, count);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { http.disconnect(); }
    }
}

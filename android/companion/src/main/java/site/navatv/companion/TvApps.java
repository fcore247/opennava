package site.navatv.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** The apps on the connected TV. The list and pictures are kept per TV, so they appear at once next time. */
final class TvApps {
    static final class Entry {
        final String id, name, component;
        final boolean banner;
        final long version;
        Entry(JSONObject row) {
            id = row.optString("package");
            name = row.optString("name");
            component = row.optString("component");
            banner = row.optBoolean("banner");
            version = row.optLong("version");
        }
        boolean valid() {
            return PACKAGE.matcher(id).matches() && component.startsWith(id + "/") && !name.isEmpty();
        }
    }

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    static final String[] NETFLIX = {"com.netflix.ninja", "com.netflix.mediaclient"};
    static final String[] YOUTUBE = {"com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube"};

    private final MainActivity a;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> pictures = new LruCache<>(140);
    private List<Entry> entries = Collections.emptyList();
    private String owner = "";
    private long asked;
    private boolean loading;
    /** Why the list is missing, in words for the viewer. */
    String problem = "";

    TvApps(MainActivity activity) { a = activity; }

    boolean loading() { return loading; }

    private static String key(TvConnection.Device device) {
        String id = device.androidId.isEmpty() ? device.host : device.androidId;
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private File folder() { return new File(a.getCacheDir(), "tvapps/" + owner); }

    List<Entry> list() {
        if (a.connected() && !key(a.device).equals(owner)) {
            owner = key(a.device);
            problem = "";
            asked = 0;
            pictures.evictAll();
            entries = parse(read(new File(folder(), "apps.json")));
        }
        return entries;
    }

    Entry find(String[] packages) {
        for (String id : packages) for (Entry entry : list()) if (entry.id.equals(id)) return entry;
        return null;
    }

    private static String read(File file) {
        try { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
        catch (java.io.IOException missing) { return "[]"; }
    }

    private static List<Entry> parse(String json) {
        List<Entry> found = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(json);
            for (int i = 0; i < rows.length() && found.size() < 120; i++) {
                Entry entry = new Entry(rows.getJSONObject(i));
                if (entry.valid()) found.add(entry);
            }
        } catch (org.json.JSONException broken) { return new ArrayList<>(); }
        return found;
    }

    /** Ask the TV again, at most once a minute unless the viewer asks. */
    void refresh(boolean now) {
        if (!a.connected() || loading) return;
        list();
        long time = android.os.SystemClock.elapsedRealtime();
        if (!now && asked != 0 && time - asked < 60_000) return;
        asked = time;
        loading = true;
        TvConnection connection = a.tv;
        String forTv = owner;
        loader.execute(() -> {
            String json = null;
            String failure = "";
            try { json = connection.installedApps(); }
            catch (Exception unavailable) { failure = a.error(unavailable); }
            String loaded = json, message = failure;
            main.post(() -> {
                loading = false;
                if (!forTv.equals(owner)) return;
                problem = message;
                boolean changed = false;
                if (loaded != null) {
                    String before = read(new File(folder(), "apps.json"));
                    changed = !before.equals(loaded);
                    entries = parse(loaded);
                    write(new File(folder(), "apps.json"), loaded.getBytes(StandardCharsets.UTF_8));
                }
                if (changed || !message.isEmpty() || now) a.appsChanged();
            });
        });
    }

    private static void write(File file, byte[] bytes) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (OutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        catch (java.io.IOException ignored) { /* the picture is fetched again next time */ }
    }

    /** Shows the app's TV banner or icon, from memory, from disk, or from the TV. */
    void picture(Entry entry, ImageView view) {
        String name = entry.id + "-" + entry.version + ".png";
        view.setTag(name);
        Bitmap ready = pictures.get(name);
        if (ready != null) {
            view.setImageBitmap(ready);
            return;
        }
        view.setImageDrawable(null);
        File file = new File(folder(), name);
        TvConnection connection = a.tv;
        loader.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (file.isFile()) {
                    try (InputStream in = new FileInputStream(file)) { bitmap = BitmapFactory.decodeStream(in); }
                } else if (connection.isConnected()) {
                    byte[] bytes = connection.appPicture(entry.id);
                    bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bitmap != null) write(file, bytes);
                }
            } catch (Exception unavailable) { /* the tile keeps its letter */ }
            Bitmap result = bitmap;
            if (result != null) main.post(() -> {
                pictures.put(name, result);
                if (name.equals(view.getTag())) {
                    view.setAlpha(0);
                    view.setImageBitmap(result);
                    view.animate().alpha(1).setDuration(220).start();
                }
            });
        });
    }

    void close() { loader.shutdownNow(); }
}

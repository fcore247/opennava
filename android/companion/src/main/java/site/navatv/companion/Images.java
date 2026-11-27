package site.navatv.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Covers, loaded off the UI thread with a bounded cache and bounded downloads. */
final class Images {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };

    void into(ImageView view, String url) {
        view.setTag(url);
        if (url == null || !url.startsWith("https://")) return;
        Bitmap known = cache.get(url);
        if (known != null) {
            view.setImageBitmap(known);
            return;
        }
        try {
            workers.execute(() -> {
                Bitmap loaded = load(url);
                if (loaded == null) return;
                cache.put(url, loaded);
                main.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(loaded); });
            });
        } catch (RejectedExecutionException closing) { /* the activity is going away */ }
    }

    private Bitmap load(String url) {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(url).openConnection();
            http.setConnectTimeout(3500);
            http.setReadTimeout(6000);
            http.setRequestProperty("User-Agent", "OpenNava (Android)");
            try (InputStream in = http.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 4_000_000) return null;
                    out.write(buffer, 0, count);
                }
                byte[] data = out.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                options.inJustDecodeBounds = false;
                options.inSampleSize = 1;
                while (options.outWidth / options.inSampleSize > 900 || options.outHeight / options.inSampleSize > 900)
                    options.inSampleSize *= 2;
                return BitmapFactory.decodeByteArray(data, 0, data.length, options);
            }
        } catch (IOException unavailable) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    void close() { workers.shutdownNow(); }
}

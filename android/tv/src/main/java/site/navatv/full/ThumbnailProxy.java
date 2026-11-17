package site.navatv.full;

import android.net.Uri;
import android.util.LruCache;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;

/** Image-only fetcher. No credentials, arbitrary redirects or executable responses. */
final class ThumbnailProxy {
    private static final int MAX_IMAGE = 4 * 1024 * 1024;
    private final LruCache<String, byte[]> cache = new LruCache<String, byte[]>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, byte[] value) { return value.length; }
    };
    static boolean allowed(Uri uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null
                || !(uri.getPort() == -1 || uri.getPort() == 443)) return false;
        String host = uri.getHost();
        return "i.ytimg.com".equals(host) || "yt3.googleusercontent.com".equals(host) || "yt3.ggpht.com".equals(host);
    }
    static String normalized(String source) {
        if (source == null) return "";
        if (source.startsWith("//")) source = "https:" + source;
        return allowed(Uri.parse(source)) ? source : "";
    }
    WebResourceResponse fetch(Uri source) {
        String original = source.toString();
        byte[] hit = cache.get(original);
        if (hit != null) return image(hit);
        String next = original;
        for (int redirect = 0; redirect < 4; redirect++) {
            HttpURLConnection connection = null;
            try {
                if (!allowed(Uri.parse(next))) break;
                connection = (HttpURLConnection) new URL(next).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp");
                connection.setRequestProperty("Cookie", "");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) break;
                    next = new URL(new URL(next), location).toString();
                    continue;
                }
                String type = connection.getContentType();
                if (status != 200 || type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("image/")
                        || type.toLowerCase(java.util.Locale.ROOT).contains("svg") || connection.getContentLengthLong() > MAX_IMAGE) break;
                try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (bytes.size() + count > MAX_IMAGE) throw new java.io.IOException("image-size");
                        bytes.write(buffer, 0, count);
                    }
                    byte[] data = bytes.toByteArray();
                    // Require a recognized raster signature; response text never reaches WebView.
                    if (!raster(data)) break;
                    cache.put(original, data);
                    return image(data);
                }
            } catch (Exception ignored) { break; }
            finally { if (connection != null) connection.disconnect(); }
        }
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Image unavailable",
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }
    private static boolean raster(byte[] b) {
        return b.length >= 12 && ((b[0] == (byte)0xff && b[1] == (byte)0xd8)
                || (b[0] == (byte)0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
                || (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P'));
    }
    private static WebResourceResponse image(byte[] data) {
        String mime = data[0] == (byte)0xff ? "image/jpeg" : data[0] == (byte)0x89 ? "image/png" : "image/webp";
        return new WebResourceResponse(mime, null, 200, "OK", Collections.singletonMap("X-Content-Type-Options", "nosniff"), new ByteArrayInputStream(data));
    }
}

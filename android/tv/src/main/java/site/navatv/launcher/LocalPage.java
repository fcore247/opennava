package site.navatv.launcher;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Each local WebView can load only its designated document and packaged subresources. */
final class LocalPage {
    private static final String IMAGE_PREFIX = "/mediathek/image/";
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self'; frame-src 'none'; connect-src 'none'; "
            + "object-src 'none'; base-uri 'none'; form-action 'none'";
    static WebResourceResponse response(Context context, WebResourceRequest request, String entry, MediathekService service) {
        Uri uri = request.getUrl();
        if (!local(uri) || !"GET".equals(request.getMethod())) return error(403);
        String path = uri.getEncodedPath();
        if (path == null || !path.matches("/[A-Za-z0-9_/.-]+") || path.contains("..")) return error(404);
        if (path.startsWith(IMAGE_PREFIX)) {
            String id = path.substring(IMAGE_PREFIX.length());
            if (service == null || request.isForMainFrame() || !validId(id)) return error(404);
            return service.image(id);
        }
        String file = path.substring(1);
        if (request.isForMainFrame() && !entry.equals(file)) return error(403);
        try {
            InputStream input = context.getAssets().open(file);
            Map<String,String> headers = new HashMap<>();
            headers.put("Content-Security-Policy", CSP);
            headers.put("X-Content-Type-Options", "nosniff");
            String type = mime(file);
            return new WebResourceResponse(type, type.startsWith("text/") || type.equals("application/javascript") ? "UTF-8" : null,
                    200, "OK", headers, input);
        } catch (IOException e) { return error(404); }
    }
    private static boolean local(Uri uri) {
        return "https".equals(uri.getScheme()) && "nava.local".equals(uri.getHost()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    private static String mime(String file) {
        if (file.endsWith(".html")) return "text/html";
        if (file.endsWith(".js")) return "application/javascript";
        if (file.endsWith(".css")) return "text/css";
        if (file.endsWith(".ttf")) return "font/ttf";
        if (file.endsWith(".woff2")) return "font/woff2";
        if (file.endsWith(".svg")) return "image/svg+xml";
        if (file.endsWith(".png")) return "image/png";
        if (file.endsWith(".jpg") || file.endsWith(".jpeg")) return "image/jpeg";
        if (file.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
    private static WebResourceResponse error(int status) {
        return new WebResourceResponse("text/plain", "UTF-8", status, status == 403 ? "Forbidden" : "Not found",
                java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,128}"); }
}

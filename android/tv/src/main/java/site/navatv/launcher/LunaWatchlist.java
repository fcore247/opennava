package site.navatv.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The browser hands over public programme links. Only the full launcher opens them on selection. */
public final class LunaWatchlist extends SQLiteOpenHelper {
    private static final int LIMIT = 200;
    private static final Pattern NETFLIX = Pattern.compile("/(?:[A-Za-z]{2}(?:-[A-Za-z]{2})?/)?(?:title|watch)/([0-9]{1,16})/?");
    private static LunaWatchlist instance;

    private LunaWatchlist(Context context) { super(context, "luna-watchlist.db", null, 1); }

    public static synchronized LunaWatchlist get(Context context) {
        if (instance == null) instance = new LunaWatchlist(context.getApplicationContext());
        return instance;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE programmes (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT UNIQUE NOT NULL, title TEXT NOT NULL, provider TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) { throw new IllegalStateException("Unknown watchlist version"); }

    static String canonical(String value) throws URISyntaxException {
        if (value.length() > 2048 || value.indexOf('\\') >= 0) return null;
        URI uri = new URI(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        String path = uri.getRawPath();
        if (path == null || path.contains("//") || !path.equals(uri.normalize().getRawPath())) return null;
        switch (host) {
            case "netflix.com":
                Matcher title = NETFLIX.matcher(path);
                String id = title.matches() ? title.group(1) : null;
                if (id == null && path.matches("/(?:[A-Za-z]{2}(?:-[A-Za-z]{2})?/)?browse/?")) {
                    String queryId = Uri.parse(value).getQueryParameter("jbv");
                    if (queryId != null && queryId.matches("[0-9]{1,16}")) id = queryId;
                }
                if (id == null) return null;
                path = "/title/" + id;
                break;
            case "ardmediathek.de": if (!path.matches("/(?:video|sendung)/[^/]+(?:/.*)?")) return null; break;
            case "zdf.de": if (!path.matches("/[^/]+(?:/.*)?")) return null; break;
            case "arte.tv": if (!path.matches("/(?:de|fr|en|es|it|pl)/videos/[^/]+(?:/.*)?")) return null; break;
            default: return null;
        }
        return "https://www." + host + path;
    }

    private static String provider(String url) {
        switch (Uri.parse(url).getHost()) {
            case "www.ardmediathek.de": return "ARD";
            case "www.zdf.de": return "ZDF";
            case "www.arte.tv": return "ARTE";
            case "www.netflix.com": return "Netflix";
            default: throw new IllegalArgumentException("Unknown provider");
        }
    }

    boolean save(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > 16000) return false;
        try {
            byte[] bytes = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject item = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!(item.opt("title") instanceof String) || !(item.opt("url") instanceof String)) return false;
            String title = item.getString("title").trim();
            if (title.isEmpty() || title.length() > 240 || title.matches("(?s).*\\p{Cc}.*")) return false;
            String url = canonical(item.getString("url"));
            if (url == null) return false;
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues data = new ContentValues();
                data.put("title", title);
                if (db.update("programmes", data, "url = ?", new String[]{url}) == 0) {
                    try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM programmes", null)) {
                        count.moveToFirst();
                        if (count.getInt(0) >= LIMIT) return false;
                    }
                    data.put("url", url);
                    data.put("provider", provider(url));
                    db.insertOrThrow("programmes", null, data);
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            return true;
        } catch (JSONException | URISyntaxException | IllegalArgumentException | android.database.SQLException failure) {
            return false;
        }
    }

    public String list(Context context) {
        JSONArray list = new JSONArray();
        if (!VisitorMode.isVisitor(context)) return list.toString();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id, title, provider FROM programmes ORDER BY id DESC", null)) {
            while (cursor.moveToNext()) list.put(new JSONObject().put("id", cursor.getString(0))
                    .put("title", cursor.getString(1)).put("provider", cursor.getString(2)));
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        return list.toString();
    }

    public boolean remove(Context context, String id) {
        return VisitorMode.isVisitor(context) && id != null && getWritableDatabase().delete("programmes", "id = ?", new String[]{id}) > 0;
    }

    public void open(Activity activity, String id) {
        if (!VisitorMode.isVisitor(activity) || id == null) return;
        try (Cursor row = getReadableDatabase().rawQuery("SELECT url, title, provider FROM programmes WHERE id = ?", new String[]{id})) {
            if (!row.moveToFirst()) return;
            Uri uri = Uri.parse(row.getString(0));
            String title = row.getString(1), provider = row.getString(2);
            if (provider.equals("Netflix")) {
                String pkg = "com.netflix.ninja";
                if (start(activity, new Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) return;
                Intent netflix = activity.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (netflix != null && start(activity, netflix)) {
                    notice(activity, "Search Netflix for “" + title + "”.");
                } else { notice(activity, "Install Netflix on this TV to open “" + title + "”."); }
                return;
            }
            // Direct references use the same broadcaster player and access checks as the TV guide.
            String detail = nativeProgramme(uri, provider);
            if (detail != null && start(activity, new Intent(activity, PlayerActivity.class)
                    .putExtra("provider", provider.toLowerCase(Locale.ROOT)).putExtra("programme", detail).putExtra("title", title))) return;
            if (!start(activity, new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)))
                notice(activity, "No app on this TV can open the " + provider + " link.");
        }
    }

    private static String nativeProgramme(Uri uri, String provider) {
        java.util.List<String> parts = uri.getPathSegments();
        if (parts.isEmpty()) return null;
        String last = parts.get(parts.size() - 1);
        if (provider.equals("ARD") && parts.get(0).equals("video") && last.matches("Y3JpZD[A-Za-z0-9_=+-]{6,512}"))
            return "https://api.ardmediathek.de/page-gateway/pages/ard/item/" + Uri.encode(last);
        if (provider.equals("ZDF") && last.matches("[a-zA-Z0-9-]+-[0-9]{3}(?:\\.html)?"))
            return "https://zdf-prod-futura.zdf.de/mediathekV2/document/" + last.replaceFirst("\\.html$", "");
        if (provider.equals("ARTE") && parts.size() >= 3 && parts.get(0).equals("de") && parts.get(2).matches("[0-9]{6}-[0-9]{3}-[A-Z]"))
            return "https://api.arte.tv/api/player/v2/config/de/" + parts.get(2);
        return null;
    }

    private static boolean start(Activity activity, Intent intent) {
        try { activity.startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException unavailable) { return false; }
    }

    private static void notice(Activity activity, String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}

package site.navatv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared, bounded weather cache for the fixed location selected in the app assets. */
public final class WeatherBridge {
    private static final long MINUTE = 60_000L;
    private static final long REFRESH_AFTER = 30 * MINUTE;
    private static final long STALE_AFTER = 90 * MINUTE;
    private static final long EXPIRE_AFTER = 6 * 60 * MINUTE;
    private static final long RETRY_AFTER = 5 * MINUTE;
    private static final DateTimeFormatter WALL_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm");
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final Object REQUEST_LOCK = new Object();
    private static boolean loading;
    private static long lastAttempt = -1;

    private final Location location;
    private final SharedPreferences preferences;

    public WeatherBridge(Context context) {
        Context app = context.getApplicationContext();
        preferences = app.getSharedPreferences("ortswetter-v1", Context.MODE_PRIVATE);
        Location selected = null;
        try (InputStream asset = app.getAssets().open("assets/wetterort.json")) {
            selected = new Location(new JSONObject(read(asset, 4096)));
        } catch (Exception unavailable) {
            // A missing or incomplete configuration intentionally leaves weather disabled.
        }
        location = selected;
    }

    @JavascriptInterface public String cached() {
        if (location == null) return "{}";
        JSONObject saved = stored();
        if (saved == null) return "{}";
        try {
            long now = System.currentTimeMillis();
            long age = now - saved.getLong("observedAtMs");
            if (age < -5 * MINUTE || age > EXPIRE_AFTER) return "{}";
            ZoneId zone = ZoneId.of(saved.getString("timezone"));
            LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
            saved.put("localTime", localTime(now, zone));
            saved.put("ageMinutes", Math.max(0, age) / MINUTE);
            saved.put("stale", age > STALE_AFTER);
            saved.put("sunrise", "");
            saved.put("sunset", "");
            JSONObject sun = saved.optJSONObject("_sun");
            JSONObject day = sun == null ? null : sun.optJSONObject(today.toString());
            if (day != null) {
                saved.put("sunrise", day.getString("sunrise"));
                saved.put("sunset", day.getString("sunset"));
            }
            saved.remove("_sun");
            saved.remove("_location");
            return saved.toString();
        } catch (Exception invalidCache) { return "{}"; }
    }

    @JavascriptInterface public void refresh() {
        if (location == null) return;
        synchronized (REQUEST_LOCK) {
            if (loading) return;
            long elapsed = SystemClock.elapsedRealtime();
            if (lastAttempt >= 0 && elapsed - lastAttempt < RETRY_AFTER) return;
            long now = System.currentTimeMillis();
            JSONObject saved = stored();
            if (saved != null) {
                long fetchedAge = now - saved.optLong("fetchedAtMs");
                long observedAge = now - saved.optLong("observedAtMs");
                if (fetchedAge >= 0 && fetchedAge < REFRESH_AFTER
                        && observedAge >= -5 * MINUTE && observedAge <= STALE_AFTER) return;
            }
            loading = true;
            lastAttempt = elapsed;
        }
        NETWORK.execute(() -> {
            try {
                JSONObject fresh = fetch();
                preferences.edit().putString("snapshot", fresh.toString()).apply();
            } catch (Exception unavailable) {
                // Keep the most recent successful response. cached() applies the age policy offline.
            } finally {
                synchronized (REQUEST_LOCK) { loading = false; }
            }
        });
    }

    private JSONObject stored() {
        try {
            JSONObject saved = new JSONObject(preferences.getString("snapshot", "{}"));
            return location.key.equals(saved.optString("_location")) ? saved : null;
        } catch (JSONException invalidCache) { return null; }
    }

    private JSONObject fetch() throws Exception {
        URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + location.latitude
                + "&longitude=" + location.longitude + "&current=weather_code,temperature_2m,is_day"
                + "&daily=sunrise,sunset&timezone=auto&timeformat=unixtime&forecast_days=2");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("weather response");
            try (InputStream input = connection.getInputStream()) {
                return snapshot(new JSONObject(read(input, 32 * 1024)), System.currentTimeMillis());
            }
        } finally { connection.disconnect(); }
    }

    private JSONObject snapshot(JSONObject body, long now) throws JSONException {
        ZoneId zone = ZoneId.of(body.getString("timezone"));
        JSONObject current = body.getJSONObject("current");
        long observed = Math.multiplyExact(current.getLong("time"), 1000L);
        if (observed > now + 5 * MINUTE || now - observed > EXPIRE_AFTER)
            throw new JSONException("observation time outside cache window");
        int day = current.getInt("is_day");
        int weatherCode = current.getInt("weather_code");
        double temperature = current.getDouble("temperature_2m");
        if ((day != 0 && day != 1) || weatherCode < 0 || weatherCode > 99 || !Double.isFinite(temperature))
            throw new JSONException("invalid weather observation");
        return new JSONObject().put("_location", location.key).put("_sun", solarDays(body.optJSONObject("daily"), zone))
                .put("location", location.name).put("timezone", zone.getId()).put("observedAtMs", observed)
                .put("observedAt", localTime(observed, zone)).put("fetchedAtMs", now)
                .put("weatherCode", weatherCode).put("temperatureC", temperature).put("isDay", day == 1);
    }

    private static JSONObject solarDays(JSONObject daily, ZoneId zone) throws JSONException {
        JSONObject days = new JSONObject();
        if (daily == null) return days;
        JSONArray rises = daily.optJSONArray("sunrise"), sets = daily.optJSONArray("sunset");
        if (rises == null || sets == null) return days;
        for (int i = 0; i < Math.min(2, Math.min(rises.length(), sets.length())); i++) {
            long rise = rises.optLong(i), set = sets.optLong(i);
            if (rise <= 0 || set <= rise) continue;
            String sunrise = localTime(rise * 1000, zone);
            String sunset = localTime(set * 1000, zone);
            days.put(sunrise.substring(0, 10), new JSONObject().put("sunrise", sunrise).put("sunset", sunset));
        }
        return days;
    }

    private static String localTime(long timestamp, ZoneId zone) {
        return WALL_TIME.format(Instant.ofEpochMilli(timestamp).atZone(zone));
    }

    private static String read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > limit) throw new IOException("response too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    private static final class Location {
        final String name, key;
        final double latitude, longitude;

        Location(JSONObject config) throws JSONException {
            name = config.getString("name").trim();
            latitude = config.getDouble("latitude");
            longitude = config.getDouble("longitude");
            if (name.isEmpty() || name.length() > 100 || !Double.isFinite(latitude) || Math.abs(latitude) > 90
                    || !Double.isFinite(longitude) || Math.abs(longitude) > 180) throw new JSONException("invalid place");
            key = name + ":" + latitude + ":" + longitude;
        }
    }
}

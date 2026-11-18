package site.navatv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Full-mode configuration and settings requests for an ADB-authorized Luna connection. */
public final class LunaTvSettings {
    private static final String DEFAULT = "{\"version\":1,\"tiles\":[\"luna\",\"live\",\"library\"],\"libraries\":[\"ard\",\"zdf\",\"arte\"],\"language\":\"auto\",\"appearance\":\"graphite\",\"showClock\":true,\"showWatchlistCount\":true,\"defaultSearchService\":\"auto\"}";
    private static final Set<String> SERVICES = set("ard","zdf","arte");
    private static final Set<String> TILES = set("luna","live","library","apps","youtube","netflix","search","settings","ard","zdf","arte");
    private static final Set<String> LANGUAGES = set("auto","de","en","fr","es","it","pt","nl","da","sv","no","fi","pl","cs","sk","hu","ro","tr","uk","ja","ko");

    private static Set<String> set(String... ids) { return new HashSet<>(Arrays.asList(ids)); }
    private static SharedPreferences store(Context context) { return context.getSharedPreferences("luna-tv-settings", Context.MODE_PRIVATE); }
    public static String read(Context context) { return store(context).getString("configuration", DEFAULT); }

    private static Set<String> allowedList(JSONArray list, Set<String> allowed) throws JSONException {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < list.length(); i++) {
            String value = list.getString(i);
            if (!allowed.contains(value) || !values.add(value)) throw new JSONException("Invalid setting");
        }
        return values;
    }

    // A second paired computer can write between Luna's read and this call.
    // Compare the observed fields under the provider lock before committing the replacement.
    private static boolean matchesSnapshot(JSONObject current, JSONObject expected) throws JSONException {
        for (String key : Arrays.asList("version", "language", "appearance")) {
            if (!current.get(key).equals(expected.get(key))) return false;
        }
        for (String key : Arrays.asList("tiles", "libraries")) {
            if (!current.getJSONArray(key).toString().equals(expected.getJSONArray(key).toString())) return false;
        }
        return current.optBoolean("showClock", true) == expected.getBoolean("showClock")
                && current.optBoolean("showWatchlistCount", true) == expected.getBoolean("showWatchlistCount")
                && current.optString("defaultSearchService", "auto").equals(expected.getString("defaultSearchService"));
    }

    private static boolean update(Context context, String input) {
        if (input == null || input.length() > 4096) return false;
        try {
            JSONObject source = new JSONObject(new String(Base64.decode(input, Base64.URL_SAFE | Base64.NO_WRAP), StandardCharsets.UTF_8));
            JSONArray tiles = source.getJSONArray("tiles"), services = source.getJSONArray("libraries");
            String language = source.getString("language"), appearance = source.getString("appearance");
            if (source.getInt("version") != 1 || tiles.length() != 3 || services.length() > 3
                    || !LANGUAGES.contains(language) || !("rose".equals(appearance) || "graphite".equals(appearance))) return false;
            Set<String> selectedServices = allowedList(services, SERVICES);
            Set<String> selectedTiles = allowedList(tiles, TILES);
            selectedTiles.retainAll(SERVICES);
            if (!selectedServices.containsAll(selectedTiles)) return false;
            JSONObject previous = new JSONObject(read(context));
            if (source.has("expected") && !matchesSnapshot(previous, source.getJSONObject("expected"))) return false;
            String search = source.has("defaultSearchService") ? source.getString("defaultSearchService") : previous.optString("defaultSearchService","auto");
            if (!(search.equals("auto") || search.equals("netflix") || search.equals("youtube") || selectedServices.contains(search))) {
                if (source.has("defaultSearchService")) return false;
                search = "auto";
            }
            JSONObject configuration = new JSONObject()
                    .put("showClock", source.has("showClock") ? source.getBoolean("showClock") : previous.optBoolean("showClock", true))
                    .put("showWatchlistCount", source.has("showWatchlistCount") ? source.getBoolean("showWatchlistCount") : previous.optBoolean("showWatchlistCount", true))
                    .put("defaultSearchService", search).put("version", 1).put("tiles",tiles).put("libraries",services)
                    .put("language",language).put("appearance",appearance);
            return store(context).edit().putString("configuration", configuration.toString()).putBoolean("connected", true).commit();
        } catch (JSONException | IllegalArgumentException invalid) { return false; }
    }

    public static synchronized boolean requestOpen(Context context) {
        SharedPreferences prefs = store(context);
        if (!VisitorMode.isVisitor(context) || !prefs.getBoolean("connected", false)) return false;
        if (!prefs.getString("request", "").isEmpty()) return true;
        return prefs.edit().putString("request", UUID.randomUUID().toString().replace("-","")).commit();
    }

    static synchronized Bundle call(Context context, String method, String arg) {
        Bundle result = new Bundle();
        SharedPreferences prefs = store(context);
        switch (method) {
            case "luna_settings_get":
                if (!prefs.getBoolean("connected", false)) prefs.edit().putBoolean("connected", true).commit();
                result.putString("settings", CompanionLibrary.encode(read(context)));
                break;
            case "luna_settings_set": result.putBoolean("saved", update(context,arg)); break;
            case "luna_settings_request":
                if (!prefs.getBoolean("connected", false)) prefs.edit().putBoolean("connected", true).commit();
                result.putString("request", prefs.getString("request", "none"));
                break;
            case "luna_settings_ack":
                result.putBoolean("saved", arg != null && arg.matches("[a-f0-9]{32}") && arg.equals(prefs.getString("request",""))
                        && prefs.edit().remove("request").commit());
                break;
            default: throw new IllegalArgumentException("Unknown settings method");
        }
        return result;
    }

    private LunaTvSettings() {}
}

package site.navatv.companion;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SavedTv {
    final String id, host, name, invite;
    final int port;

    SavedTv(String id, String host, int port, String name, String invite) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.name = name;
        this.invite = invite;
    }

    boolean sameDevice(TvConnection.Device device) {
        if (!id.isEmpty() && !device.androidId.isEmpty()) return id.equals(device.androidId);
        return port == device.port && host.equals(device.host) && name.equals(device.name);
    }

    static List<SavedTv> list(SharedPreferences prefs) {
        List<SavedTv> result = new ArrayList<>();
        try {
            JSONArray entries = new JSONArray(prefs.getString("knownTvs", "[]"));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject row = entries.getJSONObject(i);
                result.add(new SavedTv(row.getString("id"), row.getString("host"), row.getInt("port"),
                        row.getString("name"), row.optString("invite")));
            }
        } catch (JSONException invalid) { result.clear(); }
        String oldHost = prefs.getString("host", "");
        if (result.isEmpty() && !oldHost.isEmpty()) {
            result.add(new SavedTv(prefs.getString("connectedId", ""), oldHost,
                    prefs.getInt("port", 5555), prefs.getString("name", "TV"),
                    prefs.getString("connectedInvite", prefs.getString("invite", ""))));
        }
        return result;
    }

    static String encode(List<SavedTv> tvs) {
        JSONArray entries = new JSONArray();
        for (SavedTv tv : tvs) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", tv.id);
            row.put("host", tv.host);
            row.put("port", tv.port);
            row.put("name", tv.name);
            row.put("invite", tv.invite);
            entries.put(new JSONObject(row));
        }
        return entries.toString();
    }
}

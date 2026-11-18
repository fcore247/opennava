package site.navatv.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.webkit.WebResourceResponse;
import org.json.JSONException;
import org.json.JSONObject;

/** Reuse the broadcaster catalogue and player already supplied by the combined TV app. */
public final class FullArte {
    public interface Sink { void send(String id, String json); }
    private final MediathekService service;

    public FullArte(Context context, Sink sink) { service = new MediathekService(context, sink::send); }
    public boolean search(String id, String query) {
        if (query == null || query.length() > 160) return false;
        try {
            String term = query.trim();
            service.request(id, new JSONObject().put("channel","arte").put("method",term.isEmpty() ? "home" : "search").put("query",term).toString());
            return true;
        } catch (JSONException invalid) { return false; }
    }
    public boolean has(String id) { return id != null && MediathekService.belongsTo(id, "arte"); }
    public WebResourceResponse image(String id) { return service.image(id); }
    public void open(Activity activity, String id) {
        if (has(id)) activity.startActivity(new Intent(activity, PlayerActivity.class).putExtra("id", id));
    }
    public void close() { service.close(); }
}

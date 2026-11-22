package site.navatv.launcher;

import android.app.Activity;
import android.app.ListActivity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;

/** Standalone station catalogue reached from the senior live controls. */
public final class LiveLibraryActivity extends ListActivity {
    private final ArrayList<String> ids = new ArrayList<>();
    private MediathekService service;
    private TextView title;
    private String provider;

    private static Intent destination(Context ctx, LiveGuide.Channel station) {
        switch (station.provider) {
            case "ard": case "zdf": case "arte":
                return new Intent(ctx, LiveLibraryActivity.class).putExtra("provider", station.provider);
            case "rtl": return ctx.getPackageManager().getLeanbackLaunchIntentForPackage("de.rtli.tvnow");
            default: return null;
        }
    }

    static boolean available(Context ctx, LiveGuide.Channel station) { return destination(ctx, station) != null; }

    static boolean open(Activity caller, LiveGuide.Channel station) {
        Intent intent = destination(caller, station);
        if (intent == null) return false;
        try { caller.startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException unavailable) { return false; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        provider = getIntent().getStringExtra("provider");
        if (!("ard".equals(provider) || "zdf".equals(provider) || "arte".equals(provider))) {
            finish();
            return;
        }
        int inset = Math.round(26 * getResources().getDisplayMetrics().density);
        getListView().setPadding(inset, inset, inset, inset);
        title = new TextView(this);
        title.setTextSize(26);
        title.setTextColor(0xfff3edf9);
        getListView().addHeaderView(title, null, false);
        service = new MediathekService(this, (id, json) -> runOnUiThread(() -> display(json)));
        load();
    }

    private void load() {
        title.setText(provider.toUpperCase(Locale.ROOT) + " Mediathek · Wird geladen …");
        try {
            service.request("station", new JSONObject().put("method", "home").put("channel", provider).toString());
        } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
    }

    private void display(String json) {
        if (isFinishing() || isDestroyed()) return;
        ids.clear();
        ArrayList<String> titles = new ArrayList<>();
        try {
            JSONArray items = new JSONObject(json).optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject row = items.getJSONObject(i);
                String id = row.getString("id");
                if (ids.contains(id)) continue;
                ids.add(id);
                titles.add(row.getString("title"));
            }
        } catch (JSONException invalid) {
            ids.clear();
            titles.clear();
        }
        title.setText(provider.toUpperCase(Locale.ROOT) + " Mediathek");
        if (ids.isEmpty()) titles.add("Keine Sendungen verfügbar · Erneut laden");
        setListAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles));
        getListView().requestFocus();
    }

    @Override protected void onListItemClick(ListView list, View row, int position, long id) {
        int index = position - list.getHeaderViewsCount();
        if (index < 0) return;
        if (index < ids.size()) startActivity(new Intent(this, PlayerActivity.class).putExtra("id", ids.get(index)));
        else load();
    }

    @Override protected void onDestroy() {
        if (service != null) service.close();
        super.onDestroy();
    }
}

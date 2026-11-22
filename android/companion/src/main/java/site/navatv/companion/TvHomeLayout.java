package site.navatv.companion;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ordered home tiles from one TV. A folder holds packages, never nested folders. */
final class TvHomeLayout {
    static final int MAX_TOP = 32, MAX_APPS = 48, MAX_FOLDER = 16, MAX_NAME = 32;
    final List<Tile> tiles = new ArrayList<>();
    final Map<String, String> apps = new LinkedHashMap<>();
    final String authority;
    long revision;
    boolean grid;

    static final class Tile {
        final String id;
        String name;
        final List<String> children;

        private Tile(String id, String name, List<String> children) {
            this.id = id;
            this.name = name;
            this.children = children;
        }
        static Tile app(String pkg) { return new Tile(pkg, null, null); }
        static Tile folder(String name) {
            return new Tile(UUID.randomUUID().toString(), name, new ArrayList<>());
        }
        boolean isFolder() { return children != null; }
    }

    TvHomeLayout(String authority, JSONObject json) throws JSONException {
        this.authority = authority;
        revision = json.getLong("revision");
        String layout = json.getString("layout");
        if (!layout.equals("row") && !layout.equals("grid")) throw new JSONException("Unknown layout");
        grid = layout.equals("grid");
        JSONArray appsJson = json.getJSONArray("apps");
        for (int i = 0; i < appsJson.length(); i++) {
            JSONObject app = appsJson.getJSONObject(i);
            apps.put(app.getString("package"), app.getString("name"));
        }
        JSONArray items = json.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String type = item.getString("type");
            if (type.equals("app")) {
                tiles.add(Tile.app(item.getString("package")));
            } else if (type.equals("folder")) {
                JSONArray array = item.getJSONArray("apps");
                List<String> children = new ArrayList<>();
                for (int j = 0; j < array.length(); j++) children.add(array.getString(j));
                tiles.add(new Tile(item.getString("id"), item.getString("name"), children));
            } else {
                throw new JSONException("Unknown home tile");
            }
        }
    }

    JSONObject request() throws JSONException {
        JSONArray items = new JSONArray();
        for (Tile tile : tiles) {
            JSONObject item = new JSONObject();
            if (tile.isFolder()) {
                item.put("type", "folder").put("id", tile.id).put("name", tile.name)
                        .put("apps", new JSONArray(tile.children));
            } else {
                item.put("type", "app").put("package", tile.id);
            }
            items.put(item);
        }
        return new JSONObject().put("revision", revision).put("layout", grid ? "grid" : "row")
                .put("items", items);
    }

    String name(String pkg) { return apps.getOrDefault(pkg, pkg); }
    boolean contains(String pkg) {
        for (Tile tile : tiles) {
            if (tile.isFolder() ? tile.children.contains(pkg) : tile.id.equals(pkg)) return true;
        }
        return false;
    }
    int appCount() {
        int count = 0;
        for (Tile tile : tiles) count += tile.isFolder() ? tile.children.size() : 1;
        return count;
    }
    Tile folder(String id) {
        for (Tile tile : tiles) if (tile.isFolder() && tile.id.equals(id)) return tile;
        return null;
    }
}

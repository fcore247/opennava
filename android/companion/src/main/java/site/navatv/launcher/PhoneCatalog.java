package site.navatv.launcher;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Native broadcaster results with canonical programme links. Call off the main thread. */
public final class PhoneCatalog implements AutoCloseable {
    private final MediathekService service;
    public PhoneCatalog(Context context) { service = new MediathekService(context.getApplicationContext(), (id,json) -> {}); }

    public static final class Item {
        public final String title, detail, image, subtitle;
        public final long duration;
        Item(MediathekService.Entry e) {
            title = e.title; detail = e.detail; image = e.thumbnail; subtitle = e.subtitle; duration = e.duration;
        }
    }
    public List<Item> home(String provider) throws Exception { return items(service.browse(provider,null)); }
    public List<Item> search(String provider,String query) throws Exception {
        if (query == null || query.trim().isEmpty()) return home(provider);
        return items(service.browse(provider,query.trim()));
    }
    private static List<Item> items(List<MediathekService.Entry> list) {
        List<Item> result = new ArrayList<>(list.size());
        for (MediathekService.Entry entry : list) result.add(new Item(entry));
        return result;
    }
    @Override public void close() { service.close(); }
}

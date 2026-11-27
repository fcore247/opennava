package site.navatv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The television owns the viewer's streams. A paired phone adds and removes them through this store. */
final class CustomStreamStore {
    private static final int ENCODING = Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP;
    private static final String PREFERENCES = "custom_streams", KEY = "list";

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static synchronized List<CustomStreams.Entry> list(Context context) {
        return CustomStreams.parse(preferences(context).getString(KEY, ""));
    }

    /** Make the saved streams part of the channel line-up. */
    static synchronized void load(Context context) {
        RegionStore.apply(context);
        LiveGuide.setCustomStreams(list(context));
    }

    private static boolean save(Context context, List<CustomStreams.Entry> entries) {
        if (!preferences(context).edit().putString(KEY, CustomStreams.write(entries)).commit()) return false;
        LiveGuide.setCustomStreams(entries);
        return true;
    }

    /** Either every stream is stored or none of them, so that a full list is reported honestly. */
    static synchronized boolean addAll(Context context, List<CustomStreams.Entry> added) {
        List<CustomStreams.Entry> entries = new ArrayList<>(list(context));
        for (CustomStreams.Entry item : added) if (!CustomStreams.add(entries, item)) return false;
        return !added.isEmpty() && save(context, entries);
    }

    static synchronized boolean remove(Context context, String id) {
        List<CustomStreams.Entry> entries = new ArrayList<>(list(context));
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(id)) { entries.remove(i); return save(context, entries); }
        }
        return true;
    }

    /** channels_get, channels_add and channels_remove for the authenticated companion. */
    static Bundle companion(Context context, String method, String arg) {
        Bundle out = new Bundle();
        if ("channels_get".equals(method)) {
            out.putString("channels64", Base64.encodeToString(
                    CustomStreams.write(list(context)).getBytes(StandardCharsets.UTF_8), ENCODING));
            return out;
        }
        if ("channels_remove".equals(method)) {
            out.putBoolean("handled", arg != null && arg.matches("custom-[0-9a-f]{12}") && remove(context, arg));
            return out;
        }
        boolean added = false;
        if ("channels_add".equals(method) && arg != null && arg.length() <= 160000) {
            try {
                added = addAll(context, CustomStreams.parse(new String(Base64.decode(arg, ENCODING), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException invalid) { added = false; }
        }
        out.putBoolean("handled", added);
        return out;
    }

    private CustomStreamStore() {}
}

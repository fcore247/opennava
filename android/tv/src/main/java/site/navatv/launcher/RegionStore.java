package site.navatv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Language and country of this television. The phone asks for both before it connects and
 * hands them over; until then the language of the TV itself decides.
 */
public final class RegionStore {
    private static final String METHOD = "locale_set";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("region", Context.MODE_PRIVATE);
    }

    public static String language(Context context) {
        String saved = prefs(context).getString("language", "");
        if (!saved.isEmpty()) return saved;
        return "de".equals(java.util.Locale.getDefault().getLanguage()) ? "de" : "en";
    }

    /** Empty while nobody has chosen a country. */
    public static String country(Context context) { return prefs(context).getString("country", ""); }

    public static boolean english(Context context) { return "en".equals(language(context)); }

    /** Makes the saved country part of this process: it decides which live channels exist. */
    public static void apply(Context context) {
        LiveGuide.setCountry(country(context));
        if ("site.navatv.rustpreview".equals(context.getPackageName())) writeForNativeScreen(context);
    }

    static boolean handles(String method) { return METHOD.equals(method); }

    static synchronized Bundle companion(Context context, String value) {
        if (value == null || !value.matches("(de|en)-(de|at|ch|us)"))
            throw new IllegalArgumentException("Unknown language or country");
        prefs(context).edit().putString("language", value.substring(0, 2)).putString("country", value.substring(3)).commit();
        apply(context);
        if ("site.navatv.rustpreview".equals(context.getPackageName())) presetLibraries(context);
        Bundle out = new Bundle();
        out.putBoolean("handled", true);
        return out;
    }

    /** The native screen reads this file, the same way it reads the viewer's libraries. */
    private static void writeForNativeScreen(Context context) {
        try {
            replace(new File(context.getFilesDir(), "locale.json"), new JSONObject()
                    .put("language", language(context)).put("country", country(context)).toString());
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void replace(File target, String content) {
        File temporary = new File(target.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temporary)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failed) { return; }
        if (!temporary.renameTo(target)) temporary.delete();
    }

    /** Adds the American libraries for the USA and takes only those away again elsewhere. */
    private static void presetLibraries(Context context) {
        boolean american = "us".equals(country(context));
        Set<String> added = new HashSet<>(prefs(context).getStringSet("presets", new HashSet<String>()));
        if (american == !added.isEmpty()) return;
        List<OwnLibrary.Source> sources = new ArrayList<>(OwnLibrary.parseSources(LibraryStore.saved(context)));
        if (american) {
            for (OwnLibrary.Source source : OwnLibrary.americanPresets()) {
                boolean present = false;
                for (OwnLibrary.Source existing : sources) present |= existing.id.equals(source.id);
                if (present || sources.size() >= OwnLibrary.LIBRARY_LIMIT) continue;
                sources.add(source);
                added.add(source.id);
            }
        } else {
            for (int i = sources.size() - 1; i >= 0; i--) if (added.contains(sources.get(i).id)) sources.remove(i);
            added.clear();
        }
        if (LibraryStore.store(context, sources)) prefs(context).edit().putStringSet("presets", added).commit();
    }

    private RegionStore() {}
}

package site.navatv.companion;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Language and country of the viewer, chosen before the phone connects to a TV. */
final class Region {
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("region", Context.MODE_PRIVATE);
    }

    static String language(Context context) {
        String saved = prefs(context).getString("language", "");
        if (!saved.isEmpty()) return saved;
        return "de".equals(Locale.getDefault().getLanguage()) ? "de" : "en";
    }

    static String country(Context context) {
        String saved = prefs(context).getString("country", "");
        if (!saved.isEmpty()) return saved;
        String device = Locale.getDefault().getCountry().toLowerCase(Locale.ROOT);
        return device.matches("de|at|ch|us") ? device : "de";
    }

    static boolean chosen(Context context) { return prefs(context).contains("country"); }

    /** What the TV is told, such as "en-us". */
    static String value(Context context) { return language(context) + "-" + country(context); }

    static void save(Context context, String language, String country) {
        prefs(context).edit().putString("language", language).putString("country", country).apply();
        apply(context);
    }

    static void apply(Context context) {
        L.german = "de".equals(language(context));
        // Someone who never chose keeps the line-up the app has always shown.
        site.navatv.launcher.PhoneGuide.setCountry(chosen(context) ? country(context) : "");
    }

    static String countryName(String code) {
        switch (code) {
            case "at": return L.t("Austria");
            case "ch": return L.t("Switzerland");
            case "us": return L.t("United States");
            default: return L.t("Germany");
        }
    }

    private Region() {}
}

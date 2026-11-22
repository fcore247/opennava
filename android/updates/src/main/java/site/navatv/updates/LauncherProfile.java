package site.navatv.updates;

import android.content.Context;
import android.content.SharedPreferences;

/** The explicit three-way choice lives in the classic launcher's existing profile preferences. */
public final class LauncherProfile {
    public static final String RUST = "rust", CLASSIC = "classic", SENIOR = "senior";
    private LauncherProfile() {}
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("home-destination", Context.MODE_PRIVATE);
    }
    public static boolean hasExplicitChoice(Context context) {
        return !prefs(context).getString("launcher", "").isEmpty();
    }

    static String resolve(String explicit, String previous, boolean preferredRust, boolean nativeWasHome) {
        if (!explicit.isEmpty()) return explicit;
        if (nativeWasHome) return RUST;
        if ("PERSONAL".equals(previous)) return SENIOR;
        if (preferredRust) return RUST;
        return "VISITOR".equals(previous) ? CLASSIC : "";
    }

    private static String resolve(Context context, boolean nativeWasHome) {
        SharedPreferences state = prefs(context);
        return resolve(state.getString("launcher", ""), state.getString("mode", ""),
                Updates.prefs(context).getBoolean("useRustForFullHome", false), nativeWasHome);
    }

    public static String get(Context context) {
        return resolve(context, false);
    }

    public static synchronized void adoptRust(Context context) {
        if (!hasExplicitChoice(context)) save(context, resolve(context, true));
    }

    public static synchronized boolean save(Context context, String value) {
        if (!RUST.equals(value) && !CLASSIC.equals(value) && !SENIOR.equals(value)) return false;
        return prefs(context).edit().putString("launcher", value)
                .putString("mode", SENIOR.equals(value) ? "PERSONAL" : "VISITOR").commit();
    }
}

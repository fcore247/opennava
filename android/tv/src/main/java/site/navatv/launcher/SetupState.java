package site.navatv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** How far the first-time setup has come. The paired phone reports its steps through the provider. */
public final class SetupState {
    private static final String METHOD = "setup_phone";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("setup", Context.MODE_PRIVATE);
    }

    public static boolean done(Context context) { return prefs(context).getBoolean("done", false); }

    /** Empty until a phone has connected; then "connected" or "tour-1" to "tour-9". */
    public static String phone(Context context) { return prefs(context).getString("phone", ""); }

    public static void finish(Context context) {
        prefs(context).edit().putBoolean("done", true).remove("phone").apply();
    }

    public static void restart(Context context) {
        prefs(context).edit().putBoolean("done", false).remove("phone").apply();
    }

    static boolean handles(String method) { return METHOD.equals(method); }

    static Bundle companion(Context context, String step) {
        if (step == null || !step.matches("status|connected|tour-[1-9]|done|restart"))
            throw new IllegalArgumentException("Unknown setup step");
        // Only a setup that is still running follows the phone.
        if ("restart".equals(step)) restart(context);
        else if ("done".equals(step)) finish(context);
        else if (!"status".equals(step) && !done(context)) prefs(context).edit().putString("phone", step).apply();
        Bundle out = new Bundle();
        out.putBoolean("handled", true);
        out.putBoolean("done", done(context));
        return out;
    }

    private SetupState() {}
}

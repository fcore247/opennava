package site.navatv.rustpreview;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

public final class FireHomeSettings {
    private FireHomeSettings() {}

    /** -1 when the optional Home service is unavailable, otherwise its saved toggle. */
    public static int state(Context context) {
        if (Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 0)
            return -1;
        String services = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null) return -1;
        ComponentName home = new ComponentName(context, FireHomeService.class);
        for (String entry : services.split(":")) {
            if (home.equals(ComponentName.unflattenFromString(entry)))
                return FireHomeService.isRedirectEnabled(context) ? 1 : 0;
        }
        return -1;
    }
}

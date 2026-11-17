package site.navatv.full;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** State shared by the Activity and its optional, explicitly enabled service. */
final class LauncherState {
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("nava-native", Context.MODE_PRIVATE);
    }

    static boolean isRequested(Context context) {
        // A manually migrated, already enabled Home service should work immediately on its first bind.
        return prefs(context).getBoolean("launcher_enabled", "site.navatv.launcher".equals(context.getPackageName()));
    }

    static boolean isBypassed(Context context) {
        String kind = prefs(context).getString("bypass_kind", "");
        if ("amazon".equals(kind)) return true;
        if (!"settings".equals(kind)) return false;
        long remaining = prefs(context).getLong("bypass_until", 0) - SystemClock.elapsedRealtime();
        // Also reject a deadline from a previous boot's elapsed-realtime epoch.
        return remaining > 0 && remaining <= 8000;
    }

    static void beginBypass(Context context) {
        prefs(context).edit().putString("bypass_kind", "amazon").remove("bypass_until").commit();
    }

    static void beginSettingsBypass(Context context) {
        prefs(context).edit().putString("bypass_kind", "settings")
                .putLong("bypass_until", SystemClock.elapsedRealtime() + 8000).commit();
    }

    static void clearSettingsBypass(Context context) {
        if ("settings".equals(prefs(context).getString("bypass_kind", ""))) clearBypass(context);
    }

    static void clearBypass(Context context) {
        prefs(context).edit().remove("bypass_kind").remove("bypass_until").commit();
    }

    static ComponentName service(Context context) {
        return new ComponentName(context, NavaHomeService.class);
    }

    static boolean isEnabled(Context context) {
        if (!isRequested(context)) return false;
        try {
            String existing = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1 || existing == null) return false;
            for (String item : existing.split(":")) {
                if (service(context).equals(ComponentName.unflattenFromString(item))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    static synchronized boolean setEnabled(Context context, boolean enabled) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w("OpenNava", "Launcher change refused: WRITE_SECURE_SETTINGS not granted");
            return false;
        }
        String before = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String beforeFlag = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED);
        boolean beforeRequested = isRequested(context);
        List<String> services = new ArrayList<>();
        if (before != null && !before.isEmpty()) {
            for (String item : before.split(":")) {
                if (!item.isEmpty() && !service(context).equals(ComponentName.unflattenFromString(item))) {
                    services.add(item);
                }
            }
        }
        if (enabled) services.add(service(context).flattenToString());
        String after = String.join(":", services);
        String afterFlag = enabled ? "1" : services.isEmpty() ? "0" : beforeFlag;
        try {
            // No writes occur before the permission check. Other services are preserved verbatim.
            if (!Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, after)
                    || !Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, afterFlag)
                    || !prefs(context).edit().putBoolean("launcher_enabled", enabled).commit())
                throw new IllegalStateException("Launcher settings write refused");
            Log.i("OpenNava", "Launcher requested=" + enabled + "; services=" + services.size());
            return true;
        } catch (RuntimeException failure) {
            // Restore both original secure values, including a null value, on partial failure.
            try {
                Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, before);
                Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, beforeFlag);
                prefs(context).edit().putBoolean("launcher_enabled", beforeRequested).commit();
            }
            catch (RuntimeException ignored) { }
            Log.w("OpenNava", "Launcher change failed; attempted restoration", failure);
            return false;
        }
    }
}

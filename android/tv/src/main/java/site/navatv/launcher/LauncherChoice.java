package site.navatv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;

import site.navatv.updates.LauncherProfile;

public final class LauncherChoice {
    static final String CLASSIC_APP = "site.navatv.launcher", RUST_APP = "site.navatv.rustpreview";
    private LauncherChoice() {}

    /** Only one Fire service should redirect a Home press, including when OpenNava lets Amazon handle it. */
    public static boolean rustHandlesFireHome(Context c) {
        if (c.getPackageManager().checkSignatures(c.getPackageName(), RUST_APP) != PackageManager.SIGNATURE_MATCH) return false;
        android.content.ContentResolver resolver = c.getContentResolver();
        if (android.provider.Settings.Secure.getInt(resolver, "accessibility_enabled", 0) == 0) return false;
        String services = android.provider.Settings.Secure.getString(resolver, "enabled_accessibility_services");
        if (services == null) return false;
        ComponentName target = new ComponentName(RUST_APP, RUST_APP + ".FireHomeService");
        for (String entry : services.split(":")) {
            if (target.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    private static boolean canonical(Context c) {
        return CLASSIC_APP.equals(c.getPackageName());
    }

    private static boolean hasProvider(Context c) {
        ProviderInfo provider = c.getPackageManager().resolveContentProvider(HomeLayoutStore.CHANGES.getAuthority(), 0);
        return provider != null && CLASSIC_APP.equals(provider.packageName)
                && c.getPackageManager().checkSignatures(c.getPackageName(), CLASSIC_APP) == PackageManager.SIGNATURE_MATCH;
    }

    private static Bundle request(Context c, String method, String value) {
        try {
            return c.getContentResolver().call(HomeLayoutStore.CHANGES, method, value, null);
        } catch (RuntimeException unavailable) {
            // An older classic app can own this authority without supporting launcher selection.
            return null;
        }
    }

    /** Called inside the Rust package only. Older releases had no shared launcher key. */
    public static boolean previouslyUsedRust(Context c) {
        return RUST_APP.equals(c.getPackageName()) && (LauncherProfile.RUST.equals(LauncherProfile.get(c))
                || (rustHandlesFireHome(c) && c.getSharedPreferences("fire-home", Context.MODE_PRIVATE)
                .getBoolean("redirect", true)));
    }

    private static boolean rustReportsPreviousHome(Context c) {
        String authority = RUST_APP + ".homepreference";
        PackageManager pm = c.getPackageManager();
        ProviderInfo provider = pm.resolveContentProvider(authority, 0);
        if (provider == null || !RUST_APP.equals(provider.packageName)
                || pm.checkSignatures(c.getPackageName(), RUST_APP) != PackageManager.SIGNATURE_MATCH) return false;
        try {
            Bundle result = c.getContentResolver().call(android.net.Uri.parse("content://" + authority),
                    "previous_home", null, null);
            return result != null && result.getBoolean("rust");
        } catch (RuntimeException unavailable) { return false; }
    }

    public static String selected(Context c) {
        if (canonical(c)) {
            if (!LauncherProfile.hasExplicitChoice(c) && rustReportsPreviousHome(c)) LauncherProfile.adoptRust(c);
            return LauncherProfile.get(c);
        }
        String method = previouslyUsedRust(c) ? "launcher_adopt_rust" : "launcher_get";
        Bundle reply = hasProvider(c) ? request(c, method, null) : null;
        return reply != null ? reply.getString("launcher", "") : LauncherProfile.get(c);
    }

    static boolean available(Context c, String mode) {
        if (LauncherProfile.RUST.equals(mode)) {
            return RUST_APP.equals(c.getPackageName()) || (c.getPackageManager().checkSignatures(c.getPackageName(), RUST_APP)
                    == PackageManager.SIGNATURE_MATCH && c.getPackageManager().resolveActivity(rustIntent(c), 0) != null);
        }
        return canonical(c) || (hasProvider(c) && request(c, "launcher_get", null) != null);
    }

    static boolean choose(Context c, String mode) {
        if (!available(c, mode)) return false;
        if (canonical(c)) return LauncherProfile.save(c, mode);
        if (hasProvider(c)) {
            Bundle r = request(c, "launcher_set", mode);
            if (r != null) return r.getBoolean("saved");
            if (!LauncherProfile.RUST.equals(mode)) return false;
        }
        return LauncherProfile.save(c, mode);
    }

    private static Intent rustIntent(Context c) {
        Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(RUST_APP, RUST_APP + ".HomeEntryActivity");
        if (c.getPackageManager().resolveActivity(intent, 0) == null) intent.setClassName(RUST_APP, "android.app.NativeActivity");
        return intent;
    }

    public static boolean openSelected(Context c) {
        String mode = selected(c);
        if (mode.isEmpty()) return false;
        Intent intent;
        if (LauncherProfile.RUST.equals(mode)) {
            if (!available(c, mode)) return false;
            intent = rustIntent(c);
        } else {
            intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(CLASSIC_APP, CLASSIC_APP + ".MainActivity"));
        }
        try {
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}

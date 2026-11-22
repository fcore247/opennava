package site.navatv.updates;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/** OpenNava's full-profile destination. Android continues to route Home to the classic package. */
public final class RustHome {
    static final String FULL_SETTINGS = "opennava.fullLauncherSettings";
    private static final String PREFERRED = "useRustForFullHome";
    private RustHome() {}

    static PackageInfo installed(Context context) throws Exception {
        try {
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo info = context.getPackageManager().getPackageInfo(ReleaseFeed.PREVIEW, flags);
            if (!ReleaseFeed.officialSigner(info))
                throw new SecurityException("The installed Rust app has a different signing key.");
            return info;
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        }
    }

    static Intent intent() {
        return new Intent(Intent.ACTION_MAIN).setClassName(ReleaseFeed.PREVIEW, "android.app.NativeActivity");
    }

    static boolean preferred(Context c) {
        return Updates.prefs(c).getBoolean(PREFERRED, false);
    }

    public static boolean useClassic(Context c) {
        return !preferred(c) || Updates.prefs(c).edit().remove(PREFERRED).commit();
    }

    static void choose(Context c) throws Exception {
        if (installed(c) == null) throw new IllegalStateException("Install Rust first.");
        if (!Updates.prefs(c).edit().putBoolean(PREFERRED, true).commit())
            throw new IllegalStateException("Your TV could not save the launcher selection.");
        try {
            start(c);
        } catch (RuntimeException e) {
            useClassic(c);
            throw e;
        }
    }

    /** Called only after the launcher's full/senior selector chose the full profile. */
    public static boolean launchIfPreferred(Context c) {
        if (!preferred(c)) return false;
        try {
            if (installed(c) != null) {
                start(c);
                return true;
            }
        } catch (Exception unavailable) {
            Log.w("OpenNava", "Rust home unavailable; opening the classic launcher.", unavailable);
        }
        useClassic(c);
        return false;
    }

    private static void start(Context c) {
        // Omit the one-off preview's fromClassic extra. Rust then sends OPEN_CLASSIC when
        // the person explicitly returns, including after classic's process has restarted.
        c.startActivity(intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
}

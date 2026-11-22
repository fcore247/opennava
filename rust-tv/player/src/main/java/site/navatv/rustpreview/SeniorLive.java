package site.navatv.rustpreview;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import site.navatv.launcher.LiveTvActivity;

public final class SeniorLive {
    private static final String CLASSIC = "site.navatv.launcher";

    private SeniorLive() {}

    public static void open(Activity caller) {
        if (caller.getPackageManager().checkSignatures(caller.getPackageName(), CLASSIC)
                == PackageManager.SIGNATURE_MATCH) {
            try {
                caller.startActivity(new Intent().setClassName(CLASSIC, LiveTvActivity.class.getName()));
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // Classic releases predating the shared entry use a private player.
            }
        }
        caller.startActivity(new Intent(caller, LiveTvActivity.class));
    }
}

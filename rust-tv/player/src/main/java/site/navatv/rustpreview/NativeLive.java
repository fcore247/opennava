package site.navatv.rustpreview;

import android.app.Activity;
import android.content.Intent;

import site.navatv.launcher.LiveTvActivity;

/** Opens live television; the native screen calls this over JNI. */
public final class NativeLive {
    private NativeLive() {}

    public static void open(Activity caller) {
        caller.startActivity(new Intent(caller, LiveTvActivity.class));
    }
}

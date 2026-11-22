package site.navatv.rustpreview;

import android.app.Activity;
import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import site.navatv.launcher.LauncherChoice;

/** Android Home and the Fire remote enter through the same saved launcher choice. */
public final class HomeEntryActivity extends Activity {
    private static final AtomicBoolean pendingHome = new AtomicBoolean();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        openHome(this);
        finish();
    }

    public static boolean consumeHomeRequest() {
        return pendingHome.getAndSet(false);
    }

    static void openHome(Context context) {
        String selected = LauncherChoice.selected(context);
        if (("classic".equals(selected) || "senior".equals(selected)) && LauncherChoice.openSelected(context)) {
            pendingHome.set(false);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN).setClass(context, NativeActivity.class)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingHome.set(true);
        try {
            context.startActivity(intent);
        } catch (RuntimeException error) {
            pendingHome.set(false);
            Log.w("OpenNavaHome", "Could not open the TV home", error);
        }
    }
}

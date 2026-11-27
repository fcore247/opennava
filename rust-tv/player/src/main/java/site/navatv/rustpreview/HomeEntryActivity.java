package site.navatv.rustpreview;

import android.app.Activity;
import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

import site.navatv.launcher.SetupState;

/** Android Home and the Fire remote both enter here. */
public final class HomeEntryActivity extends Activity {
    private static final AtomicBoolean pendingHome = new AtomicBoolean();
    private static final AtomicBoolean pendingNavaTube = new AtomicBoolean();

    public static boolean consumeNavaTubeRequest() {
        return pendingNavaTube.getAndSet(false);
    }

    /** Brings the native launcher forward on its video page, whatever home screen is selected. */
    static void openNavaTube(Context context) {
        pendingHome.set(false);
        pendingNavaTube.set(true);
        context.startActivity(new Intent(context, NativeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        openHome(this);
        finish();
    }

    /**
     * The native screen asks every time it comes to the front, because Android can open it directly,
     * for instance from the recent apps, without passing this entry.
     */
    public static void requireSetup(Activity screen) {
        if (SetupState.done(screen)) return;
        screen.startActivity(new Intent(screen, SetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    public static boolean consumeHomeRequest() {
        return pendingHome.getAndSet(false);
    }

    static void openHome(Context context) {
        site.navatv.launcher.RegionStore.apply(context);
        if (!SetupState.done(context)) {
            pendingHome.set(false);
            context.startActivity(new Intent(context, SetupActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
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

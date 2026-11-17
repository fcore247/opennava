package site.navatv.launcher;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import java.lang.ref.WeakReference;

/** Screen transitions and cold entry are process-wide. All calls run on the main thread. */
public class StartupSignals extends Application {
    private boolean pending = true;
    private boolean interactive;
    private boolean playerOpen;
    private WeakReference<MainActivity> main = new WeakReference<>(null);

    @Override public void onCreate() {
        super.onCreate();
        ProgrammeReminders.install(this);
        PowerManager power = getSystemService(PowerManager.class);
        interactive = power == null || power.isInteractive();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        // Only the system can send these actions. Keep registration for the process lifetime,
        // including while the home service is running without a MainActivity.
        registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    interactive = false;
                    return;
                }
                if (interactive || !Intent.ACTION_SCREEN_ON.equals(intent.getAction())) return;
                interactive = true;
                pending = !playerOpen && !VisitorMode.isVisitor(StartupSignals.this);
                MainActivity activity = main.get();
                if (pending && activity != null) activity.dispatchStartup();
            }
        }, filter);
    }

    MainActivity guidePracticeActivity() {
        MainActivity activity = main.get();
        return activity != null && activity.practisingHomeKey() ? activity : null;
    }
    void attach(MainActivity activity) { main = new WeakReference<>(activity); }
    void detach(MainActivity activity) { if (main.get() == activity) main.clear(); }
    boolean hasPending() { return pending; }
    boolean take() {
        if (!pending || !interactive || playerOpen) return false;
        pending = false;
        return true;
    }
    void clear() { pending = false; }
    void playerOpened() { playerOpen = true; clear(); }
    void playerClosed() { playerOpen = false; }
}

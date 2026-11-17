package site.navatv.updates;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Updates {
    static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final int JOB = 0x4e4156;
    static final long DAY = TimeUnit.DAYS.toMillis(1);
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("software-updates", Context.MODE_PRIVATE); }
    public static boolean enabled(Context c) { return prefs(c).getBoolean("automatic", true); }
    static long current(Context c) throws Exception {
        return ReleaseFeed.code(c.getPackageManager().getPackageInfo(c.getPackageName(), 0));
    }

    static void schedule(Context c) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        if (!enabled(c)) { jobs.cancel(JOB); return; }
        if (jobs.getPendingJob(JOB) != null) return;
        jobs.schedule(new JobInfo.Builder(JOB, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresDeviceIdle(true)
                .setPeriodic(DAY).setPersisted(true).build());
    }

    public static void home(Activity activity) {
        home(activity, () -> open(activity), null);
    }

    public static void home(Activity activity, Runnable openUpdates, Runnable returnHome) {
        Context c = activity.getApplicationContext();
        schedule(c);
        SharedPreferences prefs = prefs(c);
        if (!enabled(c) || (System.currentTimeMillis() - prefs.getLong("lastForeground", 0) < DAY)) return;
        prefs.edit().putLong("lastForeground", System.currentTimeMillis()).apply();
        WORK.execute(() -> {
            try {
                ReleaseFeed release = ReleaseFeed.latest(c.getPackageName());
                if (release.versionCode <= current(c)) return;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()) return;
                    AlertDialog.Builder prompt = new AlertDialog.Builder(activity).setTitle("OpenNava " + release.version + " is available")
                            .setMessage("Open software updates to install this release or manage automatic updates.")
                            .setNegativeButton("Later", null)
                            .setPositiveButton("View update", (d, w) -> openUpdates.run());
                    if (returnHome != null) prompt.setNeutralButton("Home", (d, w) -> returnHome.run());
                    prompt.show();
                });
            } catch (Exception ignored) { /* The scheduled worker and manual check can retry. */ }
        });
    }

    public static void open(Context c) { c.startActivity(new Intent(c, UpdateActivity.class)); }

    static synchronized void install(Context c, ReleaseFeed release, boolean interactive) throws Exception {
        if (release.versionCode <= current(c)) return;
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        // Recover a session interrupted during transfer; do not create a second active install.
        for (PackageInstaller.SessionInfo existing : installer.getMySessions()) {
            if (!c.getPackageName().equals(existing.getAppPackageName())) continue;
            if (existing.isActive() || (Build.VERSION.SDK_INT >= 31 && existing.isCommitted())) return;
            installer.abandonSession(existing.getSessionId());
        }
        File apk = release.download(c);
        int id = -1;
        boolean committed = false;
        try {
            if (Thread.currentThread().isInterrupted() || (!interactive && !enabled(c)) || release.versionCode <= current(c)) return;
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (FileInputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent result = new Intent(c, InstallResult.class).setAction("site.navatv.INSTALL_RESULT")
                        .putExtra("interactive", interactive).putExtra("releaseCode", release.versionCode);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                session.commit(PendingIntent.getBroadcast(c, id, result, flags).getIntentSender());
                committed = true;
            }
        } finally {
            if (!committed && id >= 0) installer.abandonSession(id);
            apk.delete();
        }
    }
}

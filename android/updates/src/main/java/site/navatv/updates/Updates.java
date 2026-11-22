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
import android.view.View;
import android.view.ViewTreeObserver;
import java.io.IOException;
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
    static final String STARTUP_CODE = "opennava.updateOnStartup";
    private static final String KNOWN_CODE = "availableVersionCode";
    private static final String KNOWN_RELEASE = "availableRelease";
    private static boolean homeStarted;
    private static long installedCode;

    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("software-updates", Context.MODE_PRIVATE); }
    public static boolean enabled(Context c) { return prefs(c).getBoolean("automatic", true); }
    static long current(Context c) throws Exception {
        return current(c, c.getPackageName());
    }
    static long current(Context c, String pkg) throws Exception {
        try { return ReleaseFeed.code(c.getPackageManager().getPackageInfo(pkg, 0)); }
        catch (android.content.pm.PackageManager.NameNotFoundException missing) { return 0; }
    }

    private static synchronized long installed(Context c) {
        if (installedCode == 0) {
            try { installedCode = current(c); }
            catch (Exception ignored) { return 0; }
        }
        return installedCode;
    }

    public static boolean available(Context c) {
        long known = prefs(c).getLong(KNOWN_CODE, 0);
        if (known == 0) return false;
        long current = installed(c);
        return current > 0 && known > current;
    }

    static ReleaseFeed cached(Context c) {
        if (!available(c)) return null;
        try { return ReleaseFeed.parse(prefs(c).getString(KNOWN_RELEASE, ""), c.getPackageName()); }
        catch (Exception invalid) { return null; }
    }

    /** Only the updater's serial worker writes release metadata; readers can use it offline. */
    static ReleaseFeed check(Context c) throws Exception {
        ReleaseFeed release = ReleaseFeed.latest(c.getPackageName());
        boolean newer = release.versionCode > current(c);
        SharedPreferences.Editor edit = prefs(c).edit().putLong("lastCheck", System.currentTimeMillis());
        if (newer) edit.putLong(KNOWN_CODE, release.versionCode).putString(KNOWN_RELEASE, release.manifest);
        else edit.remove(KNOWN_CODE).remove(KNOWN_RELEASE);
        edit.apply();
        ReleaseFeed.discardOtherFiles(c, newer ? release : null);
        return newer ? release : null;
    }

    private static boolean recent(long since, long interval) {
        long elapsed = System.currentTimeMillis() - since;
        return since > 0 && elapsed >= 0 && elapsed < interval;
    }

    static void checkIfDue(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        if (!enabled(c) || recent(p.getLong("lastCheck", 0), DAY)
                || recent(p.getLong("lastAttempt", 0), TimeUnit.HOURS.toMillis(1))) return;
        p.edit().putLong("lastAttempt", System.currentTimeMillis()).apply();
        check(c);
    }

    static void schedule(Context c) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        if (!enabled(c)) { jobs.cancel(JOB); return; }
        boolean phone = ReleaseFeed.PHONE.equals(c.getPackageName());
        JobInfo existing = jobs.getPendingJob(JOB);
        if (existing != null && existing.isRequireDeviceIdle() == phone) return;
        jobs.schedule(new JobInfo.Builder(JOB, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresDeviceIdle(phone)
                .setPeriodic(DAY).setPersisted(true).build());
    }

    public static void home(Activity activity) {
        home(activity, () -> open(activity), null);
    }

    public static void home(Activity activity, Runnable openUpdates, Runnable returnHome) {
        Context c = activity.getApplicationContext();
        schedule(c);
        if (!ReleaseFeed.PHONE.equals(c.getPackageName())) {
            startTvHome(activity);
            return;
        }
        SharedPreferences prefs = prefs(c);
        if (!enabled(c) || (System.currentTimeMillis() - prefs.getLong("lastForeground", 0) < DAY)) return;
        prefs.edit().putLong("lastForeground", System.currentTimeMillis()).apply();
        WORK.execute(() -> {
            try {
                ReleaseFeed release = check(c);
                if (release == null) return;
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

    private static void startTvHome(Activity activity) {
        // Called for a genuine Home startup on the UI thread. Returning from a player cannot
        // consume a release that the daily worker discovered during this process's Home session.
        if (homeStarted) return;
        homeStarted = true;
        Context c = activity.getApplicationContext();
        if (!enabled(c)) return;
        ReleaseFeed prior = cached(c);
        if (prior != null) {
            showOnFirstFocus(activity, prior.versionCode);
            // A saved installer can be used offline immediately. The daily job still checks for newer releases.
            if (prior.downloaded(c)) return;
        }
        WORK.execute(() -> {
            try { checkIfDue(c); }
            catch (Exception ignored) { /* A local release stays available during a network outage. */ }
        });
    }

    private static void showOnFirstFocus(Activity activity, long versionCode) {
        View window = activity.getWindow().getDecorView();
        Runnable open = () -> {
            SharedPreferences p = prefs(activity);
            if (activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()
                    || !enabled(activity) || !available(activity) || p.getLong(KNOWN_CODE, 0) != versionCode
                    || (p.getLong("startupAttemptCode", 0) == versionCode && recent(p.getLong("startupAttemptAt", 0), DAY))) return;
            deferAutomatic(activity, versionCode);
            activity.startActivity(new Intent(activity, UpdateActivity.class).putExtra(STARTUP_CODE, versionCode)
                    .putExtra(RustHome.FULL_SETTINGS, "site.navatv.full.MainActivity".equals(activity.getClass().getName())));
        };
        if (activity.hasWindowFocus()) { window.post(open); return; }
        window.getViewTreeObserver().addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener() {
            @Override public void onWindowFocusChanged(boolean focused) {
                if (!focused) return;
                window.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                open.run();
            }
        });
    }

    static void deferAutomatic(Context c, long code) {
        if (code > 0) prefs(c).edit().putLong("startupAttemptCode", code)
                .putLong("startupAttemptAt", System.currentTimeMillis()).apply();
    }

    public static void openFullSettings(Context c) {
        c.startActivity(new Intent(c, UpdateActivity.class).putExtra(RustHome.FULL_SETTINGS, true));
    }

    public static void open(Context c) { c.startActivity(new Intent(c, UpdateActivity.class)); }

    static synchronized void install(Context c, ReleaseFeed release, boolean interactive) throws Exception {
        boolean preview = ReleaseFeed.TV.equals(c.getPackageName()) && ReleaseFeed.PREVIEW.equals(release.packageName);
        if (!release.packageName.equals(c.getPackageName()) &&
                !(preview && interactive && ReleaseFeed.TV.equals(c.getPackageName())))
            throw new IllegalArgumentException("Invalid installation target.");
        if (release.versionCode <= current(c, release.packageName)) return;
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        // Recover a session interrupted during transfer; do not create a second active install.
        for (PackageInstaller.SessionInfo existing : installer.getMySessions()) {
            if (!release.packageName.equals(existing.getAppPackageName())) continue;
            if (existing.isActive() || (Build.VERSION.SDK_INT >= 31 && existing.isCommitted())) return;
            installer.abandonSession(existing.getSessionId());
        }
        boolean ownTv = !preview && !ReleaseFeed.PHONE.equals(c.getPackageName());
        File apk = ownTv ? release.prepare(c) : release.download(c);
        int id = -1;
        boolean committed = false;
        try {
            if (Thread.currentThread().isInterrupted() || (!interactive && !enabled(c)) || release.versionCode <= current(c, release.packageName)) return;
            if (ownTv && (!interactive || !UpdateActivity.allowsInstallation(c)))
                throw new IOException("Open software updates to continue installation.");
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(release.packageName);
            params.setSize(apk.length());
            if (Build.VERSION.SDK_INT >= 31)
                params.setRequireUserAction(preview ? PackageInstaller.SessionParams.USER_ACTION_REQUIRED :
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (FileInputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    session.fsync(out);
                }
                if (ownTv && !UpdateActivity.allowsInstallation(c))
                    throw new IOException("Return to software updates to continue installation.");
                Intent result = new Intent(c, InstallResult.class).setAction("site.navatv.INSTALL_RESULT")
                        .putExtra("preview", preview).putExtra("interactive", interactive).putExtra("releaseCode", release.versionCode);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                session.commit(PendingIntent.getBroadcast(c, id, result, flags).getIntentSender());
                committed = true;
            }
        } finally {
            if (!committed && id >= 0) installer.abandonSession(id);
            if (!ownTv) apk.delete();
        }
    }
}

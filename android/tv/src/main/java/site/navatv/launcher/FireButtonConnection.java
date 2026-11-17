package site.navatv.launcher;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/** An optional, separately permissioned local helper identifies Fire OS's reserved remote buttons. */
final class FireButtonConnection {
    private static final String HELPER = "site.navatv.keys";
    private static final String PERMISSION = "site.navatv.launcher.permission.REMOTE_KEYS";
    private static final String ACTION = "site.navatv.launcher.FIRE_KEY";
    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean bound, closed;
    private long firstHome;
    private final Runnable reopen = this::bind;
    private final Runnable programme = this::openProgramme;
    private final Runnable personalHome = this::openPersonal;
    private void openProgramme() { HomeProgrammeShortcut.open(service); }

    FireButtonConnection(AccessibilityService service) {
        this.service = service;
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(receiver, filter, PERMISSION, main, Context.RECEIVER_EXPORTED);
        else service.registerReceiver(receiver, filter, PERMISSION, main);
        bind();
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            HomeProgrammeShortcut.preciseFire = permitted();
        }
        @Override public void onServiceDisconnected(ComponentName name) { HomeProgrammeShortcut.preciseFire = false; }
        @Override public void onBindingDied(ComponentName name) { rebind(); }
        @Override public void onNullBinding(ComponentName name) { rebind(); }
    };
    private boolean permitted() {
        PackageManager pm = service.getPackageManager();
        return pm.checkSignatures(service.getPackageName(), HELPER) == PackageManager.SIGNATURE_MATCH
                && pm.checkPermission(Manifest.permission.READ_LOGS, HELPER) == PackageManager.PERMISSION_GRANTED;
    }
    private void bind() {
        if (closed || bound) return;
        if (permitted()) {
            Intent helper = new Intent().setClassName(HELPER, HELPER + ".KeySignalService");
            try { bound = service.bindService(helper, connection, Context.BIND_AUTO_CREATE); }
            catch (SecurityException ignored) { bound = false; }
        }
        if (!bound) main.postDelayed(reopen, 15000);
    }
    private void rebind() {
        if (closed) return;
        HomeProgrammeShortcut.preciseFire = false;
        if (bound) service.unbindService(connection);
        bound = false;
        main.removeCallbacks(reopen);
        main.postDelayed(reopen, 1000);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION.equals(intent.getAction()) || !permitted()) return;
            long time = intent.getLongExtra("elapsedMs", -1);
            long age = SystemClock.elapsedRealtime() - time;
            if (time < 0 || age < -100 || age > 1800) return;
            String kind = intent.getStringExtra("kind");
            if ("netflix".equals(kind)) {
                firstHome = 0;
                main.removeCallbacks(programme);
                main.removeCallbacks(personalHome);
                main.postDelayed(personalHome, 150);
            } else if ("home".equals(kind)) {
                if (VisitorMode.isVisitor(service) || !site.navatv.full.NavaHomeService.redirectAllowed(service)) { firstHome = 0; return; }
                if (firstHome != 0 && time >= firstHome && time - firstHome <= 650) {
                    firstHome = 0;
                    HomeProgrammeShortcut.markOpening();
                    main.removeCallbacks(programme);
                    main.postDelayed(programme, 160);
                } else {
                    firstHome = time;
                    HomeProgrammeShortcut.clearSuppression();
                }
            }
        }
    };

    private void openPersonal() {
        if (!VisitorMode.selectPersonal(service)) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(service, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        service.startActivity(home);
    }
    void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (bound) service.unbindService(connection);
        service.unregisterReceiver(receiver);
        HomeProgrammeShortcut.preciseFire = false;
    }
}

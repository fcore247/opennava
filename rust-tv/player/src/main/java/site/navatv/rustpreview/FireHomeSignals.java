package site.navatv.rustpreview;

import android.Manifest;
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

/** Optional local connection to the separately permissioned, same-signer Fire Keys app. */
final class FireHomeSignals implements ServiceConnection {
    private static final String HELPER = "site.navatv.keys";
    private static final String PERMISSION = HELPER + ".permission.RUST_HOME";
    private static final String ACTION = "site.navatv.launcher.FIRE_KEY";

    private final FireHomeService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::bind;
    private boolean bound, connected;
    private long lastStamp = -1;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION.equals(intent.getAction()) || !"home".equals(intent.getStringExtra("kind")) || !trusted()) return;
            long wall = intent.getLongExtra("eventTimeMs", -1);
            long at = intent.getLongExtra("elapsedMs", -1);
            long age = SystemClock.elapsedRealtime() - at;
            if (wall <= 0 || wall == lastStamp || at < 0 || age < -100 || age > 1800) return;
            lastStamp = wall;
            service.onPhysicalHome(at);
        }
    };

    FireHomeSignals(FireHomeService service) {
        this.service = service;
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(receiver, filter, PERMISSION, main, Context.RECEIVER_EXPORTED);
        else service.registerReceiver(receiver, filter, PERMISSION, main);
        bind();
    }

    boolean active() { return connected; }

    private boolean trusted() {
        PackageManager pm = service.getPackageManager();
        return pm.checkSignatures(service.getPackageName(), HELPER) == PackageManager.SIGNATURE_MATCH
                && pm.checkPermission(Manifest.permission.READ_LOGS, HELPER) == PackageManager.PERMISSION_GRANTED;
    }

    private void bind() {
        if (!bound && trusted()) {
            try {
                bound = service.bindService(new Intent().setClassName(HELPER, HELPER + ".RustHomeSignalService"),
                        this, Context.BIND_AUTO_CREATE);
            } catch (SecurityException unavailable) { bound = false; }
        }
        if (!bound) main.postDelayed(retry, 15000);
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) { connected = true; }
    @Override public void onServiceDisconnected(ComponentName name) { connected = false; }
    @Override public void onBindingDied(ComponentName name) { reconnect(); }
    @Override public void onNullBinding(ComponentName name) { reconnect(); }

    private void reconnect() {
        unbind();
        main.removeCallbacks(retry);
        main.postDelayed(retry, 1000);
    }

    private void unbind() {
        connected = false;
        if (bound) service.unbindService(this);
        bound = false;
    }

    void close() {
        main.removeCallbacks(retry);
        service.unregisterReceiver(receiver);
        unbind();
    }
}

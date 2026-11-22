package site.navatv.keys;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Runs only while a launcher signed with the same key is bound. */
public class KeySignalService extends Service {
    private static final String RECEIVER = "site.navatv.launcher";
    private static final String PERMISSION = RECEIVER + ".permission.REMOTE_KEYS";
    protected boolean homeOnly() { return false; }

    private final IBinder binder = new Binder();
    private LogReader reader;

    @Override public IBinder onBind(Intent intent) {
        if (reader == null) {
            reader = new LogReader();
            reader.start();
        }
        return binder;
    }

    @Override public boolean onUnbind(Intent intent) {
        stopReader();
        return false;
    }

    @Override public void onDestroy() {
        stopReader();
        super.onDestroy();
    }

    private void stopReader() {
        if (reader != null) {
            reader.shutdown();
            reader = null;
        }
    }

    private final class LogReader extends Thread {
        private volatile boolean closed;
        private Process process;

        LogReader() { super("FireKeyMonitor"); }

        synchronized void shutdown() {
            closed = true;
            if (process != null) process.destroy();
            interrupt();
        }

        @Override public void run() {
            while (!closed) {
                if (checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
                    try { readLive(); } catch (IOException unavailable) { /* Retry after logd restarts. */ }
                }
                if (closed) return;
                try { Thread.sleep(1500); } catch (InterruptedException stopped) { return; }
            }
        }

        private void readLive() throws IOException {
            long startMs = System.currentTimeMillis();
            String start = String.format(Locale.ROOT, "%d.%03d", startMs / 1000, startMs % 1000);
            Process source;
            synchronized (this) {
                if (closed) return;
                source = new ProcessBuilder("/system/bin/logcat", "-b", "system",
                        "-v", "threadtime", "-v", "epoch", "-v", "uid", "-T", start, "ActivityTaskManager:I",
                        homeOnly() ? "ContextImpl:S" : "ContextImpl:W", "*:S")
                        .redirectErrorStream(true).start();
                process = source;
            }
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(source.getInputStream(),
                    StandardCharsets.UTF_8))) {
                FireKeyEvent.Monitor monitor = new FireKeyEvent.Monitor(startMs);
                String line;
                while (!closed && (line = lines.readLine()) != null) {
                    FireKeyEvent key = monitor.parse(line);
                    if (key == null || key.wallMs < startMs || (homeOnly() && !"home".equals(key.kind))) continue;
                    long latency = System.currentTimeMillis() - key.wallMs;
                    if (latency < -100 || latency > 2000) continue;
                    Intent event = new Intent(RECEIVER + ".FIRE_KEY")
                            .setPackage(homeOnly() ? "site.navatv.rustpreview" : RECEIVER)
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND)
                            .putExtra("kind", key.kind).putExtra("eventTimeMs", key.wallMs)
                            .putExtra("elapsedMs", SystemClock.elapsedRealtime() - Math.max(0, latency));
                    synchronized (this) {
                        if (!closed) sendBroadcast(event, homeOnly() ? "site.navatv.keys.permission.RUST_HOME" : PERMISSION);
                    }
                }
            } finally {
                synchronized (this) { process = null; }
                source.destroy();
            }
        }
    }
}

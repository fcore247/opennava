package site.navatv.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RecordingService extends Service {
    private static final String NOTICES = "fernsehaufnahmen";
    private final Set<String> running = new HashSet<>();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;
    private boolean destroyed;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(
                        NOTICES, "TV recordings", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, RecordingsActivity.class);
        Notification notice =
                new Notification.Builder(this, NOTICES)
                        .setSmallIcon(android.R.drawable.presence_video_online)
                        .setContentTitle("TV recording")
                        .setContentText("Find your saved programmes under Recordings.")
                        .setContentIntent(
                                PendingIntent.getActivity(
                                        this,
                                        497,
                                        open,
                                        PendingIntent.FLAG_IMMUTABLE
                                                | PendingIntent.FLAG_UPDATE_CURRENT))
                        .setOngoing(true)
                        .build();
        startForeground(3841, notice);
        if (wake == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":Aufnahme");
            wake.setReferenceCounted(false);
        }
        wake.acquire(6L * 3600000 + 60000);
        for (JSONObject row : Recordings.list(this)) {
            String id = row.optString("id");
            if (Recordings.active(row) && running.add(id)) workers.execute(() -> record(id));
        }
        if (running.isEmpty()) stopSelf(startId);
        return START_STICKY;
    }

    private void record(String id) {
        File dir = Recordings.directory(this, id);
        boolean finished = false;
        String reason = "";
        try {
            JSONObject row = Recordings.get(this, id);
            if (row == null) return;
            boolean archived = false;
            if (!row.optBoolean("stop"))
                try {
                    archived = Recordings.archive(this, row);
                } catch (Exception unavailable) {
                    /* Capture the broadcast. */
                }
            if (archived) return;
            long until = row.getLong("until");
            if (until > System.currentTimeMillis() && !row.optBoolean("stop")) {
                Recordings.status(this, id, Recordings.CAPTURE, "");
                RecordingHls recorder = new RecordingHls(dir, row.getLong("start"));
                String url = row.optString("live");
                try {
                    recorder.prepare(url);
                } catch (Exception oldUrl) {
                    LiveGuide.Channel ch = Recordings.channel(row);
                    if (ch == null) throw oldUrl;
                    recorder = new RecordingHls(dir, row.getLong("start"));
                    recorder.prepare(LiveGuide.stream(ch));
                }
                finished =
                        recorder.capture(
                                until,
                                () -> {
                                    if (Thread.currentThread().isInterrupted()) return true;
                                    JSONObject state = Recordings.get(this, id);
                                    return state == null || state.optBoolean("stop");
                                });
            } else {
                reason = "The TV interrupted the recording.";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return; // START_STICKY restores outstanding entries and their media sequences.
        } catch (Exception error) {
            android.util.Log.w(
                    "TV recordings",
                    "Local recording: "
                            + error.getClass().getSimpleName()
                            + ": "
                            + error.getMessage());
            String message = error.getMessage();
            reason =
                    message != null
                                    && (message.startsWith("Storage")
                                            || message.startsWith("Protected programme"))
                            ? message
                            : "The broadcaster or storage was temporarily unavailable.";
        } finally {
            if (!Thread.currentThread().isInterrupted()) {
                JSONObject current = Recordings.get(this, id);
                if (Recordings.active(current)) {
                    boolean playable = RecordingHls.playable(dir);
                    if (!playable) Recordings.removeFiles(dir);
                    String state =
                            playable
                                    ? (finished ? Recordings.LOCAL : Recordings.PART)
                                    : Recordings.FAILED;
                    if (!playable && reason.isEmpty())
                        reason = "Could not save a video segment.";
                    Recordings.status(this, id, state, reason);
                    Recordings.schedule(this);
                }
            }
            main.post(
                    () -> {
                        if (destroyed) return;
                        running.remove(id);
                        if (running.isEmpty()) stopSelf();
                    });
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        workers.shutdownNow();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

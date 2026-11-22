package site.navatv.updates;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.Future;

public final class UpdateJob extends JobService {
    private Future<?> task;

    @Override public boolean onStartJob(JobParameters params) {
        task = Updates.WORK.submit(() -> {
            boolean retry = false;
            try {
                if (Updates.enabled(this)) {
                    ReleaseFeed release = Updates.check(this);
                    if (ReleaseFeed.PHONE.equals(getPackageName()) && release != null && getPackageManager().canRequestPackageInstalls()
                            && Updates.prefs(this).getLong("needsApproval", 0) != release.versionCode)
                        Updates.install(this, release, false);
                }
            } catch (Exception failure) { retry = true; }
            if (!Thread.currentThread().isInterrupted()) jobFinished(params, retry);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (task != null) task.cancel(true);
        return true;
    }
}

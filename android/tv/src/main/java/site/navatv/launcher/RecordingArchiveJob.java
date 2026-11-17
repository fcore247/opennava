package site.navatv.launcher;

import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;

public final class RecordingArchiveJob extends JobService {
    private Thread runner;

    @Override
    public boolean onStartJob(JobParameters params) {
        runner =
                new Thread(
                        () -> {
                            Recordings.refresh(this, 2 * 3600000L);
                            if (!Thread.currentThread().isInterrupted()) {
                                jobFinished(params, false);
                                if (!Recordings.hasLocalWork(this))
                                    getSystemService(JobScheduler.class)
                                            .cancel(Recordings.ARCHIVE_JOB);
                            }
                        },
                        "Checking the archive");
        runner.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (runner != null) runner.interrupt();
        return true;
    }
}

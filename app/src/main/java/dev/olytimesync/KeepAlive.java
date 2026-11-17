package dev.olytimesync;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * A periodic check, kept across reboots by JobScheduler: if the phone killed the watcher, start it again. With the
 * battery exemption Android allows the restart from the background.
 */
public class KeepAlive extends JobService {
    private static final int JOB_ID = 1;
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        // Rescheduling would restart the period (and cancel a running check), so only add it when missing.
        for (JobInfo j : js.getAllPendingJobs()) {
            if (j.getId() == JOB_ID) return;
        }
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, KeepAlive.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    static void cancel(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (Prefs.auto(this)) Watcher.startService(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}

package io.github.lostininaka.weatherglance;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * The reading's own clock, so the line under the clock is warm before Home asks for it.
 *
 * <p>Home queries the provider only while it is on screen. An add-on that refreshed purely on that query was
 * therefore only ever as fresh as the last time Home was drawn: a set left inside YouTube for eighteen hours
 * came back to a reading eighteen hours old, and the launcher hid the line rather than print yesterday's sky.
 * This job refreshes on its own schedule whatever is on screen, and asks to be run again when the system
 * stops it, so a fetch that fails is retried instead of waiting for the next Home draw.
 *
 * <p>Not persisted across reboots on purpose: Home is the launcher, so it is drawn again after every boot,
 * and that first read calls {@link Refresh#schedule} again. Persisting would buy nothing and cost the app a
 * {@code RECEIVE_BOOT_COMPLETED} permission it does not otherwise need.
 */
public final class RefreshJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        // A job starts on the main thread and this one does network: do the work off it, then report.
        Thread worker = new Thread(() -> {
            try {
                Refresh.blocking(getApplicationContext());
            } finally {
                jobFinished(params, false);
            }
        }, "weather-job");
        // Not a daemon: a job that is still fetching must keep the process alive until it can say it is done.
        worker.setDaemon(false);
        try {
            worker.start();
        } catch (RuntimeException noThread) {
            return false;  // nothing running, so nothing to report later; the next cycle tries again
        }
        return true;  // the fetch continues on that thread, and jobFinished will be called
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Stopped mid-fetch (doze, app standby, the slot given to a more urgent job): ask for another run.
        return true;
    }
}

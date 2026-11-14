package io.github.lostininaka.weatherglance;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import java.util.concurrent.Executor;

/**
 * Fetches the reading the launcher publishes and tells it to look again.
 *
 * <p>Two things ask for a fetch: the owner, by opening this app or pressing Refresh; and the job this class
 * schedules, which runs on the reading's own clock whether or not anything is watching. The second one is not
 * a luxury. Home reads the provider only while it is on screen, so an add-on that refreshed purely on a read
 * went stale for as long as another app owned the panel - a set left in YouTube overnight came back to a
 * reading eighteen hours old, and the launcher hid the line rather than print yesterday's weather.
 */
final class Refresh {
    /** How often the reading is refreshed without being asked. JobScheduler's floor is 15 minutes. */
    static final long INTERVAL_MS = 20 * 60_000L;
    /**
     * How much slack the system gets to pick its moment in.
     *
     * <p>Leaving flex out of {@code setPeriodic} makes it equal the interval, and the system then spends the
     * whole window: a 20-minute job measured on the TV first ran at 29m49s, which made "every 20 minutes" a
     * claim the set did not keep. Five minutes of flex puts the run between roughly 18 and 23, close enough
     * that the sentence and the behaviour agree.
     */
    static final long FLEX_MS = 5 * 60_000L;
    /** Our one job id: rescheduling with it replaces the pending job rather than stacking another. */
    private static final int JOB = 0x77EA;
    private static final String TAG = "WeatherGlance";

    /** The app's one fetch; its slot keeps the launcher's reads from stacking up behind each other. */
    private static final Fetch FETCH = new Fetch(OpenMeteo::current, Refresh::onWorkerThread);

    private Refresh() {}

    /** Fetch if the stored reading is older than {@link Store#STALE_MS}. */
    static void ifStale(Context ctx) {
        if (new Store(ctx).stale()) now(ctx);
    }

    /** Fetch now, whatever the stored reading's age. Safe to call from any thread. */
    static void now(Context ctx) {
        FETCH.request(new Stored(ctx.getApplicationContext()));
    }

    /**
     * Give the reading a clock of its own. Idempotent and cheap enough to call on every process start: a job
     * already pending is left alone, so the launcher's reads do not churn the scheduler. Called from the
     * provider's {@code onCreate} and from the app's own screen - either one is enough, since Home always
     * reads the provider before it can draw a line.
     */
    static void schedule(Context ctx) {
        Context app = ctx.getApplicationContext();
        JobScheduler jobs = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs == null || jobs.getPendingJob(JOB) != null) return;
        JobInfo job = new JobInfo.Builder(JOB, new ComponentName(app, RefreshJob.class))
                .setPeriodic(INTERVAL_MS, FLEX_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        try {
            jobs.schedule(job);
        } catch (RuntimeException refused) {
            // A scheduler that will not have us is not fatal: the read-triggered fetch still works, and this
            // is called again on the next process start. Say so rather than fail silently.
            Log.w(TAG, "no clock of its own: only reads will refresh the reading", refused);
        }
    }

    /**
     * One fetch on the calling thread, for the job, which has to know when the work is over.
     *
     * <p>Every outcome is said out loud in logcat, tagged {@code WeatherGlance}: a reading that never moved
     * looked exactly like a renderer that dropped the line, and a day went into the wrong file because the
     * only party who knew the truth kept it to itself. {@code adb logcat -d | grep WeatherGlance} now answers
     * "did the clock run, and what came of it" without anyone having to sit and watch a panel.
     */
    static void blocking(Context ctx) {
        Context app = ctx.getApplicationContext();
        Store store = new Store(app);
        if (!store.hasPlace()) return;
        if (!store.stale()) {
            Log.i(TAG, "the job found the reading fresh (" + minutes(store) + " min old); nothing to fetch");
            return;
        }
        Log.i(TAG, "the job is fetching: the reading is " + minutes(store) + " min old");
        if (!FETCH.runHere(new Stored(app))) {
            Log.i(TAG, "a fetch was already in the air; the job left the city to that one");
            return;
        }
        // A fresh Store, not the one above: the question is what the provider serves now, not what we knew.
        Log.i(TAG, "the job is done: the reading is now " + minutes(new Store(app)) + " min old");
    }

    /** The stored reading's age in whole minutes, or -1 when nothing is stored at all. */
    private static long minutes(Store store) {
        if (!store.hasReading()) return -1;
        return Math.max(0, (System.currentTimeMillis() - store.updated()) / 60_000L);
    }

    private static void onWorkerThread(Runnable body) {
        Thread worker = new Thread(body, "weather-fetch");
        worker.setDaemon(true);
        worker.start();
    }

    /** The app behind {@link Fetch}: the store the provider reads, and the change notice it waits for. */
    private static final class Stored implements Fetch.Cities {
        private final Context ctx;
        private final Store store;

        Stored(Context ctx) {
            this.ctx = ctx;
            this.store = new Store(ctx);
        }

        @Override public Selections.Pick pick() {
            return store.pick();
        }

        @Override public boolean stillCurrent(Selections.Pick pick) {
            return store.stillCurrent(pick);
        }

        @Override public boolean save(Selections.Pick forPick, OpenMeteo.Now reading, long updatedMs) {
            return store.saveReading(forPick, reading.celsius, reading.code, reading.day, updatedMs, reading.humidity,
                    reading.precipChance, reading.snowChance);
        }

        @Override public void notifyReaders() {
            ctx.getContentResolver().notifyChange(WeatherProvider.NOW, null);
        }

        @Override public void failed(Exception why) {
            // The last good reading stands, but a failure nobody can see is indistinguishable from a line the
            // launcher decided not to draw - which is how a stale reading spent a day looking like a bug.
            Log.w(TAG, "no new reading: the last one stands, and the next run tries again", why);
        }
    }
}

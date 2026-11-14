package io.github.lostininaka.weatherglance;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One fetch at a time, and the newest city owns the answer. A worker freezes the city it is fetching for,
 * stores a reading only while that city is still the owner's choice, and takes up the newer choice when it
 * was overtaken: the pick's own request was dropped by the slot this worker held, so nobody else would.
 */
final class Fetch {
    /** What a worker needs from the app: the owner's choice, where a reading goes, and who to tell. */
    interface Cities {
        Selections.Pick pick();

        /** False once another city has been picked: an answer for {@code pick} must be thrown away. */
        boolean stillCurrent(Selections.Pick pick);

        /** Store the reading for the pick it was fetched for; false when that pick is no longer current. */
        boolean save(Selections.Pick forPick, OpenMeteo.Now reading, long updatedMs);

        void notifyReaders();

        /**
         * A fetch for the current city failed. The last good reading stands, so this is for the record rather
         * than for recovery: an exception nobody logs reads exactly like a line the launcher chose to hide.
         */
        default void failed(Exception why) {}
    }

    /** The network as one call: a point in, the conditions there out, null when the answer has none. */
    interface Forecast {
        OpenMeteo.Now current(double lat, double lon) throws IOException;
    }

    private final Forecast forecast;
    private final Executor workers;
    private final AtomicBoolean running = new AtomicBoolean();

    Fetch(Forecast forecast, Executor workers) {
        this.forecast = forecast;
        this.workers = workers;
    }

    /** Fetch the current city, unless a worker is already on it. Safe to call from any thread. */
    void request(Cities cities) {
        if (!running.compareAndSet(false, true)) return;
        try {
            workers.execute(() -> work(cities));
        } catch (RuntimeException noThread) {
            // Nothing to run on: give the slot back, or every later read is locked out for good.
            running.set(false);
        }
    }

    /**
     * Run the fetch on the calling thread and return when it is done: a job has to know when the work it was
     * started for has finished, so it can report that back rather than leave the system guessing. Does nothing
     * while another worker holds the slot - that worker stores a reading for the same city anyway.
     */
    void runHere(Cities cities) {
        if (!running.compareAndSet(false, true)) return;
        work(cities);
    }

    private void work(Cities cities) {
        Selections.Pick fetched = null;
        try {
            fetched = cities.pick();
            if (fetched.usable()) {
                OpenMeteo.Now reading = forecast.current(fetched.lat, fetched.lon);
                if (reading != null && cities.save(fetched, reading, System.currentTimeMillis())) {
                    cities.notifyReaders();
                }
            }
        } catch (IOException | RuntimeException failed) {
            // No network, DNS or TLS, or an answer we could not read: the last good reading stands, and the
            // next read or the job's next run tries again. A retry from here would only hammer a dead link.
            cities.failed(failed);
        } finally {
            // Let go before asking whether to go round again: a pick that lands while this worker still
            // owned the slot had its own request dropped, and it is visible by the time we look.
            running.set(false);
        }
        // Failing, empty or overtaken, an answer for a city the owner has left is of no use; fetch the
        // one that is current now instead. A failure for the same city asks for nothing, and gets it.
        if (fetched != null && !cities.stillCurrent(fetched)) request(cities);
    }
}

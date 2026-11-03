package io.github.lostininaka.weatherglance;

import android.content.Context;

import java.util.concurrent.Executor;

/**
 * Fetches the reading the launcher publishes and tells it to look again. Runs only when the owner opens
 * this app or the launcher reads a reading that has gone stale - there is no service, no alarm and no
 * polling of its own.
 */
final class Refresh {
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
    }
}

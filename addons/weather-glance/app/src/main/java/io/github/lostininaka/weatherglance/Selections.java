package io.github.lostininaka.weatherglance;

/**
 * The city the owner picked, as one frozen value, and the gate a fetched reading has to pass. Choosing a
 * city starts a new generation; a reading fetched for an older one is dropped rather than stored, because
 * the name it would be stored under is no longer the reading's own, and the timestamp it would carry
 * would make the launcher trust it for the next twenty minutes.
 */
final class Selections {
    /** What one fetch is for: the city, its coordinates, and the generation that can invalidate them. */
    static final class Pick {
        final String place;
        final double lat, lon;
        final long generation;

        Pick(String place, double lat, double lon, long generation) {
            this.place = place == null ? "" : place;
            this.lat = lat;
            this.lon = lon;
            this.generation = generation;
        }

        /** A pick there is something to fetch for: a name and a point. */
        boolean usable() {
            return !place.isEmpty() && !Double.isNaN(lat) && !Double.isNaN(lon);
        }
    }

    private final Object lock = new Object();
    private Pick current; // guarded by lock

    Selections(Pick first) {
        current = first;
    }

    Pick current() {
        synchronized (lock) {
            return current;
        }
    }

    /**
     * Take the owner's new choice. {@code write} runs under the same lock, so the reading the old city
     * left behind cannot outlive the pick it belonged to.
     */
    Pick pick(String place, double lat, double lon, Runnable write) {
        synchronized (lock) {
            current = new Pick(place, lat, lon, current.generation + 1);
            write.run();
            return current;
        }
    }

    /** False once another city has been picked: an answer fetched for {@code pick} is no longer wanted. */
    boolean stillCurrent(Pick pick) {
        synchronized (lock) {
            return pick != null && pick.generation == current.generation;
        }
    }

    /**
     * Run {@code store} for the pick a reading was fetched for, and only while that pick is still the
     * owner's choice. The check and the write share the lock {@link #pick} takes, so a city chosen while
     * the reading is being written cannot end up labelled with the old city's weather.
     */
    boolean accept(Pick forPick, Runnable store) {
        synchronized (lock) {
            if (!stillCurrent(forPick)) return false;
            store.run();
            return true;
        }
    }
}

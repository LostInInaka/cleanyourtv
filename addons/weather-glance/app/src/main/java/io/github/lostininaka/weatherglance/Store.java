package io.github.lostininaka.weatherglance;

import android.content.Context;
import android.content.SharedPreferences;

/** The city the owner picked and the reading fetched for it, in this app's own SharedPreferences. */
final class Store {
    /** How long a fetched reading is trusted; the launcher is told to read again after it expires. */
    static final long STALE_MS = 20 * 60_000L;
    /** Open-Meteo answers in Celsius; the provider names the unit so the launcher never has to guess. */
    static final String UNIT = "C";

    private static final String FILE = "weather";
    /**
     * The process's one choice. A Store is built per screen, per worker and per provider read, so the
     * generations that tell an answer from a stale one cannot live in an instance: two of them would
     * disagree about which pick a reading belongs to.
     */
    private static Selections selections;

    private final SharedPreferences p;

    Store(Context ctx) {
        p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        synchronized (Store.class) {
            if (selections == null) {
                selections = new Selections(new Selections.Pick(
                        p.getString("place", ""), number("lat"), number("lon"), 0L));
            }
        }
    }

    Selections.Pick pick() {
        return selections.current();
    }

    boolean stillCurrent(Selections.Pick pick) {
        return selections.stillCurrent(pick);
    }

    boolean hasPlace() {
        return pick().usable();
    }

    String place() {
        return pick().place;
    }

    double lat() {
        return pick().lat;
    }

    double lon() {
        return pick().lon;
    }

    /** The city alone, without the region and country: what the line under the clock can afford. */
    String shortPlace() {
        String place = place();
        int comma = place.indexOf(',');
        return comma > 0 ? place.substring(0, comma).trim() : place;
    }

    /** True once a reading has been stored for the current city. */
    boolean hasReading() {
        return p.contains("temperature");
    }

    /** The reading, rounded; only meaningful when {@link #hasReading()}. */
    int temperature() {
        return p.getInt("temperature", 0);
    }

    int code() {
        return p.getInt("code", -1);
    }

    boolean day() {
        return p.getBoolean("day", true);
    }

    long updated() {
        return p.getLong("updated", 0L);
    }

    boolean stale() {
        // A version before the columns stored an updated time and no temperature: nothing to publish yet,
        // so it is stale by definition, and the next read fetches rather than waiting out the old stamp.
        return !hasReading() || System.currentTimeMillis() - updated() >= STALE_MS;
    }

    /** The line this app's own screen shows: the stored reading in the words the launcher cannot fit. */
    String line() {
        if (!hasReading()) return "";
        return Conditions.line(temperature(), Conditions.describe(code())[1], shortPlace());
    }

    void savePlace(String place, double lat, double lon) {
        // The old reading belongs to the old town: drop it with the pick, so a worker still in flight for
        // that town cannot leave its answer under this name.
        selections.pick(place, lat, lon, () -> p.edit()
                .putString("place", place).putString("lat", Double.toString(lat))
                .putString("lon", Double.toString(lon)).remove("temperature").remove("code")
                .remove("day").remove("updated").apply());
    }

    /**
     * Store a reading, but only for the pick it was fetched for: false means the owner has chosen another
     * city since and nothing was written, so no timestamp can make the launcher trust weather that was
     * fetched for a different place.
     */
    boolean saveReading(Selections.Pick forPick, int celsius, int code, boolean day, long updatedMs) {
        return saveReading(forPick, celsius, code, day, updatedMs, -1);
    }

    /** As above, with the humidity the reading came with: -1 stores nothing, so an older add-on's data still fits. */
    boolean saveReading(Selections.Pick forPick, int celsius, int code, boolean day, long updatedMs, int humidity) {
        return selections.accept(forPick, () -> {
            SharedPreferences.Editor e = p.edit()
                    .putInt("temperature", celsius).putInt("code", code)
                    .putBoolean("day", day).putLong("updated", updatedMs);
            if (humidity >= 0 && humidity <= 100) e.putInt("humidity", humidity); else e.remove("humidity");
            e.apply();
        });
    }

    /** Relative humidity in percent, or -1 when this reading has none. */
    int humidity() {
        return p.getInt("humidity", -1);
    }

    private double number(String key) {
        try {
            return Double.parseDouble(p.getString(key, "NaN"));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}

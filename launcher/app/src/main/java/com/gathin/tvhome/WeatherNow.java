package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * weather_now (config.xml or a theme): the sky and the temperature under the clock, from an add-on such as
 * Weather Glance. The launcher only reads what the add-on publishes and stays offline - the add-on does the
 * fetching, exactly as Game Day does for game_day.
 */
final class WeatherNow {
    static final String META = "com.gathin.tvhome.weather";
    /** A reading older than this one is yesterday's weather: the row goes rather than say something stale. */
    /** Below this a chance of rain is noise under the clock; at or above it, the viewer may want a coat. */

    static final long FRESH_MS = 3 * 60 * 60 * 1000L;
    /** Below this a chance is not worth the width it takes: 20% under a clock is noise, 30% is a coat. */
    static final int WORTH_MENTIONING = 30;
    /**
     * The add-on's contract, asked for exactly: place, temperature, unit, code, is_day, updated_ms. `unit` is
     * C or F and is never drawn - the launcher prints its own degree - but the query asks for it as agreed.
     */
    private static final String[] COLUMNS = {"place", "temperature", "unit", "code", "is_day", "updated_ms",
            "sky", "humidity", "precipitation_chance", "snow_chance"};   // the last two are optional: a provider that does not publish them still draws

    private final Context ctx;
    private final Uri uri;
    private final Handler main;
    private final Runnable onChange;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ContentObserver observer;
    /**
     * Home is on screen, so a line may be shown. Away from {@link #watching} on purpose: a provider that
     * refuses the observer still answers the one query Home makes as it comes up.
     */
    private volatile boolean active;
    /** Bumped by {@link #stop()}: every query that claimed the old number belongs to a dead session. */
    private volatile int generation;
    /** The observer is registered; false says only that, never that no line may be read. */
    private boolean watching;
    private Line line;

    private WeatherNow(Context ctx, Uri uri, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.uri = uri;
        this.main = main;
        this.onChange = onChange;
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                read();
            }
        };
    }

    /**
     * Null when the key is empty, the package is not installed, or it names no provider of its own in a
     * {@value #META} meta-data. Naming the package is the owner's opt-in, as for game_day.
     */
    static WeatherNow create(Context ctx, String pkg, Handler main, Runnable onChange) {
        if (pkg == null || pkg.trim().isEmpty()) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg.trim(), PackageManager.GET_META_DATA);
            String authority = info.metaData == null ? null : info.metaData.getString(META);
            if (authority == null || authority.trim().isEmpty()) return null;
            // The provider must be the named package's own, not another app's that claims the authority.
            ProviderInfo provider = pm.resolveContentProvider(authority.trim(), 0);
            if (provider == null || !pkg.trim().equals(provider.packageName)) return null;
            Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority.trim())
                    .appendPath("now").build();
            return new WeatherNow(ctx, uri, main, onChange);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Home is on screen: read now and watch for changes. */
    void start() {
        active = true;
        if (!watching) {
            try {
                ctx.getContentResolver().registerContentObserver(uri, false, observer);
                watching = true;
            } catch (SecurityException e) {
                // The add-on's provider went away: the row keeps what it had, and the read below still runs.
            }
        }
        read();
    }

    /** Home is off screen: the observer goes, and every query still in flight loses its claim. */
    void stop() {
        // Before the unregister, so a result already posted to the main thread finds itself unwanted.
        active = false;
        generation++;
        if (watching) ctx.getContentResolver().unregisterContentObserver(observer);
        watching = false;
    }

    void shutdown() {
        stop();
        // The queue goes with this, but a query already inside the provider cannot be cancelled: the bump in
        // stop() is what keeps its result off the screen.
        io.shutdownNow();
    }

    /** One query off the main thread (the provider lives in the add-on's process), then onChange on the main thread. */
    void read() {
        if (!active) return;
        int claimed = generation;
        try {
            io.execute(() -> {
                // Home may have gone while this waited its turn in the queue: shutdownNow cannot take back a
                // task a single-thread executor has already started, so the claim is checked here as well.
                if (!owned(active, claimed, generation)) return;
                Object[] row;
                try {
                    row = row();
                } catch (RuntimeException gone) {
                    // Uninstalled or crashed mid-query: keep what is on screen.
                    return;
                }
                Line found = fromRow(row, System.currentTimeMillis());
                main.post(() -> {
                    // And once more on the way out: stop() can land while the provider is being read.
                    if (!owned(active, claimed, generation)) return;
                    line = found;
                    onChange.run();
                });
            });
        } catch (RejectedExecutionException shutDown) {
            // Home is being destroyed.
        }
    }

    /** The add-on's one row, read by name; null when it published nothing. Runs on the worker. */
    private Object[] row() {
        try (Cursor c = ctx.getContentResolver().query(uri, COLUMNS, null, null, null)) {
            if (c == null || !c.moveToNext()) return null;
            return readRow(c);
        }
    }

    /**
     * One cursor's row, column by column. Separate from the query so the NULL handling below can be tested
     * without a TV: {@link Cursor#getInt} answers 0 for a NULL column, and a chance of 0% is a figure the
     * provider did not publish — drawn as "Snow 0%" under a clear sky until this read the flag first.
     */
    static Object[] readRow(Cursor c) {
        {
            // By name, never by position: a provider may answer with the columns in its own order, and a
            // column it does not publish is simply absent.
            Object[] row = new Object[COLUMNS.length];
            int at = c.getColumnIndex("place");
            if (at >= 0) row[0] = c.getString(at);
            at = c.getColumnIndex("temperature");
            if (at >= 0) row[1] = c.getDouble(at);
            at = c.getColumnIndex("unit");
            if (at >= 0) row[2] = c.getString(at);
            at = c.getColumnIndex("code");
            if (at >= 0) row[3] = c.getInt(at);
            at = c.getColumnIndex("is_day");
            if (at >= 0) row[4] = c.getInt(at);
            at = c.getColumnIndex("updated_ms");
            if (at >= 0 && !c.isNull(at)) row[5] = c.getLong(at);
            // Optional, from javierfn's apple-tv-style branch: the sky in words, and relative humidity. Absent
            // means the line drops that part and nothing else, so an add-on publishing the six still works.
            at = c.getColumnIndex("sky");
            if (at >= 0) row[6] = c.getString(at);
            at = c.getColumnIndex("humidity");
            if (at >= 0 && !c.isNull(at)) row[7] = c.getInt(at);
            at = c.getColumnIndex("precipitation_chance");
            if (at >= 0 && !c.isNull(at)) row[8] = c.getInt(at);
            at = c.getColumnIndex("snow_chance");
            if (at >= 0 && !c.isNull(at)) row[9] = c.getInt(at);
            return row;
        }
    }

    /**
     * The row as a line, or null for none: nothing published, no temperature, no updated_ms, or a reading
     * older than {@link #FRESH_MS}. `is_day` rides on the code - only the clear skies differ at night.
     */
    static Line fromRow(Object[] row, long nowMs) {
        if (row == null || !(row[1] instanceof Number) || !(row[5] instanceof Number)) return null;
        long updated = ((Number) row[5]).longValue();
        if (nowMs - updated > FRESH_MS) return null;
        String place = row[0] instanceof String ? ((String) row[0]).trim() : "";
        int code = row[3] instanceof Number ? ((Number) row[3]).intValue() : -1;
        boolean day = !(row[4] instanceof Number) || ((Number) row[4]).intValue() != 0;
        // A caller that hands us the six-column row (older tests, a provider we could not ask for more) simply
        // has no optional parts.
        String sky = row.length > 6 && row[6] instanceof String ? ((String) row[6]).trim() : "";
        int humidity = row.length > 7 && row[7] instanceof Number ? ((Number) row[7]).intValue() : -1;
        int precipChance = row.length > 8 && row[8] instanceof Number ? ((Number) row[8]).intValue() : -1;
        int snowChance = row.length > 9 && row[9] instanceof Number ? ((Number) row[9]).intValue() : -1;
        return new Line(glyph(code, day),
                text(((Number) row[1]).doubleValue(), place, sky, humidity, precipChance, snowChance, code), updated);
    }

    /**
     * True once a line that was shown has outlived {@link #FRESH_MS}. The row was fresh when it was read, but
     * Home can stay on screen for hours: without this a reading fetched at nine in the morning would still be
     * under the clock at midnight, which is the one thing the three-hour rule exists to prevent.
     */
    static boolean expired(Line shown, long nowMs) {
        return shown != null && nowMs - shown.updated > FRESH_MS;
    }

    /**
     * Drop a line that has aged out, and say whether it was there to drop. The caller hides the row and reads
     * again, so an add-on that has published meanwhile can put it back; nothing re-reads until it does.
     */
    boolean expireIfStale() {
        if (!expired(line, System.currentTimeMillis())) return false;
        line = null;
        return true;
    }

    /**
     * "14° Takizawa", or "14°" with no place: the launcher's own degree, no unit letter. The optional sky goes
     * after the place ("14° Takizawa · Cloudy") and humidity last (" · Humidity 90%"), each dropped when the
     * add-on does not publish it.
     */
    static String text(double temperature, String place) {
        return text(temperature, place, "", -1, -1, -1);
    }

    static String text(double temperature, String place, String sky, int humidity) {
        return text(temperature, place, sky, humidity, -1, -1);
    }

    /**
     * The chance comes last, after the humidity, and only from {@link #WORTH_MENTIONING} up: a figure that low is
     * noise under a clock. Snow wins over rain: when the forecast says snow, the snow figure is the one the viewer
     * is deciding about. A number outside 0..100 is not a chance at all, so it is dropped rather than drawn as
     * nonsense.
     */
    static String text(double temperature, String place, String sky, int humidity, int precipChance, int snowChance) {
        return text(temperature, place, sky, humidity, precipChance, snowChance, -1);
    }

    /**
     * The same line, with the weather code to say what the sky is when the words are not ours to read. The sky
     * text comes from the add-on and is written in the TV's own language, so "does the sky already say snow?"
     * cannot be answered by looking for an English word: a Spanish provider says "Nieve", and the code says it
     * in every language.
     */
    static String text(double temperature, String place, String sky, int humidity, int precipChance, int snowChance,
            int code) {
        StringBuilder out = new StringBuilder(Math.round(temperature) + "\u00B0");
        if (place != null && !place.isEmpty()) out.append(' ').append(place);
        if (sky != null && !sky.isEmpty()) out.append(" \u00B7 ").append(sky);
        // Under a sky that already says snow, the snow figure is the one shown: "Snow · Rain 40%" argues with
        // itself, so rain never answers a snow sky, even when rain is the likelier of the two.
        if (humidity >= 0 && humidity <= 100) out.append(" \u00B7 Humidity ").append(humidity).append("%");
        // Last, and only once it is worth carrying an umbrella: the figure follows the facts rather than sitting
        // between them, and 20% under a clock is noise.
        boolean snowSky = names(sky, "snow") || isSnow(code);
        if (snowChance >= WORTH_MENTIONING && snowChance <= 100) {
            percent(out, snowSky, "snow", snowChance);
        } else if (precipChance >= WORTH_MENTIONING && precipChance <= 100 && !snowSky) {
            percent(out, names(sky, "rain"), "rain", precipChance);
        }
        return out.toString();
    }

    /**
     * U+FE0E, the text presentation selector: several of these symbols are emoji by default, and a panel that
     * carries NotoColorEmoji will draw a cartoon umbrella next to a plain line of type without it. One character,
     * no bytes of artwork, and the glyph inherits the text colour the way the rest of the line does.
     */
    private static final String PLAIN = "\uFE0E";

    /** One glyph per WMO group the add-on can publish; a code we do not know leaves the line without one. */
    static String glyph(int code, boolean day) {
        String g;
        switch (code) {
            case 0: case 1: g = day ? "\u2600" : "\u263E"; break;        // clear, mainly clear
            case 2: g = "\u26C5"; break;                                  // partly cloudy
            case 3: g = "\u2601"; break;                                  // overcast
            case 45: case 48: g = "\u2261"; break;                        // fog
            case 51: case 53: case 55: case 56: case 57: g = "\u2602"; break; // drizzle, freezing drizzle
            case 61: case 63: case 65: case 66: case 67: g = "\u2614"; break; // rain, freezing rain
            case 71: case 73: case 75: case 77: case 85: case 86: g = "\u2744"; break; // snow, snow showers
            case 80: case 81: case 82: g = "\u2614"; break;               // rain showers
            case 95: case 96: case 99: g = "\u26A1"; break;               // thunderstorm, with hail
            default: return "";
        }
        return g + PLAIN;
    }

    /**
     * True while a claim still belongs to the session Home is showing. {@link #read()} asks this at the
     * worker's entry and again at delivery, so a stop - or a stop and a fresh start - declines the result
     * the previous session asked for.
     */
    static boolean owned(boolean active, int claimed, int generation) {
        return active && claimed == generation;
    }

    /** The line to show, or null for none: no key, no add-on, or nothing fresh published yet. */
    Line line() {
        return line;
    }

    /**
     * Appends the chance, without saying "snow" twice when the sky has already said it: "Snow \u00B7 70%", not
     * "Snow \u00B7 Snow 70%".
     */
    private static void percent(StringBuilder out, boolean said, String word, int value) {
        out.append(" \u00B7 ");
        if (!said) out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        out.append(value).append("%");
    }

    /** The codes that are snow, whatever word the provider uses for them. */
    static boolean isSnow(int code) {
        return (code >= 71 && code <= 77) || code == 85 || code == 86;
    }

    /** Whether the sky's own wording already contains this word, case aside. */
    private static boolean names(String sky, String word) {
        return sky != null && sky.toLowerCase(java.util.Locale.ROOT).contains(word);
    }

    /** One row under the clock: the sky's glyph and "14° Takizawa". */
    static final class Line {
        final String glyph;
        final String text;
        /** When the add-on took the reading, so the row can be withdrawn once it is older than the contract allows. */
        final long updated;

        Line(String glyph, String text, long updated) {
            this.glyph = glyph;
            this.text = text;
            this.updated = updated;
        }
    }
}

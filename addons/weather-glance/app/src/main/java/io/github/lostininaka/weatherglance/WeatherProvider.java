package io.github.lostininaka.weatherglance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * What the launcher reads: content://io.github.lostininaka.weatherglance.now/now, one row built from the
 * stored reading, or none until a city has been picked and fetched. The launcher never writes here and
 * never goes online; it names this package in its weather_now key. Contract: docs/modules.md in the Clean
 * Your TV repo.
 *
 * <p>The columns are the weather, not a rendering of it - place, temperature, unit, code, is_day,
 * updated_ms - so the launcher draws the glyph and the line in its own style. The provider is exported
 * without a permission, so it holds nothing private. A read whose row is older than
 * {@link Store#STALE_MS} also starts a refresh, which calls notifyChange when the new reading is stored.
 */
public final class WeatherProvider extends ContentProvider {
    static final String AUTHORITY = "io.github.lostininaka.weatherglance.now";
    static final Uri NOW = Uri.parse("content://" + AUTHORITY + "/now");
    private static final String[] COLUMNS =
            {"_id", "place", "temperature", "unit", "code", "is_day", "updated_ms", "sky", "humidity"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        Context ctx = getContext();
        // Answer with the columns the reader asked for, in its order, plus the row id it needs.
        String[] columns = projection == null ? COLUMNS : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        if (ctx == null) return cursor;
        Store store = new Store(ctx);
        // No reading yet is no row at all: the launcher hides its line rather than name a city with no weather.
        if (store.hasReading()) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                if ("_id".equals(column)) row.add(1);
                else if ("place".equals(column)) row.add(store.shortPlace());
                else if ("temperature".equals(column)) row.add(store.temperature());
                else if ("unit".equals(column)) row.add(Store.UNIT);
                else if ("code".equals(column)) row.add(store.code());
                else if ("is_day".equals(column)) row.add(store.day() ? 1 : 0);
                else if ("updated_ms".equals(column)) row.add(store.updated());
                // Optional pair, matching javierfn's apple-tv-style branch so an add-on serves either fork: the
                // sky in words, and the humidity when the reading carried one (null drops it on the launcher side).
                else if ("sky".equals(column)) row.add(Conditions.describe(store.code())[1]);
                else if ("humidity".equals(column)) {
                    int humidity = store.humidity();
                    row.add(humidity >= 0 ? humidity : null);
                }
                else row.add(null);
            }
        }
        Refresh.ifStale(ctx);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.io.github.lostininaka.weatherglance.now";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only: the launcher never writes here");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only: the launcher never writes here");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only: the launcher never writes here");
    }
}

package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WeatherNowTest {
    private static final long HOUR = 3_600_000L;
    private static final long NOW = 1_790_000_000_000L;

    /** One row of the add-on's cursor: place, temperature, unit, code, is_day, updated_ms. */
    private static Object[] row(String place, Double temperature, int code, int day, Long updated) {
        return new Object[] {place, temperature, "C", code, day, updated};
    }

    private static WeatherNow.Line fresh(String place, double temperature, int code, int day) {
        return WeatherNow.fromRow(row(place, temperature, code, day, NOW - 60_000L), NOW);
    }

    @Test public void theLineReadsSkyTemperatureAndPlace() {
        WeatherNow.Line line = fresh("Takizawa", 14.4, 3, 1);
        assertNotNull(line);
        assertEquals("☁", line.glyph);
        assertEquals("14° Takizawa", line.text);
    }

    @Test public void theTemperatureIsRoundedAndCarriesNoUnitLetter() {
        assertEquals("15°", WeatherNow.text(14.6, ""));
        assertEquals("14°", WeatherNow.text(14.4, ""));
        assertEquals("0°", WeatherNow.text(-0.4, ""));
        assertEquals("-3°", WeatherNow.text(-2.6, ""));
    }

    @Test public void theOptionalSkyAndHumidityRideAlongAndDropWhenAbsent() {
        assertEquals("14\u00B0 Takizawa \u00B7 Cloudy", WeatherNow.text(14.4, "Takizawa", "Cloudy", -1));
        assertEquals("14\u00B0 Takizawa \u00B7 Humidity 90 %", WeatherNow.text(14.4, "Takizawa", "", 90));
        assertEquals("14\u00B0 Takizawa \u00B7 Cloudy \u00B7 Humidity 90 %",
                WeatherNow.text(14.4, "Takizawa", "Cloudy", 90));
        // An add-on that publishes only the six columns draws exactly what it drew before.
        assertEquals("14\u00B0 Takizawa", WeatherNow.text(14.4, "Takizawa", "", -1));
        // Out of range means "not published", not "0 %".
        assertEquals("14\u00B0", WeatherNow.text(14.4, "", "", 140));
    }

    @Test public void aPlaceThatIsBlankLeavesTheTemperatureAlone() {
        // The place is trimmed on the way in, so a blank one is no place at all.
        assertEquals("14°", fresh("   ", 14.2, 0, 1).text);
        assertEquals("14°", WeatherNow.text(14.2, null));
    }

    @Test public void theNightSkiesGetTheMoon() {
        assertEquals("☀", WeatherNow.glyph(0, true));
        assertEquals("☾", WeatherNow.glyph(0, false));
        assertEquals("☀", WeatherNow.glyph(1, true));
        assertEquals("☾", WeatherNow.glyph(1, false));
        // Everything below is the same by day and by night.
        assertEquals("⛅", WeatherNow.glyph(2, true));
        assertEquals("⛅", WeatherNow.glyph(2, false));
        assertEquals("≡", WeatherNow.glyph(48, true));
        assertEquals("☂", WeatherNow.glyph(55, false));
        assertEquals("☔", WeatherNow.glyph(65, true));
        assertEquals("❄", WeatherNow.glyph(86, false));
        assertEquals("⚡", WeatherNow.glyph(99, true));
    }

    @Test public void aCodeTheLauncherDoesNotKnowStillLeavesTheTemperature() {
        WeatherNow.Line line = fresh("Takizawa", 14.2, 1234, 1);
        assertEquals("", line.glyph);
        assertEquals("14° Takizawa", line.text);
    }

    @Test public void aReadingOlderThanThreeHoursGoes() {
        assertNull(WeatherNow.fromRow(row("Takizawa", 14.0, 3, 1, NOW - 3 * HOUR - 1), NOW));
        // Exactly three hours is still the same reading: the row goes past that, not at it.
        assertNotNull(WeatherNow.fromRow(row("Takizawa", 14.0, 3, 1, NOW - 3 * HOUR), NOW));
    }

    @Test public void aRowWithoutTemperatureOrUpdatedTimeShowsNothing() {
        assertNull(WeatherNow.fromRow(row("Takizawa", null, 3, 1, NOW), NOW));
        assertNull(WeatherNow.fromRow(row("Takizawa", 14.0, 3, 1, null), NOW));
        assertNull(WeatherNow.fromRow(null, NOW));
    }

    @Test public void aProviderThatPublishesNothingShowsNothing() {
        // Every column absent: an add-on that answered with an empty row rather than no row.
        assertNull(WeatherNow.fromRow(new Object[6], NOW));
    }

    @Test public void aMissingIsDayReadsAsDayAndAMissingCodeAsNoSky() {
        WeatherNow.Line line = WeatherNow.fromRow(new Object[] {"Takizawa", 14.0, "C", null, null, NOW}, NOW);
        assertNotNull(line);
        assertEquals("", line.glyph);
        assertEquals("14° Takizawa", line.text);
    }

    @Test public void aClaimDiesWithTheSessionThatMadeIt() {
        // read() asks this at the worker's entry and again at delivery: active means Home is on screen.
        assertTrue(WeatherNow.owned(true, 7, 7));
    }

    @Test public void aStoppedHomeDeclinesTheResultItAskedFor() {
        // stop() clears active and bumps the generation: the query queued before it cannot assign the line.
        assertFalse(WeatherNow.owned(false, 7, 7));
    }

    @Test public void aStopAndStartDeclinesThePreviousSessionsResult() {
        // The published finding: stop() -> start() accepts what the old session asked for unless the
        // generation moved. The claim of the old read carries the old number and is refused.
        assertFalse(WeatherNow.owned(true, 7, 8));
        assertTrue(WeatherNow.owned(true, 8, 8));
    }

    @Test public void aLineCarriesTheTimeTheAddOnTookTheReading() {
        // The time has to survive the read, or a row already on screen cannot be judged later.
        assertEquals(NOW - 60_000L, fresh("Takizawa", 14.4, 3, 1).updated);
    }

    @Test public void aRowThatAgesWhileHomeStaysUpIsWithdrawn() {
        // A reading is fresh when it is read, but Home can stay on screen for hours: the row goes when its own
        // three hours are up, not only when it is next fetched. Exactly three hours is still shown, as it is
        // on the way in.
        WeatherNow.Line shown = fresh("Takizawa", 14.4, 3, 1);
        long at = shown.updated;
        assertFalse(WeatherNow.expired(shown, at + 3 * HOUR));
        assertTrue(WeatherNow.expired(shown, at + 3 * HOUR + 1));
        // Nothing on screen is nothing to withdraw.
        assertFalse(WeatherNow.expired(null, at + 24 * HOUR));
    }
}

package io.github.lostininaka.weatherglance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The gate a fetched reading passes before it is stored under the owner's city. */
public final class SelectionsTest {

    @Test
    public void aPickAfterTheFetchMakesTheAnswerStale() {
        Selections selections = takizawa();
        Selections.Pick fetched = selections.current();

        selections.pick("Morioka, Iwate, Japan", 39.7019, 141.15, () -> {});

        assertFalse(selections.stillCurrent(fetched));
        assertFalse("the old city's weather is not stored under the new city's name",
                selections.accept(fetched, () -> fail("stored a reading for a city the owner had left")));
    }

    @Test
    public void anAnswerForTheCurrentPickIsStored() {
        Selections selections = takizawa();
        Selections.Pick fetched = selections.current();
        final int[] stored = {-999};

        assertTrue(selections.accept(fetched, () -> stored[0] = 14));
        assertEquals(14, stored[0]);
    }

    @Test
    public void aPickWaitsForTheReadingBeingStored() throws Exception {
        Selections selections = takizawa();
        Selections.Pick fetched = selections.current();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch picked = new CountDownLatch(1);

        Thread worker = new Thread(() -> selections.accept(fetched, () -> {
            writing.countDown();
            await(release);
        }), "test-store");
        worker.start();
        assertTrue(writing.await(5, TimeUnit.SECONDS));

        Thread owner = new Thread(() -> {
            selections.pick("Morioka, Iwate, Japan", 39.7019, 141.15, () -> {});
            picked.countDown();
        }, "test-pick");
        owner.start();
        // The pick takes the same lock the write holds: it cannot land in the middle of the reading and
        // leave the new city's name over the old city's weather.
        assertFalse(picked.await(200, TimeUnit.MILLISECONDS));

        release.countDown();
        assertTrue(picked.await(5, TimeUnit.SECONDS));
        worker.join(5_000);
        owner.join(5_000);
    }

    @Test
    public void aPickWithNoNameOrNoPointIsNotFetchable() {
        assertFalse(new Selections.Pick("", 39.6846, 141.15, 0L).usable());
        assertFalse(new Selections.Pick("Takizawa", Double.NaN, 141.15, 0L).usable());
        assertFalse(new Selections.Pick("Takizawa", 39.6846, Double.NaN, 0L).usable());
        assertTrue(new Selections.Pick("Takizawa", 39.6846, 141.15, 0L).usable());
    }

    private static Selections takizawa() {
        return new Selections(new Selections.Pick("Takizawa, Iwate, Japan", 39.6846, 141.15, 0L));
    }

    /** Waits for a latch, failing the test rather than the worker thread when it never opens. */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("the latch never opened");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

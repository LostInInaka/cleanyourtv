package io.github.lostininaka.weatherglance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Which reading is stored when the owner picks another city while a fetch is still in flight, and what a
 * worker does about a fetch that failed. The store is a fake with the real generation gate in it; the
 * workers are real threads, because the bug this guards is one of ordering, not of arithmetic.
 */
public final class FetchTest {
    private static final double TAKIZAWA = 39.6846;
    private static final double MORIOKA = 39.7019;

    private final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
    private final Executor workers = body -> {
        Thread thread = new Thread(body, "test-fetch");
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
    };

    @Test
    public void aReadingForACityTheOwnerLeftIsNeverStored() throws Exception {
        Overtaken run = overtaken(Old.ANSWER);

        assertEquals("only the new city's weather is stored", Collections.singletonList("Morioka:3"),
                run.cities.saved);
    }

    @Test
    public void aWorkerThatLostItsCityFetchesTheOneThatReplacedIt() throws Exception {
        Overtaken run = overtaken(Old.ANSWER);

        assertEquals("the held city first, then the current one", Arrays.asList(TAKIZAWA, MORIOKA),
                run.asked);
        assertEquals(1, run.cities.notices.get());
    }

    @Test
    public void aFailedFetchForTheCityTheOwnerLeftStillHandsOff() throws Exception {
        Overtaken run = overtaken(Old.FAILED);

        assertEquals(Arrays.asList(TAKIZAWA, MORIOKA), run.asked);
        assertEquals(Collections.singletonList("Morioka:3"), run.cities.saved);
    }

    @Test
    public void anEmptyAnswerForTheCityTheOwnerLeftStillHandsOff() throws Exception {
        Overtaken run = overtaken(Old.EMPTY);

        assertEquals(Arrays.asList(TAKIZAWA, MORIOKA), run.asked);
        assertEquals(Collections.singletonList("Morioka:3"), run.cities.saved);
    }

    @Test
    public void aFailedFetchForTheSameCityIsNotRetried() throws Exception {
        FakeCities cities = new FakeCities("Takizawa", TAKIZAWA);
        AtomicInteger calls = new AtomicInteger();
        Fetch fetch = new Fetch((lat, lon) -> {
            calls.incrementAndGet();
            throw new IOException("offline");
        }, workers);

        fetch.request(cities);
        settle();

        assertEquals("one attempt, and no retry of it", 1, calls.get());
        assertTrue(cities.saved.isEmpty());
        assertEquals(0, cities.notices.get());
    }

    @Test
    public void anEmptyAnswerForTheSameCityIsNotRetried() throws Exception {
        FakeCities cities = new FakeCities("Takizawa", TAKIZAWA);
        AtomicInteger calls = new AtomicInteger();
        Fetch fetch = new Fetch((lat, lon) -> {
            calls.incrementAndGet();
            return null;
        }, workers);

        fetch.request(cities);
        settle();

        assertEquals(1, calls.get());
        assertTrue(cities.saved.isEmpty());
    }

    @Test
    public void runHereFetchesOnTheCallingThreadAndStores() {
        FakeCities cities = new FakeCities("Takizawa", TAKIZAWA);
        Fetch fetch = new Fetch((lat, lon) -> new OpenMeteo.Now(14, 3, true), workers);

        fetch.runHere(cities);

        assertEquals("the reading is stored before the call returns",
                Collections.singletonList("Takizawa:14"), cities.saved);
        assertEquals(1, cities.notices.get());
        assertEquals("the job needs no thread of its own", 0, threads.size());
    }

    @Test
    public void runHereDoesNothingWhileAWorkerHoldsTheSlot() throws Exception {
        FakeCities cities = new FakeCities("Takizawa", TAKIZAWA);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Fetch fetch = new Fetch((lat, lon) -> {
            calls.incrementAndGet();
            inFlight.countDown();
            await(finish, "the test never let the fetch go");
            return new OpenMeteo.Now(14, 3, true);
        }, workers);

        fetch.request(cities);
        assertTrue("the background fetch starts", inFlight.await(5, TimeUnit.SECONDS));
        fetch.runHere(cities);  // the job arrives while the launcher's own fetch is still in the air
        finish.countDown();
        settle();

        assertEquals("one fetch for one city, not two", 1, calls.get());
        assertEquals(Collections.singletonList("Takizawa:14"), cities.saved);
    }

    @Test
    public void aFailedRunHereIsReportedAndDoesNotLockTheSlot() {
        FakeCities cities = new FakeCities("Takizawa", TAKIZAWA);
        AtomicBoolean offline = new AtomicBoolean(true);
        Fetch fetch = new Fetch((lat, lon) -> {
            if (offline.get()) throw new IOException("offline");
            return new OpenMeteo.Now(14, 3, true);
        }, workers);

        fetch.runHere(cities);
        assertEquals("the failure is reported, not swallowed", 1, cities.failures.get());
        assertTrue(cities.saved.isEmpty());

        offline.set(false);
        fetch.runHere(cities);
        assertEquals("a failure does not lock the slot for the next run",
                Collections.singletonList("Takizawa:14"), cities.saved);
        assertEquals(1, cities.failures.get());
    }

    /** What the held fetch for the old city answers. */
    private enum Old { ANSWER, EMPTY, FAILED }

    /** The scenario in the report: a fetch for Takizawa held open while the owner picks Morioka. */
    private static final class Overtaken {
        final FakeCities cities;
        final List<Double> asked;

        Overtaken(FakeCities cities, List<Double> asked) {
            this.cities = cities;
            this.asked = asked;
        }
    }

    private Overtaken overtaken(Old old) throws Exception {
        FakeCities cities = new FakeCities("Takizawa", TAKIZAWA);
        List<Double> asked = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Fetch fetch = new Fetch((lat, lon) -> {
            asked.add(lat);
            if (lat != TAKIZAWA) return new OpenMeteo.Now(3, 2, true);
            inFlight.countDown();
            await(finish, "the test never let the fetch go");
            if (old == Old.FAILED) throw new IOException("offline");
            return old == Old.EMPTY ? null : new OpenMeteo.Now(14, 3, true);
        }, workers);

        fetch.request(cities);
        assertTrue("the fetch for the first city starts", inFlight.await(5, TimeUnit.SECONDS));
        cities.choose("Morioka", MORIOKA);
        finish.countDown();
        settle();
        return new Overtaken(cities, asked);
    }

    /** Waits for every worker started here, including the one a handoff starts in turn. */
    private void settle() throws InterruptedException {
        for (int i = 0; ; i++) {
            Thread next;
            synchronized (threads) {
                next = i < threads.size() ? threads.get(i) : null;
            }
            if (next == null) return;
            next.join(5_000);
        }
    }

    /** Waits for a latch the test only opens once the owner has picked again. */
    private static void await(CountDownLatch latch, String message) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    /** The store the provider reads, without Android: the real generation gate over an in-memory reading. */
    private static final class FakeCities implements Fetch.Cities {
        final Selections selections;
        final List<String> saved = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger notices = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();

        FakeCities(String place, double lat) {
            selections = new Selections(new Selections.Pick(place, lat, 141.15, 0L));
        }

        /** What choosing a city does: a new generation, and whatever the old one left is gone. */
        void choose(String place, double lat) {
            selections.pick(place, lat, 141.15, () -> {});
        }

        @Override public Selections.Pick pick() {
            return selections.current();
        }

        @Override public boolean stillCurrent(Selections.Pick pick) {
            return selections.stillCurrent(pick);
        }

        @Override public boolean save(Selections.Pick forPick, OpenMeteo.Now reading, long updatedMs) {
            return selections.accept(forPick, () -> saved.add(forPick.place + ":" + reading.celsius));
        }

        @Override public void notifyReaders() {
            notices.incrementAndGet();
        }

        @Override public void failed(Exception why) {
            failures.incrementAndGet();
        }
    }
}

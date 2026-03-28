package com.intelos;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 tests for {@link ExpiringCounter}.
 *
 * <p>A {@link FakeClock} is used in all tests so that time is controlled
 * explicitly—no real-time waits are needed except where noted.
 */
class ExpiringCounterTest {

    // -----------------------------------------------------------------------
    // Fake clock used by all tests
    // -----------------------------------------------------------------------

    /**
     * Deterministic clock backed by an {@link AtomicLong}.
     * The time only changes when the test calls {@link #setTime} or {@link #advance}.
     */
    static class FakeClock implements Clock {
        private final AtomicLong time;

        FakeClock(long initialTime) {
            this.time = new AtomicLong(initialTime);
        }

        @Override
        public long now() {
            return time.get();
        }

        void setTime(long t) {
            time.set(t);
        }

        void advance(long ms) {
            time.addAndGet(ms);
        }
    }

    // -----------------------------------------------------------------------
    // 1. Empty counter
    // -----------------------------------------------------------------------

    @Test
    void emptyCounterReturnsZero() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(1000, clock);
        assertEquals(0, counter.getCount());
    }

    // -----------------------------------------------------------------------
    // 2. Simple recording
    // -----------------------------------------------------------------------

    @Test
    void simpleRecordingIncrementsCount() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(1000, clock);

        counter.record();
        assertEquals(1, counter.getCount());

        counter.record();
        counter.record();
        assertEquals(3, counter.getCount());
    }

    // -----------------------------------------------------------------------
    // 3. Expiration
    // -----------------------------------------------------------------------

    @Test
    void eventsExpireAfterWindow() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(1000, clock);

        counter.record();           // event at t=0
        assertEquals(1, counter.getCount());

        // At exactly t=1000 (== 0 + windowMillis) the event should be expired
        clock.setTime(1000);
        assertEquals(0, counter.getCount());
    }

    // -----------------------------------------------------------------------
    // 4. Rolling window behaviour
    // -----------------------------------------------------------------------

    @Test
    void rollingWindowKeepsOnlyRecentEvents() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(1000, clock);

        counter.record();           // t=0
        clock.setTime(500);
        counter.record();           // t=500
        assertEquals(2, counter.getCount());

        // t=0 event expires at t=1000
        clock.setTime(1000);
        assertEquals(1, counter.getCount());

        // t=500 event expires at t=1500
        clock.setTime(1499);
        assertEquals(1, counter.getCount());

        clock.setTime(1500);
        assertEquals(0, counter.getCount());
    }

    // -----------------------------------------------------------------------
    // 5. Boundary conditions
    // -----------------------------------------------------------------------

    @Test
    void boundaryAtExactWindowEdge() {
        FakeClock clock = new FakeClock(100);
        ExpiringCounter counter = new ExpiringCounter(500, clock);

        counter.record();           // event at t=100, expires at t=600

        clock.setTime(599);
        assertEquals(1, counter.getCount(), "Should still be visible one ms before expiry");

        clock.setTime(600);
        assertEquals(0, counter.getCount(), "Should be expired at exactly expiry time");
    }

    @Test
    void multipleEventsAtSameTimestampExpireAtOnce() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(1000, clock);

        // All three events share the same bucket (t=0)
        counter.record();
        counter.record();
        counter.record();
        assertEquals(3, counter.getCount());

        clock.setTime(1000);        // bucket expires
        assertEquals(0, counter.getCount());
    }

    // -----------------------------------------------------------------------
    // 6. Invalid constructor arguments
    // -----------------------------------------------------------------------

    @Test
    void zeroWindowMillisThrows() {
        FakeClock clock = new FakeClock(0);
        assertThrows(IllegalArgumentException.class,
                () -> new ExpiringCounter(0, clock));
    }

    @Test
    void negativeWindowMillisThrows() {
        FakeClock clock = new FakeClock(0);
        assertThrows(IllegalArgumentException.class,
                () -> new ExpiringCounter(-100, clock));
    }

    @Test
    void nullClockThrows() {
        assertThrows(NullPointerException.class,
                () -> new ExpiringCounter(1000, null));
    }

    // -----------------------------------------------------------------------
    // 7. Concurrency – threads calling both record() and getCount()
    // -----------------------------------------------------------------------

    @Test
    void concurrentRecordAndGetCountAreConsistent() throws InterruptedException {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(5000, clock);

        int numThreads = 20;
        int recordsPerThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numThreads);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < recordsPerThread; j++) {
                        counter.record();
                        // getCount() must never return a negative value
                        if (counter.getCount() < 0) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }

        start.countDown();
        done.await();

        assertEquals(0, errors.get(), "getCount() returned negative value");
        assertEquals(numThreads * recordsPerThread, counter.getCount(),
                "Total count should match number of records");
    }

    // -----------------------------------------------------------------------
    // 8a. windowMillis = 1 – single-threaded
    // -----------------------------------------------------------------------

    @Test
    void windowMillisOneSingleThreaded() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(1, clock);

        counter.record();           // event at t=0, expires at t=1
        assertEquals(1, counter.getCount());

        clock.setTime(1);           // expiry boundary
        assertEquals(0, counter.getCount());

        // New event at t=1 is visible
        counter.record();
        assertEquals(1, counter.getCount());

        clock.setTime(2);           // that event also expires
        assertEquals(0, counter.getCount());
    }

    // -----------------------------------------------------------------------
    // 8b. windowMillis = 1 – multi-threaded
    // -----------------------------------------------------------------------

    @Test
    void windowMillisOneMultiThreaded() throws InterruptedException {
        AtomicLong sharedTime = new AtomicLong(0);
        Clock clock = sharedTime::get;
        ExpiringCounter counter = new ExpiringCounter(1, clock);

        int numThreads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numThreads);

        for (int i = 0; i < numThreads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    counter.record();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        // All threads recorded at t=0; counter should reflect numThreads events
        assertEquals(numThreads, counter.getCount());

        // Advance clock so all events expire
        sharedTime.set(1);
        assertEquals(0, counter.getCount());
    }

    // -----------------------------------------------------------------------
    // 9. Non-consecutive events (gaps in time)
    // -----------------------------------------------------------------------

    @Test
    void nonConsecutiveEvents() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(1000, clock);

        clock.setTime(1);
        counter.record();           // event at t=1, expires at t=1001

        clock.setTime(5);
        counter.record();           // event at t=5, expires at t=1005

        assertEquals(2, counter.getCount());

        // t=1 event expires, t=5 still live
        clock.setTime(1001);
        assertEquals(1, counter.getCount());

        // t=5 event expires
        clock.setTime(1005);
        assertEquals(0, counter.getCount());
    }

    @Test
    void nonConsecutiveEventsLargeGap() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(100, clock);

        clock.setTime(10);
        counter.record();           // expires at t=110

        clock.setTime(500);
        counter.record();           // expires at t=600

        // At t=500 only the second event is within window
        assertEquals(1, counter.getCount());

        clock.setTime(600);
        assertEquals(0, counter.getCount());
    }

    // -----------------------------------------------------------------------
    // 10. Deletion of expired event from list + decrement of currEvents
    // -----------------------------------------------------------------------

    @Test
    void expiredEventIsRemovedAndCountDecremented() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(1000, clock);

        // Record multiple events in different buckets
        counter.record();           // t=0  → bucket 1 (numOfEvents=1)
        clock.setTime(200);
        counter.record();           // t=200 → bucket 2 (numOfEvents=1)
        clock.setTime(400);
        counter.record();           // t=400 → bucket 3 (numOfEvents=1)

        assertEquals(3, counter.getCount());

        // Expire bucket at t=0
        clock.setTime(1000);        // 0 + 1000 → expired
        assertEquals(2, counter.getCount());

        // Expire bucket at t=200
        clock.setTime(1200);
        assertEquals(1, counter.getCount());

        // Expire bucket at t=400
        clock.setTime(1400);
        assertEquals(0, counter.getCount());
    }

    @Test
    void expiredBucketWithMultipleEventsDecrementsCorrectly() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(1000, clock);

        // Five events in the same bucket
        for (int i = 0; i < 5; i++) {
            counter.record();
        }
        assertEquals(5, counter.getCount());

        // Expire the bucket: all 5 events should vanish together
        clock.setTime(1000);
        assertEquals(0, counter.getCount());
    }

    @Test
    void multipleExpiredBucketsRemovedAtOnce() {
        FakeClock clock = new FakeClock(0);
        ExpiringCounter counter = new ExpiringCounter(500, clock);

        // Three buckets, each with multiple events
        clock.setTime(0);
        counter.record();
        counter.record();           // bucket t=0, 2 events

        clock.setTime(100);
        counter.record();           // bucket t=100, 1 event

        clock.setTime(200);
        counter.record();
        counter.record();
        counter.record();           // bucket t=200, 3 events

        assertEquals(6, counter.getCount());

        // Jump far into the future so all three buckets expire simultaneously
        clock.setTime(1000);
        assertEquals(0, counter.getCount(), "All buckets must have been removed");
    }

    // -----------------------------------------------------------------------
    // Additional: concurrent expiry correctness
    // -----------------------------------------------------------------------

    @Test
    void concurrentRecordAndExpiryAreConsistent() throws InterruptedException {
        AtomicLong sharedTime = new AtomicLong(0);
        Clock clock = sharedTime::get;
        // Window of 100 ms
        ExpiringCounter counter = new ExpiringCounter(100, clock);

        int rounds = 5;
        int threadsPerRound = 10;
        int recordsPerThread = 50;

        for (int r = 0; r < rounds; r++) {
            final long base = r * 200L;
            sharedTime.set(base);

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadsPerRound);
            List<Thread> threads = new ArrayList<>();

            for (int i = 0; i < threadsPerRound; i++) {
                Thread t = new Thread(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < recordsPerThread; j++) {
                            counter.record();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                threads.add(t);
                t.start();
            }

            start.countDown();
            done.await();

            // All events in this round were recorded at 'base', which is
            // within the current window
            int countInWindow = counter.getCount();
            assertTrue(countInWindow >= threadsPerRound * recordsPerThread,
                    "Should include current round's events; got " + countInWindow);

            // Advance clock so all events in this round expire before next round
            sharedTime.set(base + 100);
        }

        // After all rounds, advance clock far enough so every event has expired
        sharedTime.set(rounds * 200L + 100L);
        assertEquals(0, counter.getCount(), "All events should have expired");
    }
}

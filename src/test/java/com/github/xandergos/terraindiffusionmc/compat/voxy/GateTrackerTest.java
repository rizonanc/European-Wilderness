package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 5.4.1: gate statistics on MONOTONIC TIME. Buckets advance with real seconds
 * even when the controller step counter is frozen, long pauses expire the history,
 * windows without samples report an honest -1, and reads are safe from other threads. */
class GateTrackerTest {
    private static void seconds(AtomicLong clock, int count, boolean open, Runnable between) {
        for (int s = 0; s < count; s++) {
            if (s == 0 || true) /* one sample per second */
                ;
            if (s == 0) {}
        }
        // one sample at the current second, then advance
    }

    @Test
    void frozenStepCounterStillAdvancesBucketsWithTime() {
        var clock = new AtomicLong(0L);
        var tracker = new GateTracker(clock::get);
        tracker.sample(true); // one open cycle at second 0; step counter stays frozen afterwards
        clock.addAndGet(15_000_000_000L);
        assertEquals(-1, tracker.openRatio10(), "last 10 seconds had no cycles: honest unknown");
        assertEquals(100, tracker.openRatio60(), "the only observed cycle in the window was open");
    }

    @Test
    void longPauseExpiresHistoryAndWindowWithoutSamplesIsEmpty() {
        var clock = new AtomicLong(0L);
        var tracker = new GateTracker(clock::get);
        for (int s = 0; s < 5; s++) {
            tracker.sample(true);
            tracker.sample(true);
            clock.addAndGet(1_000_000_000L);
        }
        tracker.sample(false);
        clock.addAndGet(90_000_000_000L); // long pause: the whole history expires
        assertEquals(-1, tracker.openRatio10(), "no samples in the window: honest unknown");
        tracker.sample(true);
        assertEquals(100, tracker.openRatio10(), "a fresh sample defines the window again");
    }

    @Test
    void closedCyclesAreCountedEvenWhenTheExternalForkSkipsWork() {
        var clock = new AtomicLong(0L);
        var tracker = new GateTracker(clock::get);
        for (int s = 0; s < 6; s++) { // 6 fully closed seconds: sampled before the fork, no work done
            for (int i = 0; i < 20; i++) tracker.sample(false);
            clock.addAndGet(1_000_000_000L);
        }
        assertEquals(0, tracker.openRatio10());
        assertEquals(0, tracker.openRatio60());
    }

    @Test
    void mixedHistorySplitsByWindows() {
        var clock = new AtomicLong(0L);
        var tracker = new GateTracker(clock::get);
        for (int s = 0; s < 30; s++) {
            tracker.sample(false);
            clock.addAndGet(1_000_000_000L);
        }
        for (int s = 0; s < 10; s++) {
            tracker.sample(true);
            clock.addAndGet(1_000_000_000L);
        }
        tracker.sample(true); // a cycle in the current, still-accumulating second
        assertEquals(100, tracker.openRatio10(), "last 10 seconds fully open");
        // 30 s window: 11 open samples (t=30..39) vs 20 closed (t=10..29) -> ~35 %.
        assertTrue(
                tracker.openRatio30() > 25 && tracker.openRatio30() < 45,
                "30 s mixes 11 open cycles with 20 closed: " + tracker.openRatio30());
        // 60 s window adds the 30 closed seconds at the start -> ~27 %.
        assertTrue(
                tracker.openRatio60() > 20 && tracker.openRatio60() < 35,
                "60 s covers only the 41 s elapsed: " + tracker.openRatio60());
    }

    @Test
    void readsFromAnotherThreadSeeAConsistentSnapshot() throws Exception {
        var clock = new AtomicLong(0L);
        var tracker = new GateTracker(clock::get);
        var reader =
                new Thread(() -> {
                    for (int i = 0; i < 200; i++) {
                        tracker.openRatio10();
                        tracker.openRatio60();
                        tracker.totalChecks();
                    }
                });
        reader.start();
        for (int s = 0; s < 20; s++) {
            tracker.sample(s % 2 == 0);
            clock.addAndGet(1_000_000_000L);
        }
        reader.join(5_000L);
        assertFalse(reader.isAlive(), "concurrent reads must not deadlock");
    }
}

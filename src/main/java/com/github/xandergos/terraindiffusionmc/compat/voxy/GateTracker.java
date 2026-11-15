package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.function.LongSupplier;

/** expire gate samples using monotonic time, including across pauses. */
public final class GateTracker {
    private static final int BUCKETS = 1280; // ≈21 minutes of history
    private final LongSupplier clock;
    private final long[] open = new long[BUCKETS];
    private final long[] checks = new long[BUCKETS];
    private long windowStart;
    private long origin = -1;
    private long curOpen, curChecks;

    public GateTracker() {
        this(System::nanoTime);
    }

    public GateTracker(LongSupplier clock) {
        this.clock = clock;
        windowStart = floorSecond(clock.getAsLong());
    }

    private static long floorSecond(long nanos) {
        return Math.floorDiv(nanos, 1_000_000_000L);
    }

    /** One controller cycle. Called BEFORE the external gate fork so closed cycles are counted. */
    public synchronized void sample(boolean gateOpen) {
        advance();
        if (origin < 0) origin = windowStart;
        curOpen += gateOpen ? 1 : 0;
        curChecks++;
    }

    private void advance() {
        long second = floorSecond(clock.getAsLong());
        long delta = second - windowStart;
        if (delta <= 0) return;
        if (delta >= BUCKETS) { // long pause: every bucket in history expired empty
            java.util.Arrays.fill(open, 0);
            java.util.Arrays.fill(checks, 0);
            curOpen = 0;
            curChecks = 0;
            windowStart = second;
            return;
        }
        for (long d = 0; d < delta; d++) {
            int at = (int) (windowStart % BUCKETS);
            totalOpen -= open[at];
            totalChecks -= checks[at];
            open[at] = curOpen;
            checks[at] = curChecks;
            totalOpen += curOpen;
            totalChecks += curChecks;
            curOpen = 0;
            curChecks = 0;
            windowStart++;
        }
    }

    private long ratio(int seconds) {
        advance();
        long second = floorSecond(clock.getAsLong());
        if (origin < 0 || second < origin) return -1;
        int count = (int) Math.min(second - origin + 1, Math.max(1, seconds));
        long openSum = 0, checkSum = 0;
        for (int i = 0; i < count; i++) {
            long s = second - i;
            if (s < origin) break;
            if (s == second) {
                openSum += curOpen;
                checkSum += curChecks;
            } else {
                int at = (int) (s % BUCKETS);
                openSum += open[at];
                checkSum += checks[at];
            }
        }
        return checkSum == 0 ? -1 : Math.round(100.0 * openSum / checkSum);
    }

    public synchronized long openRatio10() {
        return ratio(10);
    }

    public synchronized long openRatio30() {
        return ratio(30);
    }

    public synchronized long openRatio60() {
        return ratio(60);
    }

    public synchronized long totalChecks() {
        advance();
        return totalChecks + curChecks;
    }

    private long totalOpen, totalChecks;
}

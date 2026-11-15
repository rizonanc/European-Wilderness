package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import java.util.*;

/** Bounded recent percentiles; totals cover the recorded lifetime. */
public final class StageTimings {
    private final Map<String, Timing> stages = new LinkedHashMap<>();
    private final int capacity;

    public StageTimings() {
        this(256);
    }

    public StageTimings(int capacity) {
        if (capacity < 1 || capacity > 65536) throw new IllegalArgumentException("Invalid sample capacity");
        this.capacity = capacity;
    }

    public void record(String stage, long elapsedNanos) {
        Timing timing;
        synchronized (stages) {
            timing = stages.computeIfAbsent(stage, ignored -> new Timing(capacity));
        }
        timing.add(Math.max(0, elapsedNanos));
    }

    public Map<String, Snapshot> snapshot() {
        Map<String, Snapshot> result = new LinkedHashMap<>();
        synchronized (stages) {
            stages.forEach((name, timing) -> result.put(name, timing.snapshot()));
        }
        return Collections.unmodifiableMap(result);
    }

    public void clear() {
        synchronized (stages) {
            stages.clear();
        }
    }

    @Override
    public String toString() {
        return snapshot().toString();
    }

    public record Snapshot(
            long count,
            long totalNanos,
            long maxNanos,
            long p50RecentNanos,
            long p95RecentNanos,
            long p99RecentNanos,
            int recentSamples) {
        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "n=%d avg=%.2fms p50/p95/p99recent=%.2f/%.2f/%.2fms max=%.2fms",
                    count,
                    count == 0 ? 0 : totalNanos / (double) count / 1e6,
                    p50RecentNanos / 1e6,
                    p95RecentNanos / 1e6,
                    p99RecentNanos / 1e6,
                    maxNanos / 1e6);
        }
    }

    private static final class Timing {
        final long[] recent;
        long count, total, max;

        Timing(int capacity) {
            recent = new long[capacity];
        }

        synchronized void add(long ns) {
            recent[(int) (count % recent.length)] = ns;
            count++;
            total += ns;
            max = Math.max(max, ns);
        }

        synchronized Snapshot snapshot() {
            int n = (int) Math.min(count, recent.length);
            long[] sorted = Arrays.copyOf(recent, n);
            Arrays.sort(sorted);
            return new Snapshot(
                    count, total, max, percentile(sorted, .50), percentile(sorted, .95), percentile(sorted, .99), n);
        }

        private static long percentile(long[] sorted, double p) {
            return sorted.length == 0 ? 0 : sorted[(int) Math.ceil(sorted.length * p) - 1];
        }
    }
}

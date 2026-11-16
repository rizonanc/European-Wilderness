package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class StageTimingsTest {
    @Test
    void resetStartsAnEmptyMeasurementWindow() {
        var t = new StageTimings();
        t.record("old", 10);
        t.clear();
        assertTrue(t.snapshot().isEmpty());
        t.record("new", 20);
        assertEquals(1, t.snapshot().get("new").count());
    }

    @Test
    void nearestRankPercentilesDescribeOnlyTheBoundedRecentWindow() {
        var timings = new StageTimings(100);
        for (int i = 1; i <= 200; i++) timings.record("test", i);
        var s = timings.snapshot().get("test");
        assertEquals(150, s.p50RecentNanos());
        assertEquals(195, s.p95RecentNanos());
        assertEquals(199, s.p99RecentNanos());
        assertEquals(100, s.recentSamples());
        assertEquals(200, s.count());
    }

    @Test
    void testRecentPercentileIsBoundedWhileTotals() {
        var timings = new StageTimings();
        timings.record("stage", 10000);
        for (int i = 0; i < 300; i++) timings.record("stage", 10);
        var result = timings.snapshot().get("stage");
        assertEquals(301, result.count());
        assertEquals(13000, result.totalNanos());
        assertEquals(10000, result.maxNanos());
        assertEquals(10, result.p95RecentNanos());
    }

    @Test
    void concurrentRecordingDoesNotLoseSamples() throws Exception {
        var timings = new StageTimings();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++)
                tasks.add(pool.submit(() -> {
                    for (int n = 0; n < 1000; n++) timings.record("stage", 20);
                }));
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(4000, timings.snapshot().get("stage").count());
        assertEquals(80000, timings.snapshot().get("stage").totalNanos());
    }
}

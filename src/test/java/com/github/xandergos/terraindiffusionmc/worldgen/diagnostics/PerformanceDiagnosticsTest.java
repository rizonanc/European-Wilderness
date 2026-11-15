package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import com.github.xandergos.terraindiffusionmc.worldgen.preparation.FrameBudget;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.WorkTimeline.Stage.*;

class PerformanceDiagnosticsTest {
    @org.junit.jupiter.api.Test
    void runtimeCountersCanBeSerializedWithoutJdkModuleReflection() {
        var data = PerformanceDiagnostics.runtimeStatistics();
        String json = new com.google.gson.Gson().toJson(data);
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("process_cpu_time_ns"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("collection_time_ms"));
        org.junit.jupiter.api.Assertions.assertTrue(json.contains("used_bytes"));
    }

    @TempDir Path temp;

    @Test
    void timelineCapturesCompletedAndInFlightWorkOverlappingTheActualFrameWindow() {
        var clock = new AtomicLong(100);
        var trace = new WorkTimeline(8, clock::get);
        trace.enabled(true);
        long inference = trace.begin(INFERENCE_EXECUTE);
        clock.set(120);
        long io = trace.begin(RAW_IO_READ);
        clock.set(130);
        trace.end(io);
        clock.set(200);
        var events = trace.overlap(115, 140);
        assertEquals(2, events.size());
        assertTrue(events.get(0).active());
        assertEquals(25, events.get(0).overlapNanos());
        assertFalse(events.get(1).active());
        assertEquals(10, events.get(1).overlapNanos());
        trace.end(inference);
        assertTrue(trace.overlap(201, 220).isEmpty());
    }

    @Test
    void evictedOrRetiredTokensCannotCloseANewerSpanAndDisabledWorkStaysUnrecorded() {
        var clock = new AtomicLong(100);
        var trace = new WorkTimeline(1, clock::get);
        assertEquals(0, trace.begin(WATER_PLAN));
        trace.enabled(true);
        long old = trace.begin(WATER_PLAN);
        trace.end(old);
        clock.set(120);
        long current = trace.begin(LOD_INSERT);
        clock.set(130);
        trace.end(old);
        assertTrue(trace.overlap(120, 140).getFirst().active());
        trace.enabled(false);
        trace.enabled(true);
        clock.set(150);
        long newer = trace.begin(TREE_DECORATION);
        trace.end(current);
        assertTrue(trace.overlap(150, 160).getFirst().active());
        clock.set(155);
        trace.end(newer);
        assertFalse(trace.overlap(150, 160).getFirst().active());
        assertEquals(1, trace.capacity());
    }

    @Test
    void concurrentWorkersHaveSeparateSpansAndFixedHistory() throws Exception {
        var clock = new AtomicLong(100);
        var trace = new WorkTimeline(32, clock::get);
        trace.enabled(true);
        var ready = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            var worker =
                    new Thread(
                            () -> {
                                long token = trace.begin(WATER_PLAN);
                                ready.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException stop) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    trace.end(token);
                                }
                            },
                            "test-worker-" + i);
            workers.add(worker);
            worker.start();
        }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            clock.set(120);
            var active = trace.overlap(100, 120);
            assertEquals(4, active.size());
            assertTrue(active.stream().allMatch(WorkTimeline.Event::active));
            assertEquals(4, active.stream().map(WorkTimeline.Event::thread).distinct().count());
        } finally {
            release.countDown();
            for (var worker : workers) worker.join(5000);
        }
        for (int i = 0; i < 1000; i++) {
            long token = trace.begin(RAW_IO_READ);
            clock.incrementAndGet();
            trace.end(token);
        }
        assertEquals(32, trace.overlap(0, 2000).size());
    }

    @Test
    void logsRotateOnlyTheirOwnBackupAndRemainValidJsonLines() throws Exception {
        Path log = temp.resolve("performance.jsonl"), unrelated = temp.resolve("world.dat");
        Files.writeString(unrelated, "untouched");
        var writer = new RotatingPerformanceLog(log, 100);
        String line = "{\"event\":\"" + "a".repeat(30) + "\"}";
        for (int i = 0; i < 9; i++) writer.append(line);
        assertTrue(Files.size(log) <= 100);
        Path backup = temp.resolve("performance.jsonl.1");
        assertTrue(Files.size(backup) <= 100);
        try (var files = Files.list(temp)) {
            assertEquals(3, files.count());
        }
        assertEquals("untouched", Files.readString(unrelated));
        for (var file : List.of(log, backup))
            for (var entry : Files.readAllLines(file)) assertTrue(JsonParser.parseString(entry).isJsonObject());
        assertThrows(java.io.IOException.class, () -> writer.append("x".repeat(101)));
    }

    @Test
    void reportRetainsUnknownCausesAndGcSamplingWindowUncertainty() {
        var spike =
                new PerformanceDiagnostics.Spike(
                        1, 0, 100, 120, 20_000_000, 6_000_000, new FrameBudget.Decision(7_000_000, 12_000_000, 2));
        var gc = new PerformanceDiagnostics.GcWindow(50, 200, 1, 4, true);
        var event = new WorkTimeline.Event(INFERENCE_EXECUTE, "test", 80, 140, false, 20);
        var report = PerformanceDiagnostics.report(spike, List.of(event), List.of(gc));
        var json = JsonParser.parseString(new Gson().toJson(report)).getAsJsonObject();
        assertEquals(20, json.get("frame_gap_ms").getAsDouble());
        assertEquals(7, json.get("baseline_ms").getAsDouble());
        assertEquals(1, json.getAsJsonArray("overlapping_work").size());
        assertTrue(json.get("interpretation").getAsString().contains("not proven causation"));
        assertEquals(50, json.getAsJsonArray("gc_sampling_windows").get(0).getAsJsonObject().get("from").getAsLong());
        assertTrue(
                PerformanceDiagnostics.summary(spike, List.of(), List.of()).contains("нет зарегистрированной работы"));
        assertTrue(PerformanceDiagnostics.summary(spike, List.of(), List.of()).contains("нет доступной выборки"));
    }

    @Test
    void longRunningInferenceSurvivesManyShortLodEventsAndOverflowNeverEvictsActiveWork() {
        var clock = new AtomicLong(100);
        var trace = new WorkTimeline(8, clock::get);
        trace.enabled(true);
        long inference = trace.begin(INFERENCE_EXECUTE);
        for (int i = 0; i < 1000; i++) {
            long token = trace.begin(LOD_INSERT);
            clock.incrementAndGet();
            trace.end(token);
        }
        assertTrue(trace.overlap(1000, 1100).stream().anyMatch(e -> e.stage() == INFERENCE_EXECUTE && e.active()));
        trace.end(inference);
        var full = new WorkTimeline(1, clock::get);
        full.enabled(true);
        long active = full.begin(WATER_PLAN);
        assertEquals(0, full.begin(LOD_INSERT));
        assertEquals(1, full.dropped());
        clock.incrementAndGet();
        assertEquals(WATER_PLAN, full.overlap(100, 2000).getFirst().stage());
        full.end(active);
    }
}

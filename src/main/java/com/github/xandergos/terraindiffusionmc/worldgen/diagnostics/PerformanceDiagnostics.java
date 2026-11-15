package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import com.github.xandergos.terraindiffusionmc.worldgen.preparation.FrameBudget;
import com.google.gson.Gson;
import java.lang.management.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.WorkTimeline.Stage;

/** Best-effort overlap evidence. No filesystem, GC queries or formatting on the render hook. */
public final class PerformanceDiagnostics {
    private static final WorkTimeline TRACE = new WorkTimeline(512, System::nanoTime);
    private static final ArrayBlockingQueue<Spike> QUEUE = new ArrayBlockingQueue<>(8);
    private static final AtomicLong observed = new AtomicLong(),
            suppressed = new AtomicLong(),
            dropped = new AtomicLong(),
            saved = new AtomicLong();
    private static final AtomicReference<Supplier<Map<String, Object>>> SNAPSHOT = new AtomicReference<>();
    private static volatile long epoch;
    private static long nextCapture;
    private static volatile String recent = "Пиков в этой сессии ещё не записано", error = "";
    private static volatile Path path;
    private static volatile String previewStatus = "Client preview diagnostics not installed";

    public static void previewStatus(String value) {
        previewStatus = value;
    }

    public static String previewStatus() {
        return previewStatus;
    }

    private PerformanceDiagnostics() {}

    public static long begin(Stage stage) {
        return TRACE.begin(stage);
    }

    public static void end(long token) {
        TRACE.end(token);
    }

    public static void record(Stage stage, long from, long to) {
        TRACE.record(stage, from, to);
    }

    public static synchronized void install(Path destination) {
        if (path != null) return;
        path = destination;
        Thread worker = new Thread(() -> run(destination), "terrain-performance-diagnostics");
        worker.setDaemon(true);
        worker.setUncaughtExceptionHandler((thread, failure) ->
                DiagnosticErrors.failed(DiagnosticErrors.Code.SCH_WORKER, "DIAGNOSTICS_WORKER", failure));
        worker.start();
    }

    public static void active(boolean value) {
        if (value == TRACE.enabled()) return;
        epoch++;
        TRACE.enabled(value);
        QUEUE.clear();
        nextCapture = 0;
        if (value) recent = "Пиков в этой сессии ещё не записано";
    }

    public static void spike(long from, long to, long gap, long render, FrameBudget.Decision decision) {
        if (!TRACE.enabled()) return;
        observed.incrementAndGet();
        if (to < nextCapture) {
            suppressed.incrementAndGet();
            return;
        }
        nextCapture = to + 1_000_000_000L;
        if (!QUEUE.offer(new Spike(epoch, System.currentTimeMillis(), from, to, gap, render, decision)))
            dropped.incrementAndGet();
    }

    public static String status() {
        return "Диагностика "
                + (TRACE.enabled() ? "включена" : "ожидает активный TD-мир")
                + ": пиков="
                + observed.get()
                + ", записей="
                + saved.get()
                + ", объединено="
                + suppressed.get()
                + ", очередь пропущена="
                + dropped.get()
                + ". Журнал: "
                + path
                + (error.isEmpty() ? "" : ". Ошибка записи: " + error);
    }

    public static String recent() {
        return recent;
    }

    /** One pending export; all MXBean queries, formatting and IO stay on the existing diagnostics worker. */
    public static boolean requestSnapshot(Supplier<Map<String, Object>> capture) {
        return SNAPSHOT.compareAndSet(null, Objects.requireNonNull(capture));
    }

    static Map<String, Object> runtimeStatistics() {
        Map<String, Object> result = new LinkedHashMap<>();
        var memory = ManagementFactory.getMemoryMXBean();
        result.put("heap", memoryUsage(memory.getHeapMemoryUsage()));
        result.put("non_heap", memoryUsage(memory.getNonHeapMemoryUsage()));
        var buffers = new LinkedHashMap<String, Object>();
        for (var pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
            buffers.put(
                    pool.getName(),
                    Map.of(
                            "count",
                            pool.getCount(),
                            "used_bytes",
                            pool.getMemoryUsed(),
                            "capacity_bytes",
                            pool.getTotalCapacity()));
        result.put("buffer_pools", buffers);
        var collectors = new LinkedHashMap<String, Object>();
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans())
            collectors.put(
                    gc.getName(),
                    Map.of("collections", gc.getCollectionCount(), "collection_time_ms", gc.getCollectionTime()));
        result.put("gc_cumulative", collectors);
        var os = ManagementFactory.getOperatingSystemMXBean();
        result.put("available_processors", os.getAvailableProcessors());
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            result.put("process_cpu_load", extended.getProcessCpuLoad());
            result.put("process_cpu_time_ns", extended.getProcessCpuTime());
            result.put("committed_virtual_bytes", extended.getCommittedVirtualMemorySize());
        }
        result.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        result.put(
                "native_memory_note",
                "Direct/mapped pools are partial accounting, not total native RAM or VRAM; use NMT/JFR and process RSS separately.");
        return result;
    }

    private static Map<String, Long> memoryUsage(MemoryUsage usage) {
        return Map.of(
                "used_bytes", usage.getUsed(), "committed_bytes", usage.getCommitted(), "max_bytes", usage.getMax());
    }

    record Spike(
            long epoch, long epochMillis, long from, long to, long gap, long render, FrameBudget.Decision decision) {}

    record GcWindow(long from, long to, long collections, long timeMillis, boolean available) {}

    private static final class GcSampler {
        final List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        long last = System.nanoTime(), count, time;

        GcSampler() {
            for (var b : beans) {
                count += Math.max(0, b.getCollectionCount());
                time += Math.max(0, b.getCollectionTime());
            }
        }

        GcWindow sample() {
            long now = System.nanoTime(), nextCount = 0, nextTime = 0;
            boolean available = false;
            for (var b : beans) {
                long c = b.getCollectionCount(), t = b.getCollectionTime();
                if (c >= 0 && t >= 0) available = true;
                nextCount += Math.max(0, c);
                nextTime += Math.max(0, t);
            }
            var result =
                    new GcWindow(last, now, Math.max(0, nextCount - count), Math.max(0, nextTime - time), available);
            last = now;
            count = nextCount;
            time = nextTime;
            return result;
        }
    }

    private static void run(Path destination) {
        var output = new RotatingPerformanceLog(destination, 2 * 1024 * 1024);
        var gson = new Gson();
        GcSampler gc = null;
        Deque<GcWindow> windows = new ArrayDeque<>();
        long samplingEpoch = -1;
        while (true)
            try {
                DiagnosticErrors.drain(destination.getParent().resolve("terrain-diffusion"));
                Spike request = QUEUE.poll(250, TimeUnit.MILLISECONDS);
                var capture = SNAPSHOT.getAndSet(null);
                if (capture != null) {
                    var report = new LinkedHashMap<>(capture.get());
                    report.put("schema", 2);
                    report.put("kind", "benchmark_snapshot");
                    report.put("utc", Instant.now().toString());
                    report.put("monotonic_ns", System.nanoTime());
                    report.put("runtime", runtimeStatistics());
                    try {
                        output.append(gson.toJson(report));
                        saved.incrementAndGet();
                        error = "";
                    } catch (Exception failure) {
                        error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                        DiagnosticErrors.emit(
                                DiagnosticErrors.Code.IO_REPORT,
                                DiagnosticErrors.current("PERFORMANCE_SNAPSHOT"),
                                failure,
                                DiagnosticErrors.Outcome.DEGRADED,
                                "Snapshot unavailable; generation unchanged",
                                false);
                    }
                }
                if (samplingEpoch != epoch) {
                    gc = null;
                    windows.clear();
                    samplingEpoch = epoch;
                }
                if (!TRACE.enabled()) {
                    gc = null;
                    windows.clear();
                    continue;
                }
                if (gc == null) gc = new GcSampler();
                windows.addLast(gc.sample());
                while (windows.size() > 16) windows.removeFirst();
                if (request == null || request.epoch() != epoch) continue;
                var events = TRACE.overlap(request.from(), request.to());
                List<GcWindow> matching = new ArrayList<>();
                for (var window : windows)
                    if (window.to() > request.from() && window.from() < request.to()) matching.add(window);
                var report = report(request, events, matching);
                report.put("timeline_dropped_events", TRACE.dropped());
                recent = summary(request, events, matching);
                try {
                    output.append(gson.toJson(report));
                    saved.incrementAndGet();
                    error = "";
                } catch (Exception failure) {
                    error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    DiagnosticErrors.emit(
                            DiagnosticErrors.Code.IO_REPORT,
                            DiagnosticErrors.current("PERFORMANCE_TRACE"),
                            failure,
                            DiagnosticErrors.Outcome.DEGRADED,
                            "Trace unavailable; generation unchanged",
                            false);
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                DiagnosticErrors.emit(
                        DiagnosticErrors.Code.IO_REPORT,
                        DiagnosticErrors.current("PERFORMANCE_CAPTURE"),
                        failure,
                        DiagnosticErrors.Outcome.DEGRADED,
                        "Capture discarded; worker continues",
                        false);
            }
    }

    static Map<String, Object> report(Spike spike, List<WorkTimeline.Event> events, List<GcWindow> gc) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("utc", Instant.ofEpochMilli(spike.epochMillis()).toString());
        report.put(
                "interpretation",
                "Overlapping work is a candidate, not proven causation. GC counters cover sampling windows, not exact pauses. GPU and other mods are not instrumented. History is bounded.");
        report.put("window_start_ns", spike.from());
        report.put("window_end_ns", spike.to());
        report.put("frame_gap_ms", spike.gap() / 1e6);
        report.put("render_call_ms", spike.render() / 1e6);
        report.put("baseline_ms", spike.decision().baselineNanos() / 1e6);
        report.put("threshold_ms", spike.decision().thresholdNanos() / 1e6);
        report.put("pressure", spike.decision().pressure());
        report.put("overlapping_work", events);
        report.put("gc_sampling_windows", gc);
        Runtime runtime = Runtime.getRuntime();
        report.put("heap_used_at_capture_bytes", runtime.totalMemory() - runtime.freeMemory());
        report.put("heap_committed_at_capture_bytes", runtime.totalMemory());
        return report;
    }

    static String summary(Spike spike, List<WorkTimeline.Event> events, List<GcWindow> gc) {
        Map<Stage, Long> totals = new EnumMap<>(Stage.class);
        for (var event : events) totals.merge(event.stage(), event.overlapNanos(), Long::sum);
        StringBuilder text =
                new StringBuilder(
                        String.format(
                                Locale.ROOT,
                                "Последний пик: кадр %.1fмс, render %.1fмс, обычный %.1fмс. Совпало: ",
                                spike.gap() / 1e6,
                                spike.render() / 1e6,
                                spike.decision().baselineNanos() / 1e6));
        if (totals.isEmpty()) text.append("нет зарегистрированной работы мода");
        else
            totals.entrySet().stream()
                    .sorted(Map.Entry.<Stage, Long>comparingByValue().reversed())
                    .limit(6)
                    .forEach(e ->
                            text.append(String.format(Locale.ROOT, "%s %.1fмс; ", e.getKey(), e.getValue() / 1e6)));
        long count = gc.stream().mapToLong(GcWindow::collections).sum(),
                time = gc.stream().mapToLong(GcWindow::timeMillis).sum();
        if (gc.isEmpty() || gc.stream().noneMatch(GcWindow::available)) text.append(" GC: нет доступной выборки.");
        else text.append(" GC в пересекающихся окнах: ").append(count).append(" / ").append(time).append("мс.");
        text.append(" Совпадение не доказывает причину.");
        return text.toString();
    }
}

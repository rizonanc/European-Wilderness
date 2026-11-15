package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;

class DiagnosticErrorsTest {
    @TempDir Path temp;
    private final Context context =
            new Context("td-test", "TEST_FAILURE", "minecraft:overworld", "opaque-session", "tile=(-1,2)");

    @Test
    void preservesOriginalExceptionContextCodesAndBoundedDuplicateSummary() {
        var clock = new AtomicLong();
        var lines = new ArrayList<String>();
        var failures = new ArrayList<Throwable>();
        var d =
                new DiagnosticErrors(
                        clock::get,
                        (line, failure) -> {
                            lines.add(line);
                            failures.add(failure);
                        });
        var root = new IOException("simulated native failure");
        var wrapped = new CompletionException(root);
        for (int i = 0; i < 1000; i++) d.record(Code.INF_RUN, context, wrapped, Outcome.FAILED, "propagated", true);
        assertEquals(1, lines.size());
        assertSame(wrapped, failures.getFirst());
        assertSame(root, DiagnosticErrors.root(wrapped));
        assertTrue(lines.getFirst().contains("RequestId=td-test"));
        assertTrue(lines.getFirst().contains("TD-INF-002"));
        assertTrue(lines.getFirst().contains("Cause=IOException: simulated native failure"));
        assertEquals(64, d.snapshot().get("recent_events"));
        assertEquals(1, d.snapshot().get("pending_reports"));
        clock.set(TimeUnit.SECONDS.toNanos(16));
        d.record(Code.INF_RUN, context, wrapped, Outcome.FAILED, "propagated", true);
        assertTrue(lines.getLast().contains("SuppressedSincePrevious=999"));
        assertEquals(Code.values().length, Arrays.stream(Code.values()).map(c -> c.id).distinct().count());
    }

    @Test
    void recoverableWarningCannotSuppressTheNextCriticalFailure() {
        var lines = new ArrayList<String>();
        var d = new DiagnosticErrors(() -> 0L, (line, failure) -> lines.add(line));
        d.record(Code.SCH_TASK, context, new IOException("optional prefetch"), Outcome.DEGRADED, "deferred", false);
        d.record(Code.SCH_TASK, context, new IOException("required failure"), Outcome.FAILED, "propagated", true);
        assertEquals(2, lines.size());
        assertTrue(lines.getLast().contains("Result=FAILED"));
        assertEquals(1, d.snapshot().get("pending_reports"));
    }

    @Test
    void reportsKeepCauseAndTraceButRedactPrivatePathsAndCredentials() throws Exception {
        var d = new DiagnosticErrors(System::nanoTime, (line, failure) -> {});
        d.record(
                Code.HYD_PLAN,
                context,
                new IOException("C:\\Users\\PrivateName\\secret-world\\file.dat token=abc123"),
                Outcome.FAILED,
                "password=qwerty",
                true);
        d.writeNext(temp);
        Path report;
        try (var files = Files.list(temp)) {
            report = files.findFirst().orElseThrow();
        }
        String text = Files.readString(report);
        var json = JsonParser.parseString(text).getAsJsonObject();
        assertTrue(Files.size(report) <= 128 * 1024);
        assertFalse(text.contains("PrivateName"));
        assertFalse(text.contains("abc123"));
        assertFalse(text.contains("qwerty"));
        assertTrue(json.get("stack_trace").getAsString().contains("IOException"));
        assertTrue(text.contains("TEST_FAILURE"));
    }

    @Test
    void failureOfReportWriterCannotReplaceOriginalErrorOrDeleteOtherFiles() throws Exception {
        var lines = new ArrayList<String>();
        var d = new DiagnosticErrors(System::nanoTime, (line, failure) -> lines.add(line));
        Path blocked = temp.resolve("not-directory");
        Files.writeString(blocked, "user data");
        d.record(Code.SCH_WORKER, context, new RuntimeException("worker broke"), Outcome.FAILED, "no result", true);
        assertDoesNotThrow(() -> d.writeNext(blocked));
        assertEquals("user data", Files.readString(blocked));
        assertTrue(lines.getFirst().contains("worker broke"));
        assertTrue(lines.getLast().contains("TD-IO-003"));
        assertTrue(lines.getLast().contains("Result=DEGRADED"));
    }

    @Test
    void concurrentErrorsHaveBoundedQueueRingAndOwnedReportRetention() throws Exception {
        var clock = new AtomicLong();
        var lines = new CopyOnWriteArrayList<String>();
        var d = new DiagnosticErrors(clock::get, (line, t) -> lines.add(line));
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 1000; i++)
                jobs.add(
                        pool.submit(() ->
                                d.record(
                                        Code.CAC_CRC,
                                        context,
                                        new IOException("CRC"),
                                        Outcome.DEGRADED,
                                        "recompute",
                                        false)));
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, lines.size());
        assertEquals(64, d.snapshot().get("recent_events"));
        Path other = temp.resolve("do-not-delete.json");
        Files.writeString(other, "untouched");
        for (int i = 0; i < 20; i++) {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(16));
            d.record(
                    Code.WGN_INVARIANT, context, new IllegalStateException("invariant"), Outcome.FAILED, "abort", true);
            d.writeNext(temp);
        }
        try (var files = Files.list(temp)) {
            assertEquals(11, files.count());
        }
        assertEquals("untouched", Files.readString(other));
    }

    @Test
    void cancellationIsRecognizedThroughFutureWrappersAndScopesAreRestored() {
        assertTrue(cancelled(new ExecutionException(new CancellationException("normal"))));
        var previous = DiagnosticErrors.current("before");
        try (var outer = DiagnosticErrors.scope(context)) {
            assertEquals("td-test", DiagnosticErrors.current("next").requestId());
            try (var inner = DiagnosticErrors.scope(new Context("child", "work", "test:dim", "other", "unknown"))) {
                assertEquals("child", DiagnosticErrors.current("x").requestId());
            }
            assertEquals("td-test", DiagnosticErrors.current("y").requestId());
        }
        assertNotEquals("td-test", DiagnosticErrors.current("after").requestId());
    }
}

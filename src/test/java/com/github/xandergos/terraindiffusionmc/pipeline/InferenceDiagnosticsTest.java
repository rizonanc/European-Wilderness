package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;

class InferenceDiagnosticsTest {
    @Test
    void testFailedFutureRetainsException() throws Exception {
        var context =
                new DiagnosticErrors.Context("td-correlation", "region", "test:dimension", "opaque", "tile=(-4,7)");
        var original = new IllegalArgumentException("fixture inference failed");
        try (var worker = new InferenceThread("diagnostic-test")) {
            try (var scope = DiagnosticErrors.scope(context)) {
                assertEquals(
                        "td-correlation",
                        worker.submit(() -> DiagnosticErrors.current("execute").requestId()).get(5, TimeUnit.SECONDS));
                var failed =
                        worker.submit(() -> {
                            throw original;
                        });
                assertSame(
                        original,
                        assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS)).getCause());
            }
            assertNotEquals(
                    "td-correlation",
                    worker.submit(() -> DiagnosticErrors.current("next").requestId()).get(5, TimeUnit.SECONDS));
        }
    }
}

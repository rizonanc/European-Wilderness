package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import com.sun.management.ThreadMXBean;

/** Pure composition cost with the same read-only guard/correction lookups as rendering.
 * Excludes Voxy meshing, GPU uploads and neural inference. */
public final class CoarseComposeProbe {
    public static void main(String[] args) throws Exception {
        var guard = new LodChunkGuard(Path.of("qa-unused-region"));
        guard.markLoaded(0, 0);
        guard.renderRegionReady(2048, 0);
        guard.prepareRenderRegions(1);
        var coverage = new CoarseCoverage();
        coverage.mark(0, 0);
        var corrections = new CoarseLodCorrections(262144);
        corrections.add(List.of(new CoarseLodCorrections.Anchor(2064, 16, 40)));
        var field = new CoarseLodPatch.Surface[1024];
        Arrays.fill(field, new CoarseLodPatch.Surface(128, (short) 209, 20));
        long[] target = new long[32768], palette = new long[13];
        for (int i = 1; i < 13; i++) palette[i] = (long) i << 27;
        var thread = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        thread.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();
        CoarseSectionComposer.Protected exact =
                (x, z) ->
                        guard.knownRealColumn(x, z)
                                || guard.savedRealColumn(x, z)
                                || coverage.contains(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        java.util.function.IntFunction<long[]> colors = b -> palette;
        java.util.function.LongPredicate air = v -> (v & (((1L << 20) - 1) << 27)) == 0;
        CoarseSectionComposer.Adjustment correction = corrections::adjustment, zero = (x, z) -> 0;
        for (int n = 0; n < 500; n++)
            CoarseSectionComposer.fill(4, 4, 0, 0, -64, 1871, field, null, target, exact, colors, air, correction);
        List<Map<String, Object>> modes = new ArrayList<>();
        for (var mode : List.of(zero, correction)) {
            long allocated = thread.getThreadAllocatedBytes(id), started = System.nanoTime(), checksum = 0;
            int iterations = 2000;
            for (int n = 0; n < iterations; n++)
                checksum +=
                        CoarseSectionComposer.fill(4, 4, 0, 0, -64, 1871, field, null, target, exact, colors, air, mode)
                                        .nonAir()
                                + target[n & 32767];
            long duration = System.nanoTime() - started, bytes = thread.getThreadAllocatedBytes(id) - allocated;
            modes.add(
                    Map.of(
                            "correction",
                            mode == correction,
                            "iterations",
                            iterations,
                            "msPerSection",
                            duration / 1e6 / iterations,
                            "bytesPerSection",
                            bytes / (double) iterations,
                            "checksum",
                            checksum));
        }
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(modes);
        Files.writeString(Path.of(args[0]), json + "\n");
        System.out.println(json);
    }
}

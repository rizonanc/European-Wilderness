package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Same deterministic raw-window source, sampled with and without the new window cache. */
public final class DensityReadAllocationProbe {
    private static double sample(WorldSession session, DensitySampler.Lookup lookup, DensitySampler cache, int count) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            int x = -16 + (i & 15), z = -16 + ((i >>> 4) & 15), y = i % 320;
            sum +=
                    cache == null
                            ? lookup.fetch(-16, -16, 0, 0).surfaceYAt(x + 16, z + 16, 2) - y
                            : cache.sample(session, lookup, x, y, z);
        }
        return sum;
    }

    public static void main(String[] args) {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var session = WorldSession.activate(GenerationProfile.defaultsFor(2, "probe", "cpu", "cpu"), 7);
        var calls = new AtomicInteger();
        DensitySampler.Lookup lookup =
                (z, x, ez, ex) -> {
                    calls.incrementAndGet();
                    short[][] h = new short[16][16], b = new short[16][16];
                    for (var row : h) Arrays.fill(row, (short) 600);
                    return new HeightmapData(h, b, 16, 16);
                };
        sample(session, lookup, null, 20000);
        var cache = new DensitySampler();
        sample(session, lookup, cache, 20000);
        long thread = Thread.currentThread().threadId();
        int count = 100000;
        StringBuilder result =
                new StringBuilder(
                        "{\"scope\":\"synthetic 16x16 raw-window read, not game FPS\",\"samples\":"
                                + count
                                + ",\"trials\":[");
        for (int pass = 0; pass < 2; pass++) {
            var sampler = pass == 0 ? null : new DensitySampler();
            calls.set(0);
            long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
            double checksum = sample(session, lookup, sampler, count);
            long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(thread) - before;
            if (pass > 0) result.append(',');
            result.append("{\"cached\":")
                    .append(pass == 1)
                    .append(",\"source_calls\":")
                    .append(calls.get())
                    .append(",\"allocated_bytes\":")
                    .append(allocated)
                    .append(",\"elapsed_ns\":")
                    .append(elapsed)
                    .append(",\"checksum\":")
                    .append(checksum)
                    .append('}');
        }
        WorldSession.deactivate(session);
        System.out.println(result.append("]}"));
    }
}

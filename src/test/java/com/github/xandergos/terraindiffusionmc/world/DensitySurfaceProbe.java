package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.*;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Offline immutable automatic-water density workload; no game or model inference. */
public final class DensitySurfaceProbe {
    static double run(DensitySampler sampler, WorldSession session, DensitySampler.Lookup lookup, int count) {
        double sum = 0;
        for (int i = 0; i < count; i++)
            sum += sampler.sample(session, lookup, -16 + (i & 31), -64 + (i / 1024 % 512), -16 + (i / 32 & 31));
        return sum;
    }

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[0]);
        var session = WorldSession.activate(GenerationProfile.defaultsFor(2, "model", "cpu", "cpu"), 7);
        session.attachRepository(
                new TerrainRepository(
                        new DiskTerrainStore(dir.resolve("raw")),
                        key -> {
                            short[] h = new short[key.tileSize() * key.tileSize()], b = new short[h.length];
                            Arrays.fill(h, (short) 600);
                            return new TerrainTile(key, h, b);
                        },
                        256,
                        4L * 1024 * 1024,
                        1024L * 1024 * 1024,
                        session));
        try (var water = DensityWaterFixture.create(session, dir.resolve("water"))) {
            session.attachAutomaticWater(water);
            var sampler = new DensitySampler();
            DensitySampler.Lookup lookup =
                    (z, x, ez, ex) -> {
                        short[][] h = new short[16][16], b = new short[16][16];
                        int[][] s = new int[16][16];
                        for (var row : h) Arrays.fill(row, (short) 600);
                        for (var row : s) Arrays.fill(row, 200);
                        return new HeightmapData(h, b, 16, 16, null, s);
                    };
            run(sampler, session, lookup, 150000);
            var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long[] time = new long[7], bytes = new long[7];
            double sum = 0;
            for (int i = 0; i < 7; i++) {
                long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),
                        start = System.nanoTime();
                sum = run(sampler, session, lookup, 2000000);
                time[i] = System.nanoTime() - start;
                bytes[i] = bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
            }
            Arrays.sort(time);
            Arrays.sort(bytes);
            System.out.printf(
                    Locale.ROOT,
                    "calls=2000000 medianMs=%.3f allocatedBytes=%d checksum=%.0f%n",
                    time[3] / 1e6,
                    bytes[3],
                    sum);
        } finally {
            WorldSession.deactivate(session);
        }
    }
}

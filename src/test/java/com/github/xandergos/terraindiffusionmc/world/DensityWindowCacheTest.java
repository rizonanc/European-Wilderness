package com.github.xandergos.terraindiffusionmc.world;

import static org.junit.jupiter.api.Assertions.*;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainSurfaceRegion;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

class DensityWindowCacheTest {
    private WorldSession session;

    @BeforeEach
    void activate() {
        session = WorldSession.activate(GenerationProfile.defaultsFor(2, "model", "cpu", "cpu"), 17);
    }

    @AfterEach
    void retire() {
        WorldSession.deactivate(WorldSession.current());
    }

    static HeightmapData data(int sx, int sz, int offset, boolean complete) throws Exception {
        short[][] height = new short[16][16], biome = new short[16][16];
        int[][] surface = new int[16][16];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                height[z][x] = 600;
                surface[z][x] = 200 + offset + sx + x + sz + z;
            }
        if (complete) return new HeightmapData(height, biome, 16, 16, null, surface);
        var constructor = HeightmapData.class.getDeclaredConstructor(
                short[][].class,
                short[][].class,
                int.class,
                int.class,
                float[].class,
                MountainSurfaceRegion.class,
                boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(height, biome, 16, 16, null, null, false);
    }

    private static HeightmapData ready(int sx, int sz, int offset) {
        try {
            return data(sx, sz, offset, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void allVerticalSamplesAndFillReuseCompleteWindowsWithUnchangedDensityAtNegativeCoordinates() {
        AtomicInteger calls = new AtomicInteger();
        DensitySampler.Lookup lookup = (z, x, ez, ex) -> {
            calls.incrementAndGet();
            return ready(x, z, 0);
        };
        var density = new DensitySampler();
        for (int y = -64; y < 320; y++) assertEquals(200 - 1 - 17 - y, density.sample(session, lookup, -1, y, -17));
        assertEquals(1, calls.get());
        double[] values = new double[512];
        var window = density.window(session, lookup, -16, -32);
        for (int i = 0; i < values.length; i++)
            values[i] = density.at(session, window, -16, -32, -16 + i % 16, i / 16, -32 + i / 16 % 16);
        for (int i = 0; i < values.length; i++)
            assertEquals(200 + (-16 + i % 16) + (-32 + i / 16 % 16) - i / 16, values[i]);
        assertEquals(1, calls.get(), "fill and sample share the same 16x16 cache");
    }

    @Test
    void testCacheIsBoundedAndInvalidatesOnSession() {
        var cache = new DensityWindowCache();
        var id = UUID.randomUUID();
        var source = new Object();
        for (int i = 0; i < 5; i++) cache.put(id, source, i * 16, 0, ready(i * 16, 0, 0));
        assertEquals(4, cache.size());
        assertNull(cache.get(id, source, 0, 0));
        assertNotNull(cache.get(id, source, 64, 0));
        assertNull(cache.get(UUID.randomUUID(), source, 64, 0));
        assertEquals(0, cache.size());
        cache.put(id, source, 0, 0, ready(0, 0, 0));
        assertNull(cache.get(id, new Object(), 0, 0));
        AtomicInteger calls = new AtomicInteger();
        var density = new DensitySampler();
        DensitySampler.Lookup lookup = (z, x, ez, ex) -> ready(x, z, calls.incrementAndGet());
        assertEquals(101, density.sample(session, lookup, 0, 100, 0));
        assertEquals(101, density.sample(session, lookup, 0, 100, 0));
        session = WorldSession.activate(GenerationProfile.defaultsFor(2, "model", "cpu", "cpu"), 17);
        assertEquals(102, density.sample(session, lookup, 0, 100, 0));
        DensitySampler.Lookup changed = (z, x, ez, ex) -> ready(x, z, 9);
        assertEquals(109, density.sample(session, changed, 0, 100, 0));
    }

    @Test
    void fallbackAndNullResultsAreNeverPinnedAfterTheRealSourceBecomesAvailable() throws Exception {
        var cache = new DensityWindowCache();
        var id = UUID.randomUUID();
        var source = new Object();
        cache.put(id, source, 0, 0, data(0, 0, 0, false));
        assertEquals(0, cache.size());
        AtomicInteger calls = new AtomicInteger();
        var fallback = data(0, 0, 0, false);
        DensitySampler.Lookup lookup = (z, x, ez, ex) -> calls.incrementAndGet() == 1 ? fallback : ready(x, z, 0);
        var density = new DensitySampler();
        density.sample(session, lookup, 0, 100, 0);
        assertEquals(100, density.sample(session, lookup, 0, 100, 0));
        density.sample(session, lookup, 0, 100, 0);
        assertEquals(2, calls.get());
        assertEquals(-100, density.sample(session, (z, x, ez, ex) -> null, 0, 100, 0));
        assertEquals(105, density.sample(session, (z, x, ez, ex) -> ready(x, z, 5), 0, 100, 0));
    }

    @Test
    void testDynamicWaterOverlayIsEvaluatedOnEverySample() {
        DensitySampler.Lookup lookup = (z, x, ez, ex) -> ready(x, z, 0);
        AtomicInteger waterHeight = new AtomicInteger(180);
        WorldSession.current().attachRiverOverlay(new WorldSession.RiverOverlay() {
            public int carvedHeight(int x, int z, int raw) {
                return waterHeight.get();
            }

            public int waterY(int x, int z) {
                return Integer.MIN_VALUE;
            }
        });
        var density = new DensitySampler();
        assertEquals(80, density.sample(session, lookup, 0, 100, 0));
        waterHeight.set(170);
        assertEquals(70, density.sample(session, lookup, 0, 100, 0));
    }

    @Test
    void retirementDuringALoadCannotPublishOrServeTheLoadedWindow() {
        DensitySampler.Lookup lookup = (z, x, ez, ex) -> {
            WorldSession.deactivate(WorldSession.current());
            return ready(x, z, 0);
        };
        var density = new DensitySampler();
        assertThrows(IllegalStateException.class, () -> density.sample(session, lookup, 0, 100, 0));
        session = WorldSession.activate(GenerationProfile.defaultsFor(2, "model", "cpu", "cpu"), 18);
        assertEquals(102, density.sample(session, (z, x, ez, ex) -> ready(x, z, 2), 0, 100, 0));
    }

    @Test
    void testParallelGenerationThreadsCannotReplaceEachOthersWindows() throws Exception {
        var density = new DensitySampler();
        var barrier = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        DensitySampler.Lookup lookup = (z, x, ez, ex) -> {
            calls.incrementAndGet();
            return ready(x, z, 0);
        };
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Future<Double>> results = new ArrayList<>();
            for (int thread = 0; thread < 2; thread++) {
                final int x = thread == 0 ? -1 : 1024;
                results.add(workers.submit(() -> {
                    barrier.await();
                    double sum = 0;
                    for (int i = 0; i < 1000; i++) sum += density.sample(session, lookup, x, 100, 0);
                    return sum;
                }));
            }
            assertEquals(99000, results.get(0).get());
            assertEquals(1124000, results.get(1).get());
            assertEquals(2, calls.get());
        }
    }
}

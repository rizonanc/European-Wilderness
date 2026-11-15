package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.*;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DensitySurfaceCacheTest {
    @TempDir Path temp;

    @AfterEach
    void retire() {
        WorldSession.deactivate(WorldSession.current());
    }

    @Test
    void distinguishesEveryColumnAtNegativeEdgesAndAcceptsAllIntegerHeights() {
        var cache = new DensitySurfaceCache();
        var world = UUID.randomUUID();
        var water = UUID.randomUUID();
        var lookup = new Object();
        for (int z = -32; z < 0; z++) for (int x = -32; x < 0; x++) cache.put(world, water, lookup, x, z, x * 99 + z);
        for (int z = -32; z < 0; z++)
            for (int x = -32; x < 0; x++) assertEquals(x * 99 + z, cache.get(world, water, lookup, x, z));
        assertEquals(DensitySurfaceCache.MISSING, cache.get(world, water, lookup, 0, 0));
        cache.put(world, water, lookup, -32, -32, Integer.MIN_VALUE);
        cache.put(world, water, lookup, -31, -32, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, cache.get(world, water, lookup, -32, -32));
        assertEquals(Integer.MAX_VALUE, cache.get(world, water, lookup, -31, -32));
    }

    @Test
    void boundedWindowsAreInvalidatedByWorldWaterAndLookupIdentity() {
        var cache = new DensitySurfaceCache();
        var world = UUID.randomUUID();
        var water = UUID.randomUUID();
        var lookup = new Object();
        for (int x = 0; x < 5; x++) cache.put(world, water, lookup, x * 16, 0, x);
        assertEquals(DensitySurfaceCache.MISSING, cache.get(world, water, lookup, 0, 0));
        assertEquals(4, cache.get(world, water, lookup, 64, 0));
        assertEquals(DensitySurfaceCache.MISSING, cache.get(UUID.randomUUID(), water, lookup, 64, 0));
        cache.put(world, water, lookup, 0, 0, 17);
        assertEquals(DensitySurfaceCache.MISSING, cache.get(world, UUID.randomUUID(), lookup, 0, 0));
        cache.put(world, water, lookup, 0, 0, 17);
        assertEquals(DensitySurfaceCache.MISSING, cache.get(world, water, new Object(), 0, 0));
    }

    private WorldSession automatic() throws Exception {
        var session = WorldSession.activate(GenerationProfile.defaultsFor(2, "model", "cpu", "cpu"), 7);
        session.attachRepository(
                new TerrainRepository(
                        new DiskTerrainStore(temp.resolve("raw")),
                        key -> {
                            short[] heights = new short[key.tileSize() * key.tileSize()],
                                    biomes = new short[heights.length];
                            Arrays.fill(heights, (short) 600);
                            return new TerrainTile(key, heights, biomes);
                        },
                        256,
                        4L * 1024 * 1024,
                        1024L * 1024 * 1024,
                        session));
        session.attachAutomaticWater(DensityWaterFixture.create(session, temp.resolve("water")));
        return session;
    }

    @Test
    void automaticSurfaceMatchesUncachedSamplingAndManualOverlayStillChangesImmediately() throws Exception {
        var session = automatic();
        var sampler = new DensitySampler();
        AtomicInteger fetches = new AtomicInteger();
        DensitySampler.Lookup lookup =
                (z, x, ez, ex) -> {
                    fetches.incrementAndGet();
                    try {
                        return DensityWindowCacheTest.data(x, z, 0, true);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                };
        double[] expected = new double[256];
        var raw = lookup.fetch(-16, -16, 0, 0);
        for (int z = -16; z < 0; z++)
            for (int x = -16; x < 0; x++)
                expected[(z + 16) * 16 + x + 16] = sampler.at(session, raw, -16, -16, x, 0, z);
        for (int y = -64; y < 320; y++)
            for (int z = -16; z < 0; z++)
                for (int x = -16; x < 0; x++)
                    assertEquals(expected[(z + 16) * 16 + x + 16] - y, sampler.sample(session, lookup, x, y, z));
        assertEquals(2, fetches.get());
        AtomicInteger surface = new AtomicInteger(180);
        session.attachRiverOverlay(
                new WorldSession.RiverOverlay() {
                    public int carvedHeight(int x, int z, int rawY) {
                        return surface.get();
                    }

                    public int waterY(int x, int z) {
                        return Integer.MIN_VALUE;
                    }
                });
        assertEquals(80, sampler.sample(session, lookup, -1, 100, -1));
        surface.set(170);
        assertEquals(70, sampler.sample(session, lookup, -1, 100, -1));
        session.attachRiverOverlay(session.automaticWater());
        assertEquals(expected[255] - 100, sampler.sample(session, lookup, -1, 100, -1));
        WorldSession.deactivate(session);
        assertThrows(IllegalStateException.class, () -> sampler.sample(session, lookup, -1, 100, -1));
    }

    @Test
    void incompleteAutomaticSourceCannotFreezeDensityBeforeCompleteDataArrives() throws Exception {
        var session = automatic();
        var sampler = new DensitySampler();
        var fallback = DensityWindowCacheTest.data(0, 0, 0, false);
        AtomicInteger calls = new AtomicInteger();
        DensitySampler.Lookup lookup =
                (z, x, ez, ex) -> {
                    if (calls.incrementAndGet() == 1) return fallback;
                    try {
                        return DensityWindowCacheTest.data(x, z, 0, true);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                };
        sampler.sample(session, lookup, 0, 100, 0);
        double complete = sampler.sample(session, lookup, 0, 100, 0);
        assertEquals(complete, sampler.sample(session, lookup, 0, 100, 0));
        assertEquals(2, calls.get());
    }
}

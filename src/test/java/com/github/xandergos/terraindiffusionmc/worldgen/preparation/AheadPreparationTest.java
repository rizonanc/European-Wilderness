package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import static org.junit.jupiter.api.Assertions.*;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class AheadPreparationTest {
    private static TerrainTileKey key(int x) {
        return TerrainTileKey.ofTile(7, x, 0, 512, "test");
    }

    @Test
    void testStationaryPlayerPreparesCurrent() {
        var values = WaterPrefetchPolicy.candidates(-1, -1, 0, 0, 512);
        assertEquals(3, values.size());
        assertEquals(new TilePrefetchPolicy.TileCoord(-1, -1), values.getFirst());
        assertTrue(values.contains(new TilePrefetchPolicy.TileCoord(0, -1)));
        assertTrue(values.contains(new TilePrefetchPolicy.TileCoord(-1, 0)));
    }

    @Test
    void testMotionIsBoundedAndChangesDirection() {
        var east = WaterPrefetchPolicy.candidates(500, 250, 80, 0, 512);
        var west = WaterPrefetchPolicy.candidates(500, 250, -80, 0, 512);
        assertTrue(east.size() <= 3);
        assertTrue(west.size() <= 3);
        assertTrue(east.stream().skip(1).allMatch(c -> c.x() > 0));
        assertTrue(west.stream().skip(1).allMatch(c -> c.x() < 0));
        assertEquals(
                List.of(new TilePrefetchPolicy.TileCoord(0, 0)),
                WaterPrefetchPolicy.candidates(500, 250, 1000, 0, 512));
        int slow = WaterPrefetchPolicy.candidates(10, 250, 3, 0, 512).stream()
                .mapToInt(TilePrefetchPolicy.TileCoord::x)
                .max()
                .orElseThrow();
        int fast = WaterPrefetchPolicy.candidates(10, 250, 180, 0, 512).stream()
                .mapToInt(TilePrefetchPolicy.TileCoord::x)
                .max()
                .orElseThrow();
        assertTrue(fast >= slow);
    }

    @Test
    void testSamplerIncludesCurrentWhileStationary() {
        var desired = new AtomicReference<List<TerrainTileKey>>(List.of());
        var healthy = new AtomicBoolean();
        var sampler = new TilePrefetchSampler(
                7,
                "fp",
                512,
                (keys, ok) -> {
                    desired.set(keys);
                    healthy.set(ok);
                },
                true);
        for (int i = 0; i < 20; i++) sampler.tick(10, 10, 10, true);
        assertFalse(desired.get().isEmpty());
        assertTrue(healthy.get());
        sampler.tick(70, 10, 10, true);
        assertTrue(desired.get().isEmpty());
        assertFalse(healthy.get());
        for (int i = 0; i < 20; i++) sampler.tick(10, 10, 10, true);
        assertFalse(desired.get().isEmpty());
        sampler.stop();
        assertTrue(desired.get().isEmpty());
        assertFalse(healthy.get());
    }

    @Test
    void testIncrementalServiceDefersToForeground() throws Exception {
        var first = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var busy = new AtomicBoolean();
        var stages = new AtomicInteger();
        var work = new TilePrefetchService.TileWork() {
            public boolean ready(TerrainTileKey k) {
                return false;
            }

            public void prepare(TerrainTileKey k) {
                fail("must use incremental entry point");
            }

            public boolean prepareNext(TerrainTileKey k, BooleanSupplier allowed) throws Exception {
                assertTrue(allowed.getAsBoolean());
                if (stages.incrementAndGet() == 1) {
                    first.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return false;
                }
                second.countDown();
                return true;
            }
        };
        var service = new TilePrefetchService(work, () -> true, busy::get, () -> false);
        try {
            service.updateCandidates(List.of(key(0)), true);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            busy.set(true);
            release.countDown();
            assertFalse(second.await(250, TimeUnit.MILLISECONDS));
            assertEquals(1, stages.get());
            busy.set(false);
            service.signalStateChanged();
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertTrue(service.awaitIdle(5, TimeUnit.SECONDS));
            assertEquals(2, stages.get());
            assertEquals(1, service.metrics().completed());
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void testReplacementCancelsUnstartedStages() throws Exception {
        var first = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var next = new CountDownLatch(1);
        var calls = new CopyOnWriteArrayList<TerrainTileKey>();
        var revoked = new AtomicBoolean();
        var work = new TilePrefetchService.TileWork() {
            public boolean ready(TerrainTileKey k) {
                return false;
            }

            public void prepare(TerrainTileKey k) {
                fail("must use incremental entry point");
            }

            public boolean prepareNext(TerrainTileKey k, BooleanSupplier allowed) throws Exception {
                calls.add(k);
                if (k.equals(key(0))) {
                    first.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    revoked.set(!allowed.getAsBoolean());
                    return false;
                }
                next.countDown();
                return true;
            }
        };
        var service = new TilePrefetchService(work, () -> true, () -> false, () -> false);
        try {
            service.updateCandidates(List.of(key(0)), true);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            service.updateCandidates(List.of(key(-1)), true);
            release.countDown();
            assertTrue(next.await(5, TimeUnit.SECONDS));
            assertTrue(service.awaitIdle(5, TimeUnit.SECONDS));
            assertTrue(revoked.get());
            assertEquals(List.of(key(0), key(-1)), calls);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void testManualPreparationAndUnhealthyTicksPreventAdmission() throws Exception {
        var manual = new AtomicBoolean(true);
        var started = new CountDownLatch(1);
        var service = new TilePrefetchService(
                new TilePrefetchService.TileWork() {
                    public boolean ready(TerrainTileKey k) {
                        return false;
                    }

                    public void prepare(TerrainTileKey k) {
                        started.countDown();
                    }
                },
                () -> true,
                () -> false,
                manual::get);
        try {
            service.updateCandidates(List.of(key(0)), true);
            assertFalse(started.await(150, TimeUnit.MILLISECONDS));
            service.updateCandidates(List.of(key(0)), false);
            manual.set(false);
            service.signalStateChanged();
            assertFalse(started.await(150, TimeUnit.MILLISECONDS));
            service.updateCandidates(List.of(key(0)), true);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(service.awaitIdle(5, TimeUnit.SECONDS));
        } finally {
            service.shutdown();
        }
    }
}

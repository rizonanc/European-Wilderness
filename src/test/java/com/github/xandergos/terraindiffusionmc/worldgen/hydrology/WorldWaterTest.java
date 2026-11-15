package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldWaterTest {
    @TempDir Path temp;
    private static final int NONE = PreparedWaterArea.NONE;
    private static final WorldWaterGrid SMALL = new WorldWaterGrid(64, 32, 128);

    @AfterEach
    void retire() {
        WorldSession.deactivate(WorldSession.current());
    }

    private static int[][] raw(int x, int z, int side) {
        int[][] data = new int[side][side];
        for (int dz = 0; dz < side; dz++)
            for (int dx = 0; dx < side; dx++)
                data[dz][dx] = x + dx >= 48 && x + dx < 82 && z + dz >= 16 && z + dz < 48 ? 83 : 103;
        return data;
    }

    @Test
    void aLakeCrossingAnOwnershipBoundaryIsWholeAndHasOnlyOneOwner() {
        var a = WorldWaterUnit.plan(7, SMALL, new WorldWaterGrid.Cell(0, 0), raw(-32, -32, 128));
        var b = WorldWaterUnit.plan(7, SMALL, new WorldWaterGrid.Cell(1, 0), raw(32, -32, 128));
        assertTrue(a.indices.length > 0);
        assertEquals(0, java.util.stream.IntStream.range(0, b.lakes.length).filter(i -> b.lakes[i] != 0).count());
        int[] water = new int[128 * 128];
        Arrays.fill(water, NONE);
        boolean[] lake = new boolean[water.length];
        a.merge(SMALL, 0, 0, 128, water, lake);
        b.merge(SMALL, 0, 0, 128, water, lake);
        for (int z = 16; z < 48; z++) for (int x = 48; x < 82; x++) assertEquals(102, water[z * 128 + x], x + ", " + z);
    }

    @Test
    void croppedCoresMatchOneContinuousReferenceIncludingDeepLakeAndNegativeCoordinates() {
        var grid = new WorldWaterGrid(64, 128, 128);
        var owner = new WorldWaterGrid.Cell(0, 0);
        int side = grid.unitSide();
        List<Integer> index = new ArrayList<>();
        for (int z = 12; z < side - 12; z++) for (int x = 12; x < side - 12; x++) index.add(z * side + x);
        int[] indices = index.stream().mapToInt(Integer::intValue).toArray();
        short[] levels = new short[indices.length];
        Arrays.fill(levels, (short) 120);
        byte[] lakes = new byte[indices.length];
        Arrays.fill(lakes, (byte) 1);
        var unit = new WorldWaterUnit(grid, owner, indices, levels, lakes, List.of());
        int refSide = 640, refOrigin = -256;
        int[] air = new int[refSide * refSide], water = new int[air.length];
        Arrays.fill(air, 81);
        Arrays.fill(water, NONE);
        boolean[] fine = new boolean[air.length];
        unit.merge(grid, refOrigin, refOrigin, refSide, water, fine);
        boolean[] coarse = new boolean[refSide / 2 * (refSide / 2)];
        for (int p = 0; p < fine.length; p++)
            if (fine[p]) coarse[(p / refSide / 2) * (refSide / 2) + p % refSide / 2] = true;
        RiverBanks.shape(42, refOrigin, refOrigin, refSide, air, water, coarse);
        for (int cz = -1; cz <= 1; cz++)
            for (int cx = -1; cx <= 1; cx++) {
                int[][] raw = new int[grid.renderSide()][grid.renderSide()];
                for (var row : raw) Arrays.fill(row, 81);
                var tile = WorldWaterTile.render(42, grid, new WorldWaterGrid.Cell(cx, cz), raw, List.of(unit));
                for (int z = 0; z < 64; z++)
                    for (int x = 0; x < 64; x++) {
                        int p = (tile.z + z - refOrigin) * refSide + tile.x + x - refOrigin;
                        assertEquals(water[p], tile.water[z * 64 + x]);
                        assertEquals(air[p], tile.air[z * 64 + x], "Seam at " + (tile.x + x) + ", " + (tile.z + z));
                    }
            }
        assertEquals(new WorldWaterGrid.Cell(-1, -1), grid.cell(-1, -64));
    }

    private WorldSession session(AtomicInteger calls, TerrainRepository.TileLoader custom) {
        var s = WorldSession.activate(GenerationProfile.defaultsFor(2, "model", "cpu", "cpu"), 7);
        TerrainRepository.TileLoader loader =
                custom != null
                        ? custom
                        : key -> {
                            calls.incrementAndGet();
                            int side = key.tileSize();
                            short[] heights = new short[side * side], biomes = new short[heights.length];
                            for (int z = 0; z < side; z++)
                                for (int x = 0; x < side; x++) {
                                    int wx = key.blockStartX() + x, wz = key.blockStartZ() + z;
                                    heights[z * side + x] =
                                            (short) (wx >= 48 && wx < 82 && wz >= 16 && wz < 48 ? 300 : 600);
                                }
                            return new TerrainTile(key, heights, biomes);
                        };
        s.attachRepository(
                new TerrainRepository(
                        new DiskTerrainStore(temp.resolve("raw")), loader, 256, 1024 * 1024, 1024L * 1024 * 1024, s));
        return s;
    }

    @Test
    void revisionFourNeedlesAndBanksMatchAcrossCroppedCoreBoundaries() {
        var grid = new WorldWaterGrid(64, 128, 128);
        int side = grid.unitSide();
        List<Integer> indices = new ArrayList<>();
        for (int z = 24; z < 40; z++)
            for (int x = -32; x < 96; x++) if (x - z != 2) indices.add((z + 128) * side + x + 128);
        int[] index = indices.stream().mapToInt(Integer::intValue).toArray();
        short[] levels = new short[index.length];
        Arrays.fill(levels, (short) 100);
        var unit =
                new WorldWaterUnit(
                        grid, new WorldWaterGrid.Cell(0, 0), index, levels, new byte[index.length], List.of(), 4);
        int refSide = 512, origin = -192;
        int[] air = new int[refSide * refSide], water = new int[air.length];
        Arrays.fill(air, 105);
        Arrays.fill(water, NONE);
        boolean[] fine = new boolean[air.length];
        unit.merge(grid, origin, origin, refSide, water, fine);
        RiverBanks.shape(42, origin, origin, refSide, air, water, new boolean[refSide * refSide / 4]);
        BankPeaks.lower(refSide, air, water);
        BankNeedles.lower(refSide, air, water);
        for (int cx = -1; cx <= 1; cx++) {
            int[][] raw = new int[grid.renderSide()][grid.renderSide()];
            for (var row : raw) Arrays.fill(row, 105);
            var tile = WorldWaterTile.render(42, grid, new WorldWaterGrid.Cell(cx, 0), raw, List.of(unit));
            for (int z = 0; z < 64; z++)
                for (int x = 0; x < 64; x++) {
                    int p = (tile.z + z - origin) * refSide + tile.x + x - origin;
                    assertEquals(water[p], tile.water[z * 64 + x]);
                    assertEquals(air[p], tile.air[z * 64 + x]);
                }
        }
        assertEquals(
                100,
                water[(30 - origin) * refSide + 32 - origin],
                "the diagonal remnant in the seam fixture was actually lowered");
    }

    @Test
    void plannedCurrentReachesFrozenCoreAndAcceleratesBoatsDownstream() throws Exception {
        var cell = new WorldWaterGrid.Cell(0, 0);
        int side = SMALL.unitSide();
        int[][] valley = new int[side][side];
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) valley[z][x] = 180 - 2 * x + Math.abs(z - 64) * 3;
        var unit = WorldWaterUnit.plan(4, SMALL, cell, valley, 5);
        var store = new WorldWaterStore(temp, 4, "flow", 256, SMALL);
        store.save(unit);
        assertArrayEquals(unit.currents, store.loadUnit(cell).currents);
        int[][] raw = new int[SMALL.renderSide()][SMALL.renderSide()];
        for (var row : raw) Arrays.fill(row, 120);
        var tile = WorldWaterTile.render(4, SMALL, cell, raw, List.of(store.loadUnit(cell)));
        store.save(tile);
        var saved = store.loadTile(cell);
        int flowing = 0;
        for (int x = 0; x < 64; x++) {
            int y = tile.water[tile.index(x, 32)];
            short current = tile.currentAt(x, y, 32);
            assertEquals(current, saved.currentAt(x, y, 32));
            if (current == 0) continue;
            var drift = RiverCurrent.accelerate(net.minecraft.util.math.Vec3d.ZERO, current, true);
            assertTrue(drift.x > 0, "the valley drains east");
            assertEquals(0, drift.y);
            assertTrue(drift.horizontalLength() <= 0.022001);
            flowing++;
        }
        assertTrue(flowing > 4);
    }

    @Test
    void legacyCatchmentRecoversDownstreamCurrentWithoutChangingItsFootprint() throws Exception {
        var cell = new WorldWaterGrid.Cell(0, 0);
        int side = SMALL.unitSide();
        int[][] valley = new int[side][side];
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) valley[z][x] = 180 - 2 * x + Math.abs(z - 64) * 3;
        var store = new WorldWaterStore(temp, 4, "legacy", 256, SMALL);
        store.save(WorldWaterUnit.plan(4, SMALL, cell, valley, 4));
        var legacy = store.loadUnit(cell);
        assertNull(legacy.currents);
        var recovered = legacy.restoreCurrents(4L, SMALL, valley);
        assertArrayEquals(legacy.indices, recovered.indices);
        assertArrayEquals(legacy.levels, recovered.levels);
        assertArrayEquals(legacy.lakes, recovered.lakes);
        int moving = 0;
        for (int i = 0; i < recovered.indices.length; i++) if (recovered.currents[i] != 0) moving++;
        assertTrue(moving > 4);
        assertNull(store.loadUnit(cell).currents);
    }

    @Test
    void mountainRiverLimitLeavesTheValleyDownstreamConnected() {
        int side = 256;
        int[][] valley = new int[side][side];
        for (int z = 0; z < side; z++)
            for (int x = 0; x < side; x++) valley[z][x] = Math.max(52, 320 - 2 * x + Math.abs(z - 128) * 3);
        var allowed = PreparedWaterArea.plan(4, 0, 0, valley, WaterGenerationOptions.DEFAULT, 2);
        var options = new WaterGenerationOptions(100, 100, 100, false);
        var limited = PreparedWaterArea.plan(4, 0, 0, valley, options, 2);
        int ceiling = 163;
        int high = 0, low = 0;
        for (int z = 0; z < side; z++)
            for (int x = 0; x < side; x++) {
                if (allowed.waterY(x, z) > ceiling) high++;
                assertTrue(limited.waterY(x, z) <= ceiling);
                if (limited.waterY(x, z) > 62) low++;
            }
        assertTrue(high > 20);
        assertTrue(low > 20);

        var grid = new WorldWaterGrid(128, 64, 128);
        var cell = new WorldWaterGrid.Cell(0, 0);
        for (long seed = 1; seed <= 8; seed++) {
            var original = WorldWaterUnit.plan(seed, grid, cell, valley, 6, WaterGenerationOptions.DEFAULT, 2);
            if (java.util.stream.IntStream.range(0, original.levels.length)
                    .noneMatch(i -> original.levels[i] > ceiling)) continue;
            var automatic = WorldWaterUnit.plan(seed, grid, cell, valley, 6, options, 2);
            assertTrue(automatic.levels.length > 20);
            for (short level : automatic.levels) assertTrue(level <= ceiling);
            return;
        }
        fail("The automatic fixture did not produce a high-altitude channel");
    }

    @Test
    void newlyPlannedCatchmentKeepsItsDepthSettingOnReload() throws Exception {
        var cell = new WorldWaterGrid.Cell(0, 0);
        int side = SMALL.unitSide();
        int[][] valley = new int[side][side];
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) valley[z][x] = 180 - 2 * x + Math.abs(z - 64) * 3;
        var unit =
                WorldWaterUnit.plan(
                        4,
                        SMALL,
                        cell,
                        valley,
                        6,
                        new com.github.xandergos.terraindiffusionmc.worldgen.settings.WaterGenerationOptions(
                                0, 190, 100));
        var store = new WorldWaterStore(temp, 4, "water-shape", 256, SMALL);
        store.save(unit);
        var saved = store.loadUnit(cell);
        assertEquals(190, saved.depthVariation);
        assertArrayEquals(unit.indices, saved.indices);
        assertArrayEquals(unit.currents, saved.currents);
    }

    @Test
    void changingCurrentStrengthKeepsTheDownstreamDirection() {
        short current = (short) ((40 << 8) | (-20 & 255));
        assertEquals(0, RiverCurrent.scale(current, 0));
        assertEquals(current, RiverCurrent.scale(current, 100));
        short stronger = RiverCurrent.scale(current, 175);
        assertEquals(70, (byte) (stronger >> 8));
        assertEquals(-35, (byte) stronger);
    }

    @Test
    void raisingLakeThresholdSkipsShallowSmallBasins() {
        int[][] heights = new int[8][8];
        for (var row : heights) Arrays.fill(row, 100);
        for (int z = 2; z <= 5; z++) for (int x = 2; x <= 5; x++) heights[z][x] = 98;
        var normal = DrainagePlanner.planPrepared(4, 0, 0, heights, 100);
        var largerOnly = DrainagePlanner.planPrepared(4, 0, 0, heights, 200);
        assertEquals(DrainagePlanner.CellKind.LAKE, normal.kind(3, 3));
        assertEquals(DrainagePlanner.CellKind.FILLED, largerOnly.kind(3, 3));
    }

    private WorldWaterController controller(WorldSession s, int cache) {
        return new WorldWaterController(s, temp.resolve("water"), 300, SMALL, cache, 4);
    }

    @Test
    void allocationFreeCoreBoundsMatchGridIdentityAtNegativeEdgesAndCannotWrapIntegers() {
        int[] air = new int[64 * 64], water = new int[air.length];
        Arrays.fill(air, 103);
        Arrays.fill(water, NONE);
        for (int cz = -2; cz <= 1; cz++)
            for (int cx = -2; cx <= 1; cx++) {
                var key = new WorldWaterGrid.Cell(cx, cz);
                var tile = new WorldWaterTile(SMALL, key, air, water, List.of());
                for (int dz = -1; dz <= 64; dz++)
                    for (int dx = -1; dx <= 64; dx++) {
                        int x = tile.x + dx, z = tile.z + dz;
                        assertEquals(key.equals(SMALL.cell(x, z)), tile.contains(x, z));
                    }
                assertFalse(tile.contains(Integer.MIN_VALUE, Integer.MIN_VALUE));
                assertFalse(tile.contains(Integer.MAX_VALUE, Integer.MAX_VALUE));
            }
    }

    @Test
    void automaticPlanningPersistsAcrossEvictionAndReloadWithoutInference() throws Exception {
        var calls = new AtomicInteger();
        var s = session(calls, null);
        int[] xs = {63, 64, -1, 128, 64, 63};
        int[] water = new int[xs.length], air = new int[xs.length];
        try (var c = controller(s, 1)) {
            s.attachAutomaticWater(c);
            for (int i = 0; i < xs.length; i++) {
                water[i] = c.waterY(xs[i], 32);
                air[i] = c.carvedHeight(xs[i], 32, 999);
            }
            assertEquals(102, water[0]);
            assertEquals(102, water[1]);
            assertTrue(c.computedTiles() >= 4);
            assertTrue(c.residentTiles() <= 1);
            assertTrue(c.residentUnits() <= 4);
        }
        int before = calls.get();
        try (var restored = controller(s, 1)) {
            for (int i = xs.length - 1; i >= 0; i--) {
                assertEquals(water[i], restored.waterY(xs[i], 32));
                assertEquals(air[i], restored.carvedHeight(xs[i], 32, 999));
            }
            assertEquals(0, restored.computedTiles());
            assertEquals(0, restored.computedUnits());
            assertEquals(before, calls.get());
        }
    }

    @Test
    void generationOrderDoesNotChangeNeighbouringCores() throws Exception {
        var s = session(new AtomicInteger(), null);
        int[][] sample = new int[2][4];
        try (var a = new WorldWaterController(s, temp.resolve("order-a"), 300, SMALL, 2, 4);
                var b = new WorldWaterController(s, temp.resolve("order-b"), 300, SMALL, 2, 4)) {
            for (int i = 0; i < 2; i++) {
                sample[i][0] = a.waterY(63 + i, 32);
                sample[i][1] = a.carvedHeight(63 + i, 32, 999);
            }
            for (int i = 1; i >= 0; i--) {
                sample[i][2] = b.waterY(63 + i, 32);
                sample[i][3] = b.carvedHeight(63 + i, 32, 999);
            }
            for (var row : sample) {
                assertEquals(row[0], row[2]);
                assertEquals(row[1], row[3]);
            }
        }
    }

    @Test
    void concurrentFirstReadersShareOnePlanAndSeeNoTemporaryDryFallback() throws Exception {
        var s = session(new AtomicInteger(), null);
        var barrier = new CyclicBarrier(6);
        try (var c = controller(s, 2);
                var pool = Executors.newFixedThreadPool(6)) {
            List<Future<Integer>> readers = new ArrayList<>();
            for (int i = 0; i < 6; i++)
                readers.add(
                        pool.submit(() -> {
                            barrier.await();
                            return c.waterY(60, 32);
                        }));
            for (var r : readers) assertEquals(102, r.get(30, TimeUnit.SECONDS));
            assertEquals(1, c.computedTiles());
            try (var files = Files.list(temp.resolve("water/units"))) {
                assertEquals(files.count(), c.computedUnits());
            }
        }
    }

    @Test
    void closeDuringRawComputationCannotPublishAPlan() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var s =
                session(
                        new AtomicInteger(),
                        key -> {
                            entered.countDown();
                            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("timeout");
                            return new TerrainTile(key, new short[65536], new short[65536]);
                        });
        try (var c = controller(s, 1);
                var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() -> c.ensureColumn(0, 0));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            c.close();
            release.countDown();
            assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
            assertEquals(0, c.computedTiles());
            assertEquals(0, c.computedUnits());
            assertFalse(Files.exists(temp.resolve("water/tiles")));
            assertFalse(Files.exists(temp.resolve("water/units")));
        } finally {
            release.countDown();
        }
    }

    @Test
    void neighbouringCoresCanBeGeneratedConcurrentlyWithoutDependencyCycles() throws Exception {
        var s = session(new AtomicInteger(), null);
        var barrier = new CyclicBarrier(2);
        try (var c = controller(s, 2);
                var pool = Executors.newFixedThreadPool(2)) {
            var left =
                    pool.submit(() -> {
                        barrier.await();
                        return c.waterY(63, 32);
                    });
            var right =
                    pool.submit(() -> {
                        barrier.await();
                        return c.waterY(64, 32);
                    });
            assertEquals(102, left.get(30, TimeUnit.SECONDS));
            assertEquals(102, right.get(30, TimeUnit.SECONDS));
            assertEquals(2, c.computedTiles());
        }
    }

    @Test
    void realTerrainReplayExercisesOwnedWatershedsWhenSnapshotIsAvailable() throws Exception {
        Path snapshot = Path.of("../recovery-evidence/g3-invalid-column/terrain");
        Assumptions.assumeTrue(Files.isDirectory(snapshot));
        var source = new DiskTerrainStore(snapshot);
        int[][] raw = new int[1024][1024];
        String fp = "7d73c789d6b92225ca49fc0b8087c3353eecad582fcc0c92f4b6c0062eeee577";
        for (int tz = 14; tz < 18; tz++)
            for (int tx = 14; tx < 18; tx++) {
                var tile = source.load(TerrainTileKey.ofTile(12345, tx, tz, 256, fp)).orElseThrow();
                for (int z = 0; z < 256; z++)
                    for (int x = 0; x < 256; x++)
                        raw[(tz - 14) * 256 + z][(tx - 14) * 256 + x] =
                                com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField.sample(
                                                12345, tx * 256 + x, tz * 256 + z, tile.heightAt(x, z), 2, 1871)
                                        .surfaceY();
            }
        var grid = new WorldWaterGrid(256, 128, 128);
        List<WorldWaterUnit> units = new ArrayList<>();
        for (int cz = 15; cz <= 16; cz++)
            for (int cx = 15; cx <= 16; cx++) {
                var cell = new WorldWaterGrid.Cell(cx, cz);
                int[][] local = new int[512][512];
                for (int z = 0; z < 512; z++)
                    System.arraycopy(raw[grid.z(cell) - 128 - 3584 + z], grid.x(cell) - 128 - 3584, local[z], 0, 512);
                units.add(WorldWaterUnit.plan(12345, grid, cell, local));
            }
        assertTrue(
                units.stream().mapToInt(u -> u.indices.length).sum() > 0,
                "Owned watersheds should survive on the actual terrain fixture");
        List<WorldWaterTile> tiles = new ArrayList<>();
        for (int cz = 15; cz <= 16; cz++)
            for (int cx = 15; cx <= 16; cx++) {
                var cell = new WorldWaterGrid.Cell(cx, cz);
                int[][] local = new int[512][512];
                for (int z = 0; z < 512; z++)
                    System.arraycopy(raw[grid.z(cell) - 128 - 3584 + z], grid.x(cell) - 128 - 3584, local[z], 0, 512);
                var a = WorldWaterTile.render(12345, grid, cell, local, units);
                var reversed = new ArrayList<>(units);
                Collections.reverse(reversed);
                var b = WorldWaterTile.render(12345, grid, cell, local, reversed);
                assertArrayEquals(a.air, b.air);
                assertArrayEquals(a.water, b.water);
                tiles.add(a);
            }
        Path evidence = Path.of("../recovery-evidence/g3-world-water-preview");
        Files.createDirectories(evidence);
        try (var out = new DataOutputStream(Files.newOutputStream(evidence.resolve("owned-fixture.bin")))) {
            out.writeInt(512);
            out.writeInt(3840);
            out.writeInt(3840);
            for (int layer = 0; layer < 2; layer++)
                for (int z = 0; z < 512; z++)
                    for (int x = 0; x < 512; x++) {
                        var tile = tiles.get(z / 256 * 2 + x / 256);
                        out.writeInt((layer == 0 ? tile.air : tile.water)[(z % 256) * 256 + x % 256]);
                    }
        }
        Files.writeString(
                evidence.resolve("owned-fixture.txt"),
                "Actual seed=12345 raw terrain; four 256-block cores, halo=128. Only the four wholly captured watershed units are supplied; this is not a full default 512/256 layout or a game screenshot. Wet input columns="
                        + units.stream().mapToInt(u -> u.indices.length).sum()
                        + "\n");
    }

    @Test
    void aRetiredWorldCannotPublishIntoTheNextWorld() throws Exception {
        var s = session(new AtomicInteger(), null);
        try (var c = controller(s, 1)) {
            WorldSession.activate(s.profile(), 8);
            assertThrows(IllegalStateException.class, () -> c.ensureColumn(0, 0));
            assertFalse(Files.exists(temp.resolve("water")));
        }
    }

    @Test
    void savedWaterfallsAreAvailableForTheirChunkAfterCacheEviction() throws Exception {
        var s = session(new AtomicInteger(), null);
        var a = new WorldWaterGrid.Cell(0, 0);
        var b = new WorldWaterGrid.Cell(1, 0);
        for (int z = -1; z <= 0; z++)
            for (int x = -1; x <= 0; x++) s.repository().get(TerrainTileKey.ofTile(7, x, z, 256, s.fingerprint()));
        int[] air = new int[4096], water = new int[4096];
        Arrays.fill(air, 103);
        Arrays.fill(water, NONE);
        var f = new Waterfall(24, 24, 1, 0, 100, 90, 3, 8);
        WaterfallPlanner.apply(f, 0, 0, 64, air, water);
        var store = new WorldWaterStore(temp.resolve("water"), 7, s.fingerprint(), 256, SMALL);
        store.save(new WorldWaterTile(SMALL, a, air, water, List.of(f)));
        Arrays.fill(air, 103);
        Arrays.fill(water, NONE);
        store.save(new WorldWaterTile(SMALL, b, air, water, List.of()));
        try (var c = controller(s, 1)) {
            assertEquals(List.of(f), c.waterfallsForChunk(1, 1));
            c.ensureColumn(64, 0);
            assertTrue(c.waterfalls().isEmpty());
            List<WaterfallPlacement.Placement> placements = new ArrayList<>();
            WaterfallPlacement.forChunk(c, 16, 16, -64, 320, placements::add);
            assertTrue(placements.stream().anyMatch(p -> p.kind() == WaterfallPlacement.Kind.FALLING));
            assertEquals(List.of(f), c.waterfallsForChunk(1, 1));
            assertEquals(0, c.computedTiles());
        }
    }

    @Test
    void storeRejectsForeignTruncatedAndCorruptPlans() throws Exception {
        var cell = new WorldWaterGrid.Cell(-1, 2);
        var store = new WorldWaterStore(temp, 7, "fp", 256, SMALL);
        int[] air = new int[4096], water = new int[4096];
        Arrays.fill(air, 103);
        Arrays.fill(water, NONE);
        store.save(new WorldWaterTile(SMALL, cell, air, water, List.of()));
        assertArrayEquals(air, store.loadTile(cell).air);
        assertThrows(IOException.class, () -> new WorldWaterStore(temp, 8, "fp", 256, SMALL).loadTile(cell));
        assertThrows(IOException.class, () -> new WorldWaterStore(temp, 7, "other", 256, SMALL).loadTile(cell));
        assertThrows(IOException.class, () -> store.save(new WorldWaterTile(SMALL, cell, air, water, List.of())));
        Path path = temp.resolve("tiles/-1_2.eww");
        byte[] valid = Files.readAllBytes(path);
        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 8] ^= 1;
        Files.write(path, corrupt);
        assertThrows(IOException.class, () -> store.loadTile(cell));
        Files.write(path, Arrays.copyOf(valid, valid.length / 2));
        assertThrows(IOException.class, () -> store.loadTile(cell));
    }

    @Test
    void missingFrozenTerrainFailsInsteadOfSilentlyRecomputingAnExistingWaterCore() throws Exception {
        var s = session(new AtomicInteger(), null);
        int[] air = new int[4096], water = new int[4096];
        Arrays.fill(air, 103);
        Arrays.fill(water, NONE);
        new WorldWaterStore(temp.resolve("water"), 7, s.fingerprint(), 256, SMALL)
                .save(new WorldWaterTile(SMALL, new WorldWaterGrid.Cell(0, 0), air, water, List.of()));
        try (var c = controller(s, 1)) {
            assertThrows(IllegalStateException.class, () -> c.ensureColumn(0, 0));
        }
    }

    @Test
    void incrementalPreparationBoundsSourceWorkAndProducesTheSameFrozenCore() throws Exception {
        var calls = new AtomicInteger();
        var s = session(calls, null);
        try (var ahead = controller(s, 2);
                var demand = new WorldWaterController(s, temp.resolve("demand"), 300, SMALL, 2, 4)) {
            assertFalse(ahead.prepareAheadStep(0, 0, () -> false));
            assertEquals(0, calls.get());
            int stages = 0;
            while (!ahead.isPrepared(0, 0) && stages++ < 200) {
                int before = calls.get();
                boolean complete = ahead.prepareAheadStep(0, 0, () -> true);
                assertTrue(
                        calls.get() - before <= 1,
                        "A background stage must not trigger a chain of source computations");
                if (!complete) assertEquals(0, ahead.computedTiles());
            }
            assertTrue(ahead.isPrepared(0, 0));
            assertTrue(stages > 1);
            assertEquals(1, ahead.computedTiles());
            int before = calls.get();
            ahead.ensureColumn(0, 0);
            assertEquals(before, calls.get());
            demand.ensureColumn(0, 0);
            var a =
                    new WorldWaterStore(temp.resolve("water"), 7, s.fingerprint(), 256, SMALL)
                            .loadTile(new WorldWaterGrid.Cell(0, 0));
            var b =
                    new WorldWaterStore(temp.resolve("demand"), 7, s.fingerprint(), 256, SMALL)
                            .loadTile(new WorldWaterGrid.Cell(0, 0));
            assertArrayEquals(a.air, b.air);
            assertArrayEquals(a.water, b.water);
            assertEquals(a.falls, b.falls);
        }
    }

    @Test
    void foregroundCanJoinAnActiveBackgroundSourceWithoutADeferredSharedFuture() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var s =
                session(
                        calls,
                        key -> {
                            if (calls.incrementAndGet() == 1) {
                                entered.countDown();
                                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("timeout");
                            }
                            short[] heights = new short[65536];
                            Arrays.fill(heights, (short) 600);
                            return new TerrainTile(key, heights, new short[65536]);
                        });
        try (var c = controller(s, 2);
                var pool = Executors.newFixedThreadPool(2)) {
            var bg = pool.submit(() -> c.prepareAheadStep(0, 0, () -> true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var fg = pool.submit(() -> c.waterY(0, 0));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!c.foregroundBusy() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(c.foregroundBusy());
            assertFalse(c.prepareAheadStep(64, 0, () -> true));
            release.countDown();
            assertFalse(bg.get(10, TimeUnit.SECONDS));
            assertEquals(NONE, fg.get(20, TimeUnit.SECONDS));
            assertEquals(1, c.computedTiles());
            assertFalse(c.foregroundBusy());
        } finally {
            release.countDown();
        }
    }

    @Test
    void revokedBudgetStopsBeforeTheNextStageAndCanResume() throws Exception {
        var calls = new AtomicInteger();
        var s = session(calls, null);
        var budget = new AtomicBoolean(true);
        try (var c = controller(s, 2)) {
            assertFalse(c.prepareAheadStep(0, 0, budget::get));
            assertEquals(1, calls.get());
            budget.set(false);
            assertFalse(c.prepareAheadStep(0, 0, budget::get));
            assertEquals(1, calls.get());
            assertEquals(0, c.computedUnits());
            budget.set(true);
            for (int i = 0; i < 200 && !c.isPrepared(0, 0); i++) c.prepareAheadStep(0, 0, budget::get);
            assertTrue(c.isPrepared(0, 0));
        }
    }

    @Test
    void neighbouringWaterCoresReuseConvertedHeights() {
        var s = session(new AtomicInteger(), null);
        try (var c = controller(s, 2)) {
            c.ensureColumn(0, 0);
            long first = c.timings().snapshot().get("heightTransform").count();
            assertTrue(first > 0);
            c.ensureColumn(64, 0);
            assertEquals(first, c.timings().snapshot().get("heightTransform").count());
            assertEquals(first, c.residentHeights());
            assertTrue(c.residentHeights() <= 32);
        }
    }

    @Test
    void lodExportReadsOnlyFrozenPlansAndDoesNotInvokeInference() throws Exception {
        var calls = new AtomicInteger();
        var s = session(calls, null);
        try (var c = controller(s, 2)) {
            s.attachAutomaticWater(c);
            assertNull(c.frozenCoreIfReady(0, 0));
            assertEquals(0, calls.get());
            var source = new com.github.xandergos.terraindiffusionmc.compat.voxy.LodTerrainSource(s);
            assertNull(source.sample(0, 0, -64, 319));
            assertEquals(0, calls.get());
            c.ensureColumn(0, 0);
            int before = calls.get();
            var view = c.frozenCoreIfReady(0, 0);
            assertNotNull(view);
            var column = source.sample(1, 0, -64, 319);
            assertNotNull(column);
            assertEquals(c.carvedHeight(63, 16, 999), column.firstAir(31, 16));
            assertEquals(before, calls.get());
            c.close();
            WorldSession.deactivate(s);
            assertTrue(view.isReady(0, 0));
            assertThrows(IllegalStateException.class, () -> source.sample(0, 0, -64, 319));
        }
    }

    @Test
    void frozenViewClonesItsArraysAndCarriesNoLiveGenerator() {
        int[] air = new int[1024], water = new int[1024];
        Arrays.fill(air, 100);
        Arrays.fill(water, 102);
        var view = new PreparedWaterView(-32, -32, 32, air, water, List.of());
        Arrays.fill(air, 0);
        Arrays.fill(water, 0);
        assertEquals(100, view.carvedHeight(-1, -1, 999));
        assertEquals(102, view.waterY(-1, -1));
        assertEquals(999, view.carvedHeight(0, 0, 999));
        assertEquals(NONE, view.waterY(0, 0));
    }

    @Test
    void frozenExportsReuseOneImmutableViewInsteadOfCopyingMegabytes() throws Exception {
        var s = session(new AtomicInteger(), null);
        try (var c = controller(s, 2)) {
            c.ensureColumn(0, 0);
            var a = c.frozenCoreIfReady(0, 0);
            var b = c.frozenCoreIfReady(20, 20);
            assertSame(a, b);
            assertEquals(c.carvedHeight(20, 20, 999), a.carvedHeight(20, 20, 999));
        }
    }

    @Test
    void frameBudgetDeniesSpeculationButNeverBlocksForegroundGeneration() throws Exception {
        var calls = new AtomicInteger();
        var s = session(calls, null);
        s.attachBackgroundPolicy(() -> false, () -> false);
        try (var c = controller(s, 2)) {
            assertFalse(c.prepareAheadStep(0, 0, () -> true));
            assertEquals(0, calls.get());
            c.ensureColumn(0, 0);
            assertTrue(calls.get() > 0);
        }
    }

    @Test
    void newerAlgorithmKeepsEveryAlreadyFrozenFileAndUsesNewRevisionOnlyForMissingPlans() throws Exception {
        var s = session(new AtomicInteger(), null);
        try (var legacy = controller(s, 2)) {
            legacy.ensureColumn(0, 0);
        }
        Map<Path, byte[]> old = new HashMap<>();
        try (var files = Files.walk(temp.resolve("water"))) {
            files.filter(Files::isRegularFile)
                    .forEach(p -> {
                        try {
                            old.put(p, Files.readAllBytes(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        try (var updated = new WorldWaterController(s, temp.resolve("water"), 300, SMALL, 2, 4, 2)) {
            updated.ensureColumn(0, 0);
            assertEquals(0, updated.computedUnits());
            updated.ensureColumn(64, 0);
        }
        for (var entry : old.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
        var store = new WorldWaterStore(temp.resolve("water"), 7, s.fingerprint(), 256, SMALL);
        assertEquals(1, store.loadTile(new WorldWaterGrid.Cell(0, 0)).revision);
        assertEquals(1, store.loadUnit(new WorldWaterGrid.Cell(0, 0)).revision);
        assertEquals(2, store.loadUnit(new WorldWaterGrid.Cell(4, 0)).revision);
    }

    @Test
    void lodSourceCopiesFourSmallRawTilesWithCorrectNegativeCoordinates() throws Exception {
        var s = WorldSession.activate(GenerationProfile.defaultsFor(2, "small-tiles", "cpu", "cpu"), 9);
        s.attachRepository(
                new TerrainRepository(
                        new DiskTerrainStore(temp.resolve("raw-small")),
                        key -> {
                            short[] heights = new short[256], biomes = new short[256];
                            Arrays.fill(heights, (short) 600);
                            for (int z = 0; z < 16; z++)
                                for (int x = 0; x < 16; x++)
                                    biomes[z * 16 + x] =
                                            (short)
                                                    (Math.floorMod(key.blockStartX() + x, 32)
                                                            + 32 * Math.floorMod(key.blockStartZ() + z, 32));
                            return new TerrainTile(key, heights, biomes);
                        },
                        16,
                        1024 * 1024,
                        1024L * 1024 * 1024,
                        s));
        try (var c = new WorldWaterController(s, temp.resolve("small-water"), 300, SMALL, 2, 4)) {
            s.attachAutomaticWater(c);
            c.ensureColumn(-1, -1);
            var source = new com.github.xandergos.terraindiffusionmc.compat.voxy.LodTerrainSource(s);
            var column = source.sample(-1, -1, -64, 319);
            assertNotNull(column);
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++) {
                    assertEquals(z * 32 + x, column.biome(x, z));
                    assertEquals(103, column.firstAir(x, z));
                }
        }
    }

    @Test
    void distantLodPreparationCannotTakeTheBudgetFromMissingAheadTargets() throws Exception {
        var calls = new AtomicInteger();
        var s = session(calls, null);
        try (var c = controller(s, 2)) {
            c.prioritizeAhead(List.of(TerrainTileKey.ofTile(7, 0, 0, 64, s.fingerprint())));
            assertFalse(c.prepareDistantStep(512, 0, () -> true));
            assertEquals(1, calls.get(), "one finite step serves the missing near target first");
            c.prioritizeAhead(List.of());
            c.prepareDistantStep(512, 0, () -> true);
            assertEquals(2, calls.get());
        }
    }

    @Test
    void lodForestReadsFrozenHaloOnlyAndDoesNotGenerateMissingNeighbours() throws Exception {
        var calls = new AtomicInteger();
        var s = WorldSession.activate(GenerationProfile.defaultsFor(2, "forest-probe", "cpu", "cpu"), 7);
        s.attachRepository(
                new TerrainRepository(
                        new DiskTerrainStore(temp.resolve("forest-raw")),
                        key -> {
                            calls.incrementAndGet();
                            int n = key.tileSize() * key.tileSize();
                            short[] air = new short[n], biome = new short[n];
                            float[] climate = new float[n * 4];
                            Arrays.fill(air, (short) 600);
                            Arrays.fill(biome, (short) 215);
                            Arrays.fill(climate, 0, n, 12);
                            Arrays.fill(climate, n * 2, n * 3, 900);
                            return new TerrainTile(key, air, biome, climate);
                        },
                        256,
                        32L * 1024 * 1024,
                        1024L * 1024 * 1024,
                        s));
        try (var c = new WorldWaterController(s, temp.resolve("forest-water"), 300, SMALL, 8, 16)) {
            s.attachAutomaticWater(c);
            c.ensureColumn(96, 96);
            int before = calls.get(), cores = c.computedTiles();
            var source = new com.github.xandergos.terraindiffusionmc.compat.voxy.LodTerrainSource(s);
            var bare = source.sample(3, 3, -64, 319);
            assertNotNull(bare);
            assertTrue(bare.forestPending());
            assertEquals(before, calls.get());
            assertEquals(cores, c.computedTiles());
            assertFalse(c.isPrepared(source.missingX(), source.missingZ()));
            c.ensureRegion(64, 64, 192, 192);
            before = calls.get();
            cores = c.computedTiles();
            var column = source.sample(3, 3, -64, 319);
            assertNotNull(column);
            assertEquals(before, calls.get());
            assertEquals(cores, c.computedTiles());
            assertFalse(column.forestPending());
            var buried = source.sample(3, 3, -64, 319, false);
            assertNotNull(buried);
            assertFalse(buried.forestPending());
            int limit = column.visibleBottomCube() * 32;
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    for (int y = -64; y < limit; y++)
                        assertEquals(
                                column.kind(x, y, z),
                                buried.kind(x, y, z),
                                "buried resume can omit all forest planning");
            assertTrue(column.maxY > column.firstAir(0, 0));
            int foliage = 0;
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    for (int y = column.firstAir(x, z); y <= column.maxY; y++)
                        if (column.kind(x, y, z).ordinal()
                                > com.github.xandergos.terraindiffusionmc.compat.voxy.LodColumn.Kind.WATER.ordinal())
                            foliage++;
            assertTrue(foliage > 100);
        }
    }
}

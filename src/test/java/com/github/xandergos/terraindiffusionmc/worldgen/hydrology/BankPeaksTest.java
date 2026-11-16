package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BankPeaksTest {
    @TempDir
    Path temp;

    private static final int SIDE = 64, NONE = PreparedWaterArea.NONE;

    private int[] water() {
        int[] w = new int[SIDE * SIDE];
        Arrays.fill(w, NONE);
        for (int z = 8; z < 56; z++) for (int x = 32; x < 56; x++) w[z * SIDE + x] = 100;
        return w;
    }

    @Test
    void smallCapsAreLoweredWithoutChangingTheWaterMask() {
        for (int width = 1; width <= 4; width++) {
            int[] air = new int[SIDE * SIDE], water = water();
            Arrays.fill(air, 101);
            for (int z = 20; z < 20 + width; z++) air[z * SIDE + 30] = 102;
            int[] original = water.clone();
            BankPeaks.lower(SIDE, air, water);
            for (int z = 20; z < 20 + width; z++) assertEquals(101, air[z * SIDE + 30]);
            assertArrayEquals(original, water);
        }
    }

    @Test
    void longBanksCliffsAndWaterRetainingWallsStayIntact() {
        int[] water = water(), air = new int[SIDE * SIDE];
        Arrays.fill(air, 101);
        for (int z = 16; z < 48; z++) air[z * SIDE + 30] = 102;
        air[20 * SIDE + 28] = 110;
        int[] before = air.clone();
        BankPeaks.lower(SIDE, air, water);
        assertArrayEquals(before, air);
        Arrays.fill(air, 100);
        air[24 * SIDE + 30] = 101;
        BankPeaks.lower(SIDE, air, water);
        assertEquals(101, air[24 * SIDE + 30], "Never lower dry ground beneath the water surface");
    }

    @Test
    void testExtraThinningRemovesSevenPercentOfPreviouslyKeptBasins() {
        for (boolean lake : new boolean[] {false, true}) {
            int oldKept = 0, extraRemoved = 0;
            for (int i = 0; i < 20000; i++) {
                int x = i * 2 - 20000, z = i * 74 - 100000;
                boolean old = WaterFrequency.keep(12345, x, z, lake, 2),
                        now = WaterFrequency.keep(12345, x, z, lake, 3);
                if (!old) assertFalse(now, "Never resurrect a rejected basin");
                else {
                    oldKept++;
                    if (!now) extraRemoved++;
                }
            }
            double rate = (double) extraRemoved / oldKept;
            assertTrue(rate >= .05 && rate <= .10, "Extra removal " + rate);
        }
    }

    @Test
    void revisionThreeRoundTripsAndStillReadsRevisionTwo() throws Exception {
        var grid = new WorldWaterGrid(64, 32, 128);
        var store = new WorldWaterStore(temp, 7, "fp", 256, grid);
        for (int revision : new int[] {2, 3}) {
            var key = new WorldWaterGrid.Cell(revision, 0);
            var unit = new WorldWaterUnit(
                    grid, key, new int[] {64 * 128 + 64}, new short[] {100}, new byte[] {0}, List.of(), revision);
            store.save(unit);
            assertEquals(revision, store.loadUnit(key).revision);
            int[] air = new int[4096], water = new int[4096];
            Arrays.fill(air, 100);
            Arrays.fill(water, 100);
            store.save(new WorldWaterTile(grid, key, air, water, List.of(), revision));
            assertEquals(revision, store.loadTile(key).revision);
        }
    }

    @Test
    void testIsolatedCapsBecomeEmbeddedHalfBlocksAtTheWaterline() {
        int[] water = new int[4096], air = new int[4096];
        Arrays.fill(water, 100);
        Arrays.fill(air, 99);
        // The marked screenshot pattern: two adjacent dry soil blocks amid a shallow bed.
        for (int x = 30; x <= 31; x++) {
            water[30 * 64 + x] = NONE;
            air[30 * 64 + x] = 101;
        }
        var view = new PreparedWaterView(0, 0, 64, air, water, List.of());
        List<RiverDetailPlanning.Detail> details = new ArrayList<>();
        RiverDetailPlanning.forChunk(view, 83, 16, 16, -64, 320, details::add);
        for (int x = 30; x <= 31; x++) {
            final int px = x;
            var cap = details.stream()
                    .filter(d -> d.x() == px && d.z() == 30)
                    .findFirst()
                    .orElseThrow();
            assertEquals(100, cap.y());
            assertTrue(cap.waterlogged());
            assertTrue(cap.kind().slab());
            assertEquals(101, RiverDetailPlanning.clearanceFirstAir(view, 83, x, 30, 101, -64, 320));
        }
        for (int x = 26; x <= 34; x++) {
            water[30 * 64 + x] = NONE;
            air[30 * 64 + x] = 101;
        }
        var bank = new PreparedWaterView(0, 0, 64, air, water, List.of());
        assertFalse(BankCaps.isolated(bank, 30, 30, 100), "A continuous bank must not be treated as a loose block");
    }

    @Test
    void revisionThreeThinsTheProductionLakePlanAsAWhole() {
        int[][] raw = new int[64][64];
        for (int z = 0; z < 64; z++)
            for (int x = 0; x < 64; x++) raw[z][x] = x >= 12 && x < 52 && z >= 12 && z < 52 ? 83 : 103;
        boolean extraDrop = false, retained = false;
        for (int seed = 0; seed < 100; seed++) {
            if (!WaterFrequency.keep(seed, 12, 12, true, 2)) continue;
            var before = PreparedWaterArea.calculate(seed, 0, 0, raw, null, 2);
            var after = PreparedWaterArea.calculate(seed, 0, 0, raw, null, 3);
            assertTrue(Arrays.stream(before.water()).anyMatch(w -> w != NONE));
            if (WaterFrequency.keep(seed, 12, 12, true, 3)) {
                assertArrayEquals(before.water(), after.water());
                retained = true;
            } else {
                assertTrue(Arrays.stream(after.water()).allMatch(w -> w == NONE));
                extraDrop = true;
            }
        }
        assertTrue(extraDrop && retained);
    }

    @Test
    void embeddedBankSlabsDoNotMakeNeighbouringRiverColumnsFailContainment() {
        int[] water = new int[4096], air = new int[4096];
        Arrays.fill(water, 100);
        Arrays.fill(air, 100);
        water[30 * 64 + 30] = NONE;
        air[30 * 64 + 30] = 101;
        var view = new PreparedWaterView(0, 0, 64, air, water, List.of());
        var columns = new RiverWaterPlanning.ColumnAccess() {
            public boolean isWaterloggedBankCap(int x, int y, int z) {
                return x == 30 && z == 30 && y == 100;
            }

            public boolean isWater(int x, int y, int z) {
                return isWaterloggedBankCap(x, y, z);
            }

            public boolean isAir(int x, int y, int z) {
                return y >= air[z * 64 + x] && !isWater(x, y, z);
            }

            public boolean isSolidDry(int x, int y, int z) {
                return y < air[z * 64 + x] && !isWater(x, y, z);
            }
        };
        assertTrue(FluidContainment.canPlace(FluidContainment.Fluid.WATER, view, 29, 30, 99, 100, -64, 320, columns));
        assertFalse(FluidContainment.canPlace(FluidContainment.Fluid.LAVA, view, 29, 30, 99, 100, -64, 320, columns));
        assertTrue(RiverWaterPlanning.planChunk(view, 1, 1, -64, 320, columns).stream()
                .anyMatch(p -> p.getX() == 29 && p.getY() == 100 && p.getZ() == 30));
    }

    @Test
    void capSmoothingMatchesAcrossOverlappingRenderWindows() {
        int[][] results = new int[2][];
        for (int window = 0; window < 2; window++) {
            int ox = window * 16;
            int[] air = new int[4096], water = new int[4096];
            Arrays.fill(air, 101);
            Arrays.fill(water, NONE);
            for (int z = 0; z < 64; z++)
                for (int x = 0; x < 64; x++) {
                    int wx = ox + x;
                    if (wx >= 32 && wx < 48) {
                        water[z * 64 + x] = 100;
                        air[z * 64 + x] = 99;
                    }
                    if (wx == 30 && z >= 30 && z <= 31) air[z * 64 + x] = 102;
                }
            BankPeaks.lower(64, air, water);
            results[window] = air;
        }
        for (int z = 12; z < 52; z++)
            for (int x = 28; x < 52; x++) assertEquals(results[0][z * 64 + x], results[1][z * 64 + x - 16]);
    }
}

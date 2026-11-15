package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverBanksTest {
    private static int[] channel() {
        int[] water = new int[64 * 64];
        Arrays.fill(water, PreparedWaterArea.NONE);
        for (int z = 8; z < 56; z++) for (int x = 24; x <= 38; x++) water[z * 64 + x] = 100;
        return water;
    }

    @Test
    void riverHasOneBlockShallowsAndStepsIntoDeeperWater() {
        int[] water = channel(), air = new int[4096];
        Arrays.fill(air, 105);
        RiverBanks.shape(7, 0, 0, 64, air, water, new boolean[1024]);
        assertEquals(100, air[32 * 64 + 24]);
        assertEquals(100, air[32 * 64 + 25]);
        assertTrue(air[32 * 64 + 31] <= 98, "centre should be deeper than the wading shelf");
        int previous = 100;
        for (int x = 24; x <= 31; x++) {
            assertTrue(Math.abs(air[32 * 64 + x] - previous) <= 1, "underwater steps should not become a trench wall");
            previous = air[32 * 64 + x];
        }
        assertEquals(101, air[32 * 64 + 23], "first dry beach should meet the water surface");
        assertEquals(105, air[32 * 64 + 10], "terrain outside the bank strip must remain unchanged");
    }

    @Test
    void lakeShelfPreservesItsDeepInteriorAndAllWaterLevels() {
        int[] water = new int[4096], air = new int[4096];
        Arrays.fill(water, PreparedWaterArea.NONE);
        Arrays.fill(air, 80);
        for (int z = 8; z < 56; z++) for (int x = 8; x < 56; x++) water[z * 64 + x] = 100;
        int[] before = water.clone();
        boolean[] lake = new boolean[1024];
        Arrays.fill(lake, true);
        RiverBanks.shape(8, -2048, 512, 64, air, water, lake);
        assertEquals(100, air[32 * 64 + 8]);
        assertEquals(80, air[32 * 64 + 32]);
        assertArrayEquals(before, water, "shape shores without changing the river network or lake surface");
    }

    @Test
    void reliefVariesAlongTheBedButIsRepeatableAcrossNegativeWorldCoordinates() {
        int[] water = channel(), air = new int[4096];
        Arrays.fill(air, 90);
        int[] copy = air.clone();
        RiverBanks.shape(83, -512, -1024, 64, air, water, new boolean[1024]);
        RiverBanks.shape(83, -512, -1024, 64, copy, water, new boolean[1024]);
        assertArrayEquals(air, copy);
        Set<Integer> depths = new HashSet<>();
        for (int z = 18; z < 46; z++) depths.add(101 - air[z * 64 + 31]);
        assertTrue(depths.size() > 1, "broad bed undulations should not be a perfectly constant-depth gutter");
    }

    @Test
    void beachAtWaterLevelIsContainedWithoutARetainingWallAboveWater() {
        int[] water = channel(), air = new int[4096];
        Arrays.fill(air, 105);
        RiverBanks.shape(7, 0, 0, 64, air, water, new boolean[1024]);
        var area = new PreparedWaterArea(0, 0, 64, air, water);
        var columns =
                new RiverWaterPlanning.ColumnAccess() {
                    public boolean isAir(int x, int y, int z) {
                        return y >= area.carvedHeight(x, z, 105);
                    }

                    public boolean isSolidDry(int x, int y, int z) {
                        return !isAir(x, y, z);
                    }
                };
        assertTrue(columns.isAir(23, 101, 32));
        assertTrue(columns.isSolidDry(23, 100, 32));
        assertTrue(FluidContainment.canPlace(FluidContainment.Fluid.WATER, area, 24, 32, 99, 100, -64, 320, columns));
    }

    @Test
    void sandIsSupportedAndCoversExposedUnderwaterStepsAcrossChunkBorders() {
        int[] water = channel(), air = new int[4096];
        Arrays.fill(air, 105);
        RiverBanks.shape(3, 0, 0, 64, air, water, new boolean[1024]);
        var area = new PreparedWaterArea(0, 0, 64, air, water);
        Map<String, Boolean> placements = new HashMap<>();
        WaterLining.forChunk(area, 16, 32, -64, 320, (x, y, z, sand) -> placements.put(x + "," + y + "," + z, sand));
        int bed = area.bedY(31, 32, 0);
        assertEquals(true, placements.get("31," + bed + ",32"));
        assertEquals(true, placements.get("31," + (bed - 1) + ",32"));
        assertEquals(false, placements.get("31," + (bed - 2) + ",32"));
        assertEquals(
                true, placements.get("32," + area.bedY(32, 32, 0) + ",32"), "sand must also cover the neighbour halo");
        assertEquals(true, placements.get("23,100,32"), "exposed shoreline should have a sand ledge");
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverNaturalBanksTest {
    private static PreparedWaterArea area() {
        int[] water = new int[4096], air = new int[4096];
        Arrays.fill(water, PreparedWaterArea.NONE);
        Arrays.fill(air, 105);
        for (int z = 8; z < 56; z++) for (int x = 8; x < 56; x++) water[z * 64 + x] = 100;
        RiverBanks.shape(83, 0, 0, 64, air, water, new boolean[1024]);
        return new PreparedWaterArea(0, 0, 64, air, water);
    }

    @Test
    void testExposedBankIsMostlySoilInCoherentRepeatablePatches() {
        int sand = 0, soil = 0, same = 0;
        for (int z = -128; z < 0; z++)
            for (int x = -128; x < 0; x++) {
                var m = RiverShorePalette.material(12345, x, z, 1);
                assertEquals(m, RiverShorePalette.material(12345, x, z, 1));
                if (m == RiverShorePalette.Material.SAND) sand++;
                if (m == RiverShorePalette.Material.DIRT || m == RiverShorePalette.Material.COARSE_DIRT) soil++;
                if (m == RiverShorePalette.material(12345, x + 1, z, 1)) same++;
            }
        assertTrue(soil > 128 * 128 * .65);
        assertTrue(sand < 128 * 128 * .2);
        assertTrue(same > 128 * 128 * .75, "bank materials should form patches, not single-block colour noise");
    }

    @Test
    void testSubmergedHalfBlocksLowerTheUpperStep() {
        var a = area();
        int wet = 0, rocks = 0, dry = 0;
        for (int cz = 0; cz < 4; cz++)
            for (int cx = 0; cx < 4; cx++) {
                List<RiverDetailPlanning.Detail> details = new ArrayList<>();
                RiverDetailPlanning.forChunk(a, 83, cx * 16, cz * 16, -64, 320, details::add);
                for (var d : details)
                    if (d.kind().slab()) {
                        if (d.waterlogged()) {
                            wet++;
                            assertEquals(RiverDetailPlanning.Kind.SAND_SLAB, d.kind());
                            assertEquals(a.bedY(d.x(), d.z(), 0), d.y(), "embed the slab in the floor, never above it");
                        } else {
                            dry++;
                            if (d.kind() == RiverDetailPlanning.Kind.STONE_SLAB) rocks++;
                            else
                                assertEquals(
                                        a.bedY(d.x(), d.z(), 0),
                                        d.y(),
                                        "dry soil half blocks must lower the upper step too");
                        }
                    }
            }
        assertTrue(wet > 10);
        assertTrue(rocks > 0);
        assertTrue(rocks < dry * .15, "rocks should be sparse accents");
    }

    @Test
    void wetHalfBlockIsAValidFloorButAnUnsupportedHoleStillRejectsWater() {
        var a = area();
        int x = 16, z = 16, bed = a.bedY(x, z, 0);
        var columns =
                new RiverWaterPlanning.ColumnAccess() {
                    public boolean isAir(int px, int y, int pz) {
                        return y >= a.carvedHeight(px, pz, 105);
                    }

                    public boolean isWater(int px, int y, int pz) {
                        return px == x && pz == z && y == bed;
                    }

                    public boolean isSolidDry(int px, int y, int pz) {
                        return !isWater(px, y, pz) && !isAir(px, y, pz);
                    }

                    public boolean hasBedSupport(int px, int y, int pz) {
                        return isSolidDry(px, y, pz) || isWater(px, y, pz) && isSolidDry(px, y - 1, pz);
                    }
                };
        assertFalse(columns.isSolidDry(x, bed, z));
        assertTrue(columns.hasBedSupport(x, bed, z));
        assertTrue(FluidContainment.canPlace(FluidContainment.Fluid.WATER, a, x, z, bed, 100, -64, 320, columns));
        var hole =
                new RiverWaterPlanning.ColumnAccess() {
                    public boolean isAir(int px, int y, int pz) {
                        return columns.isAir(px, y, pz) || px == x && pz == z && y <= bed;
                    }

                    public boolean isSolidDry(int px, int y, int pz) {
                        return !isAir(px, y, pz);
                    }
                };
        assertFalse(FluidContainment.canPlace(FluidContainment.Fluid.WATER, a, x, z, bed, 100, -64, 320, hole));
    }
}

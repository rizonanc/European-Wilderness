package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverDetailsTest {
    private static PreparedWaterArea area(int ox, int oz) {
        int[] water = new int[4096], air = new int[4096];
        Arrays.fill(water, PreparedWaterArea.NONE);
        Arrays.fill(air, 105);
        for (int z = 8; z < 56; z++) for (int x = 8; x < 56; x++) water[z * 64 + x] = 100;
        RiverBanks.shape(83, ox, oz, 64, air, water, new boolean[1024]);
        return new PreparedWaterArea(ox, oz, 64, air, water);
    }

    private static List<RiverDetailPlanning.Detail> details(PreparedWaterArea a, boolean reverse) {
        var result = new ArrayList<RiverDetailPlanning.Detail>();
        for (int i = 0; i < 16; i++) {
            int chunk = reverse ? 15 - i : i;
            int bx = a.x() + chunk % 4 * 16, bz = a.z() + chunk / 4 * 16;
            RiverDetailPlanning.forChunk(
                    a,
                    83,
                    bx,
                    bz,
                    -64,
                    320,
                    d -> {
                        assertTrue(d.x() >= bx && d.x() < bx + 16 && d.z() >= bz && d.z() < bz + 16);
                        result.add(d);
                    });
        }
        return result;
    }

    @Test
    void testHalfBlocksAndVegetationArePresent() {
        var a = area(-128, -256);
        var forward = details(a, false);
        var reverse = details(a, true);
        assertEquals(new HashSet<>(forward), new HashSet<>(reverse));
        Set<RiverDetailPlanning.Kind> kinds = new HashSet<>();
        for (var d : forward) kinds.add(d.kind());
        assertTrue(kinds.contains(RiverDetailPlanning.Kind.SAND_SLAB));
        assertTrue(kinds.contains(RiverDetailPlanning.Kind.TURF_SLAB));
        assertTrue(kinds.contains(RiverDetailPlanning.Kind.DIRT_SLAB));
        assertTrue(kinds.contains(RiverDetailPlanning.Kind.SEAGRASS));
        assertTrue(kinds.contains(RiverDetailPlanning.Kind.TALL_SEAGRASS));
        assertEquals(forward.size(), new HashSet<>(forward).size(), "no duplicates along chunk borders");
    }

    @Test
    void testPlantsStayUnderWater() {
        var a = area(0, 0);
        Set<String> occupied = new HashSet<>();
        for (var d : details(a, false)) {
            assertTrue(occupied.add(d.x() + "," + d.y() + "," + d.z()));
            int level = a.waterY(d.x(), d.z());
            if (d.kind() == RiverDetailPlanning.Kind.SEAGRASS || d.kind() == RiverDetailPlanning.Kind.TALL_SEAGRASS) {
                assertTrue(d.waterlogged());
                assertTrue(level >= d.y() + 1);
                if (d.kind() == RiverDetailPlanning.Kind.TALL_SEAGRASS) assertTrue(level >= d.y() + 2);
            } else assertEquals(level != PreparedWaterArea.NONE, d.waterlogged());
        }
    }

    @Test
    void testShelvesInterpolateRealOneBlockSteps() {
        var a = area(0, 0);
        int plants = 0, slabs = 0;
        for (var d : details(a, false)) {
            if (d.kind() == RiverDetailPlanning.Kind.SEAGRASS || d.kind() == RiverDetailPlanning.Kind.TALL_SEAGRASS) {
                plants++;
                continue;
            }
            slabs++;
            if (d.kind() == RiverDetailPlanning.Kind.STONE_SLAB) continue;
            boolean raised = false;
            for (int[] offset : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}})
                if (a.carvedHeight(d.x() + offset[0], d.z() + offset[1], 0) == d.y()) raised = true;
            assertTrue(raised, "a half block must soften a real height step");
        }
        assertTrue(slabs > 10);
        assertTrue(plants > 10);
        assertTrue(plants < a.wetColumns() / 3);
    }

    @Test
    void waterloggedSlabsAndPlantsAreAcceptedOnRepeatedWaterPlanning() {
        var a = area(0, 0);
        Set<String> decorations = new HashSet<>();
        for (var d : details(a, false))
            if (d.waterlogged()) {
                decorations.add(d.x() + "," + d.y() + "," + d.z());
                if (d.kind() == RiverDetailPlanning.Kind.TALL_SEAGRASS)
                    decorations.add(d.x() + "," + (d.y() + 1) + "," + d.z());
            }
        var columns =
                new RiverWaterPlanning.ColumnAccess() {
                    public boolean isAir(int x, int y, int z) {
                        return y >= a.carvedHeight(x, z, 105) && !decorations.contains(x + "," + y + "," + z);
                    }

                    public boolean isWater(int x, int y, int z) {
                        return decorations.contains(x + "," + y + "," + z);
                    }

                    public boolean isSolidDry(int x, int y, int z) {
                        return y < a.carvedHeight(x, z, 105) && !isWater(x, y, z);
                    }

                    public boolean hasBedSupport(int x, int y, int z) {
                        return isSolidDry(x, y, z) || isWater(x, y, z) && y == a.bedY(x, z, 0);
                    }
                };
        int expected = 0;
        for (int z = 16; z < 32; z++) for (int x = 16; x < 32; x++) expected += a.waterY(x, z) - a.bedY(x, z, 0);
        assertEquals(expected, RiverWaterPlanning.planChunk(a, 1, 1, -64, 320, columns).size());
    }

    @Test
    void testAlcovesAreOnlyOneConnectedColumnInLowBanks() {
        int[] water = new int[4096], air = new int[4096];
        Arrays.fill(water, PreparedWaterArea.NONE);
        Arrays.fill(air, 101);
        for (int z = 8; z < 56; z++) for (int x = 24; x < 39; x++) water[z * 64 + x] = 100;
        int[] original = water.clone();
        ShoreAlcoves.extend(83, 0, 0, 64, air, water);
        int added = 0;
        for (int p = 0; p < water.length; p++) {
            if (original[p] != PreparedWaterArea.NONE) {
                assertEquals(original[p], water[p]);
                continue;
            }
            if (water[p] == PreparedWaterArea.NONE) continue;
            added++;
            int x = p % 64, z = p / 64;
            assertTrue(x >= 8 && z >= 8 && x < 56 && z < 56);
            int adjacent = 0;
            for (int q : new int[] {p - 1, p + 1, p - 64, p + 64})
                if (original[q] != PreparedWaterArea.NONE) adjacent++;
            assertEquals(1, adjacent, "must touch original water, not chain across the bank");
        }
        assertTrue(added > 0);
        Arrays.fill(water, PreparedWaterArea.NONE);
        for (int z = 8; z < 56; z++) {
            water[z * 64 + 24] = 100;
            water[z * 64 + 26] = 102;
        }
        ShoreAlcoves.extend(83, 0, 0, 64, air, water);
        for (int z = 8; z < 56; z++) assertEquals(PreparedWaterArea.NONE, water[z * 64 + 25]);
    }

    @Test
    void testSteepBanksRemainDryAndTheFirstThreeWetBlocksStayShallow() {
        int[] water = new int[4096], air = new int[4096];
        Arrays.fill(water, PreparedWaterArea.NONE);
        Arrays.fill(air, 120);
        for (int z = 8; z < 56; z++) for (int x = 24; x <= 38; x++) water[z * 64 + x] = 100;
        int[] original = water.clone();
        ShoreAlcoves.extend(7, 0, 0, 64, air, water);
        assertArrayEquals(original, water);
        RiverBanks.shape(7, 0, 0, 64, air, water, new boolean[1024]);
        for (int x = 24; x <= 26; x++) assertEquals(100, air[32 * 64 + x]);
        assertTrue(101 - air[32 * 64 + 31] <= 4, "the main river must not immediately become a deep trench");
    }
}

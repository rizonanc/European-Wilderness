package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalChannelsTest {
    private int[][] terrain(int side, boolean valley) {
        int[][] raw = new int[side][side];
        for (int z = 0; z < side; z++)
            for (int x = 0; x < side; x++)
                raw[z][x] = 66 + (side - x) / 8 + (valley ? (z - side / 2) * (z - side / 2) / 180 : 0);
        return raw;
    }

    private DrainagePlanner.DrainagePlan plan(int[][] raw) {
        int n = raw.length / 2;
        int[][] coarse = new int[n][n];
        for (int z = 0; z < n; z++) for (int x = 0; x < n; x++) coarse[z][x] = raw[z * 2][x * 2] - 1;
        return DrainagePlanner.planPrepared(7, 0, 0, coarse);
    }

    @Test
    void quantizedPlanarSlopesDoNotQualifyAsValleysButConcaveValleysDo() {
        for (int direction = 0; direction < 8; direction++) {
            int dx = DrainagePlanner.DX[direction], dz = DrainagePlanner.DZ[direction];
            int[][] raw = new int[128][128];
            for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) raw[z][x] = 100 + (x * 3 + z * 2) / 8;
            assertFalse(NaturalChannels.valley(raw, 64, 64, dx, dz));
        }
        assertTrue(NaturalChannels.valley(terrain(256, true), 128, 128, 1, 0));
    }

    @Test
    void testParallelArtificialDrainsAreRemoved() {
        int[] counts = new int[2];
        for (int mode = 0; mode < 2; mode++) {
            int[][] raw = terrain(256, mode == 1);
            var plan = plan(raw);
            int n = plan.width();
            boolean[] eligible = new boolean[n * n];
            for (int c = 0; c < eligible.length; c++) eligible[c] = plan.accumulation(c % n, c / n) >= 96;
            boolean[] selected = RiverBranches.select(plan, eligible, 7, 0, 0),
                    keep = NaturalChannels.select(plan, selected, raw);
            assertArrayEquals(keep, NaturalChannels.select(plan, selected, raw));
            for (int c = 0; c < keep.length; c++)
                if (keep[c]) {
                    counts[mode]++;
                    assertTrue(selected[c]);
                    int d = plan.direction(c % n, c / n);
                    if (d < 0) continue;
                    int nx = c % n + DrainagePlanner.DX[d], nz = c / n + DrainagePlanner.DZ[d];
                    if (nx >= 0 && nz >= 0 && nx < n && nz < n && selected[nz * n + nx]) assertTrue(keep[nz * n + nx]);
                }
        }
        assertEquals(0, counts[0]);
        assertTrue(counts[1] > 20, "valley retained=" + counts[1]);
    }

    @Test
    void thinDiagonalRidgesBecomeShallowSandBedButWideIslandsAndDifferentWaterLevelsStay() {
        int side = 64;
        int[] air = new int[side * side], water = new int[air.length];
        Arrays.fill(air, 100);
        Arrays.fill(water, 100);
        for (int i = 20; i < 28; i++) {
            int p = i * side + i;
            air[p] = 102;
            water[p] = PreparedWaterArea.NONE;
        }
        for (int z = 38; z < 42; z++)
            for (int x = 38; x < 42; x++) {
                air[z * side + x] = 102;
                water[z * side + x] = PreparedWaterArea.NONE;
            }
        int p = 30 * side + 30;
        water[p] = PreparedWaterArea.NONE;
        air[p] = 101;
        water[p + 1] = 101;
        BankNeedles.lower(side, air, water);
        for (int i = 20; i < 28; i++) {
            assertEquals(100, water[i * side + i]);
            assertEquals(100, air[i * side + i]);
        }
        assertEquals(PreparedWaterArea.NONE, water[39 * side + 39]);
        assertEquals(PreparedWaterArea.NONE, water[p]);
    }

    @Test
    void testProductionRevisionFourReachesTheSeaThroughAValley() {
        int side = 256;
        int[][] raw = terrain(side, true);
        for (int z = 0; z < side; z++)
            for (int x = 0; x < side; x++)
                raw[z][x] = x >= 224 ? 58 : 64 + Math.max(0, (210 - x) / 8) + (z - side / 2) * (z - side / 2) / 180;
        boolean reached = false;
        for (int seed = 0; seed < 8; seed++) {
            var result = PreparedWaterArea.calculate(seed, 0, 0, raw, null, 4);
            if (Arrays.stream(result.water()).anyMatch(w -> w == 62)) reached = true;
        }
        assertTrue(reached, "filtering cannot sever every valid river mouth");
    }
}

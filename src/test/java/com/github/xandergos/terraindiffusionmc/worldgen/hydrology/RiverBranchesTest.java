package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RiverBranchesTest {
    private static DrainagePlanner.DrainagePlan terrain() {
        int[][] ground = new int[64][64];
        var random = new Random(98317);
        for (int z = 0; z < 64; z++)
            for (int x = 0; x < 64; x++) ground[z][x] = 200 - x + Math.abs(z - 32) * 2 + random.nextInt(7);
        return DrainagePlanner.planCoarse(8, 1024, -512, ground);
    }

    @Test
    void keepsEverySelectedBranchConnectedDownstream() {
        var p = terrain();
        boolean[] eligible = new boolean[4096];
        for (int c = 0; c < eligible.length; c++) eligible[c] = p.accumulation(c % 64, c / 64) >= 9;
        boolean[] keep = RiverBranches.select(p, eligible, 8, 1024, -512);
        int retained = 0;
        for (int c = 0; c < keep.length; c++)
            if (keep[c]) {
                retained++;
                assertTrue(eligible[c]);
                int d = p.direction(c % 64, c / 64);
                if (d >= 0) {
                    int next = (c / 64 + DrainagePlanner.DZ[d]) * 64 + c % 64 + DrainagePlanner.DX[d];
                    if (eligible[next]) assertTrue(keep[next], "disconnected branch at " + c);
                }
            }
        assertTrue(retained > 0);
    }

    @Test
    void confluencesHaveMainStemAndAtMostTwoSideTributaries() {
        var p = terrain();
        boolean[] eligible = new boolean[4096];
        Arrays.fill(eligible, true);
        boolean[] keep = RiverBranches.select(p, eligible, 8, 1024, -512);
        int[] incoming = new int[4096];
        for (int c = 0; c < keep.length; c++)
            if (keep[c]) {
                int d = p.direction(c % 64, c / 64);
                if (d >= 0) incoming[(c / 64 + DrainagePlanner.DZ[d]) * 64 + c % 64 + DrainagePlanner.DX[d]]++;
            }
        for (int count : incoming) assertTrue(count <= 3);
    }

    @Test
    void branchingIsRepeatableForTheSameWorldAndCoordinates() {
        var p = terrain();
        boolean[] eligible = new boolean[4096];
        Arrays.fill(eligible, true);
        assertArrayEquals(
                RiverBranches.select(p, eligible, 8, 1024, -512), RiverBranches.select(p, eligible, 8, 1024, -512));
        boolean[] empty = RiverBranches.select(p, new boolean[4096], 8, 1024, -512);
        for (boolean selected : empty) assertFalse(selected);
    }

    @Test
    void repeatedParallelFeedsDoNotBecomeACombAlongTheMainRiver() {
        int[][] ground = new int[64][64];
        for (int[] row : ground) Arrays.fill(row, 500);
        boolean[] eligible = new boolean[4096];
        for (int z = 0; z < 64; z++) {
            ground[z][8] = 400 - z * 3;
            eligible[z * 64 + 8] = true;
        }
        for (int z = 8; z <= 56; z += 4)
            for (int x = 9; x <= 55; x++) {
                ground[z][x] = 400 - z * 3 + x - 8;
                eligible[z * 64 + x] = true;
            }
        var p = DrainagePlanner.planCoarse(8, 0, 0, ground);
        boolean[] keep = RiverBranches.select(p, eligible, 8, 0, 0);
        int before = confluences(p, eligible), after = confluences(p, keep);
        assertTrue(before >= 10, "fixture must contain the densely repeated lateral feeds");
        assertTrue(after > 0, "retain substantial tributaries rather than deleting every branch");
        assertTrue(after <= before / 2, "the whole stem must not retain a tributary at every successive junction");
        List<Integer> joins = new ArrayList<>();
        int[] incoming = incoming(p, keep);
        for (int z = 0; z < 64; z++) if (incoming[z * 64 + 8] > 1) joins.add(z);
        for (int i = 1; i < joins.size(); i++)
            assertTrue((joins.get(i) - joins.get(i - 1)) * 2 >= RiverBranches.MIN_CONFLUENCE_SPACING);
    }

    @Test
    void shortLateralSurfaceRunnelsAreNotKeptAsRivers() {
        int[][] ground = new int[64][64];
        for (int[] row : ground) Arrays.fill(row, 500);
        boolean[] eligible = new boolean[4096];
        for (int z = 0; z < 64; z++) {
            ground[z][8] = 400 - z * 3;
            eligible[z * 64 + 8] = true;
        }
        for (int z = 20; z <= 56; z += 4)
            for (int x = 9; x <= 13; x++) {
                ground[z][x] = 400 - z * 3 + x - 8;
                eligible[z * 64 + x] = true;
            }
        var p = DrainagePlanner.planCoarse(8, 0, 0, ground);
        boolean[] keep = RiverBranches.select(p, eligible, 8, 0, 0);
        assertTrue(confluences(p, eligible) > 5);
        assertEquals(0, confluences(p, keep));
        assertTrue(keep[60 * 64 + 8], "the downstream main stem must remain present");
    }

    private static int[] incoming(DrainagePlanner.DrainagePlan p, boolean[] selected) {
        int width = p.width();
        int[] incoming = new int[selected.length];
        for (int c = 0; c < selected.length; c++)
            if (selected[c]) {
                int d = p.direction(c % width, c / width);
                if (d >= 0) {
                    int target = (c / width + DrainagePlanner.DZ[d]) * width + c % width + DrainagePlanner.DX[d];
                    if (selected[target]) incoming[target]++;
                }
            }
        return incoming;
    }

    private static int confluences(DrainagePlanner.DrainagePlan p, boolean[] selected) {
        return (int) Arrays.stream(incoming(p, selected)).filter(n -> n > 1).count();
    }
}

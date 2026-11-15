package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverCurvesTest {
    private record Point(int x, int z, int y) {}

    private record Fixture(DrainagePlanner.DrainagePlan plan, boolean[] channels, int[][] ground) {}

    private static Fixture fixture(boolean steep) {
        int[][] base = new int[32][32];
        for (int[] row : base) Arrays.fill(row, 500);
        for (int z = 4; z <= 20; z++) base[z][8] = 200 - (z - 4) * 2;
        for (int x = 8; x < 32; x++) base[20][x] = 168 - (x - 8) * 2;
        var plan = DrainagePlanner.planCoarse(8, 0, 0, base);
        boolean[] channels = new boolean[32 * 32];
        int c = 4 * 32 + 8;
        while (true) {
            channels[c] = true;
            int d = plan.direction(c % 32, c / 32);
            if (d < 0) break;
            c = (c / 32 + DrainagePlanner.DZ[d]) * 32 + c % 32 + DrainagePlanner.DX[d];
        }
        int[][] ground = new int[64][64];
        for (int z = 0; z < 64; z++) for (int x = 0; x < 64; x++) ground[z][x] = steep ? base[z / 2][x / 2] + 1 : 101;
        return new Fixture(plan, channels, ground);
    }

    private static Map<Integer, List<Point>> samples(Fixture f) {
        Map<Integer, List<Point>> result = new LinkedHashMap<>();
        RiverCurves.rasterize(
                f.plan,
                f.channels,
                f.ground,
                (c, x, z, y, dx, dz, slope) ->
                        result.computeIfAbsent(c, key -> new ArrayList<>()).add(new Point(x, z, y)));
        return result;
    }

    @Test
    void roundsCornerButPinsHeadwaterAndOutlet() {
        var f = fixture(false);
        var paths = samples(f);
        assertEquals(new Point(16, 8, 200), paths.get(4 * 32 + 8).getFirst());
        assertEquals(new Point(62, 40, 122), paths.get(20 * 32 + 30).getLast());
        assertTrue(
                paths.entrySet().stream()
                        .anyMatch(e -> {
                            Point first = e.getValue().getFirst();
                            int c = e.getKey();
                            return Math.hypot(first.x - c % 32 * 2, first.z - c / 32 * 2) > 2;
                        }),
                "a bend should actually move away from the old angular centreline");
        for (var entry : paths.entrySet()) {
            Point first = entry.getValue().getFirst();
            int c = entry.getKey();
            assertTrue(Math.hypot(first.x - c % 32 * 2, first.z - c / 32 * 2) <= RiverCurves.MAX_SHIFT_BLOCKS + 1);
        }
    }

    @Test
    void connectedSamplesAndSharedJunctionsNeverRiseDownstream() {
        var f = fixture(false);
        var paths = samples(f);
        for (var entry : paths.entrySet()) {
            var points = entry.getValue();
            for (int i = 1; i < points.size(); i++) {
                var a = points.get(i - 1);
                var b = points.get(i);
                assertTrue(Math.abs(a.x - b.x) <= 1 && Math.abs(a.z - b.z) <= 1);
                assertTrue(b.y <= a.y, "smoothing must not introduce uphill water levels");
            }
            int c = entry.getKey(), d = f.plan.direction(c % 32, c / 32);
            int next = (c / 32 + DrainagePlanner.DZ[d]) * 32 + c % 32 + DrainagePlanner.DX[d];
            if (paths.containsKey(next)) assertEquals(points.getLast(), paths.get(next).getFirst());
        }
    }

    @Test
    void keepsOriginalValleyWhenACurveWouldCutThroughAHighRidge() {
        var f = fixture(true);
        for (var points : samples(f).values())
            for (var p : points) assertTrue(f.ground[p.z][p.x] - 1 - p.y <= 10, "curve crossed the steep valley wall");
    }

    @Test
    void outputIsRepeatableAndDoesNotModifyInputTerrain() {
        var f = fixture(false);
        var before = Arrays.stream(f.ground).map(int[]::clone).toArray(int[][]::new);
        assertEquals(samples(f), samples(f));
        assertTrue(Arrays.deepEquals(before, f.ground));
    }

    @Test
    void allTributariesShareTheSameMovedConfluenceAndStayInsideTheGrid() {
        int[][] base = new int[32][32];
        for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) base[z][x] = 200 - x + Math.abs(z - 16) * 2;
        var plan = DrainagePlanner.planCoarse(3, -1024, 512, base);
        boolean[] channels = new boolean[1024];
        Arrays.fill(channels, true);
        var paths = samples(new Fixture(plan, channels, new int[64][64]));
        Map<Integer, List<Point>> ends = new HashMap<>();
        for (var entry : paths.entrySet()) {
            int c = entry.getKey(), d = plan.direction(c % 32, c / 32);
            int target = (c / 32 + DrainagePlanner.DZ[d]) * 32 + c % 32 + DrainagePlanner.DX[d];
            ends.computeIfAbsent(target, key -> new ArrayList<>()).add(entry.getValue().getLast());
            for (var point : entry.getValue()) assertTrue(point.x >= 0 && point.x < 64 && point.z >= 0 && point.z < 64);
        }
        boolean testedConfluence = false;
        for (var entry : ends.entrySet())
            if (entry.getValue().size() > 1) {
                testedConfluence = true;
                var junction = entry.getValue().getFirst();
                for (var endpoint : entry.getValue()) assertEquals(junction, endpoint);
                if (paths.containsKey(entry.getKey())) assertEquals(junction, paths.get(entry.getKey()).getFirst());
            }
        assertTrue(testedConfluence);
    }
}

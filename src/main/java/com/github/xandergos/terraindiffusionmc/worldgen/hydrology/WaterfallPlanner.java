package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;

/** Chooses one real river descent, then concentrates it into a local cliff and pool. */
final class WaterfallPlanner {
    private static final int NONE = PreparedWaterArea.NONE;

    static List<Waterfall> select(int originX, int originZ, int side, int[][] raw, int[] water) {
        return select(originX, originZ, side, raw, water, null);
    }

    static List<Waterfall> select(
            int originX, int originZ, int side, int[][] raw, int[] water, PreparedWaterArea.Owner owner) {
        Waterfall best = null;
        double bestScore = -1;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int z = 24; z < side - 24; z += 2)
            for (int x = 24; x < side - 24; x += 2) {
                int upper = water[z * side + x];
                if (upper < 71) continue;
                for (int[] dir : directions) {
                    int dx = dir[0], dz = dir[1];
                    boolean inlet = true;
                    for (int s = -4; s <= 0; s++) {
                        int y = water[(z + dz * s) * side + x + dx * s];
                        if (y < upper || y > upper + 2) inlet = false;
                    }
                    if (!inlet) continue;
                    for (int run = 6; run <= 16; run++) {
                        int lower = water[(z + dz * run) * side + x + dx * run], drop = upper - lower;
                        if (lower < 63 || drop < 8 || drop > 24 || drop / (double) run < .6) continue;
                        boolean valid = true;
                        int previous = upper;
                        for (int s = 0; s <= run + 4; s++) {
                            int y = water[(z + dz * s) * side + x + dx * s];
                            if (y == NONE || s <= run && y > previous || s >= run && (y < lower - 2 || y > lower + 2))
                                valid = false;
                            previous = y;
                        }
                        // The descent must exist in the original terrain too, rather
                        // than being only a widened stream or lake-mask artefact.
                        int naturalDrop = raw[z - dz * 4][x - dx * 4] - raw[z + dz * (run + 4)][x + dx * (run + 4)];
                        if (naturalDrop < 8) valid = false;
                        for (int s : new int[] {-2, 0, run})
                            for (int t = -1; t <= 1; t++)
                                if (water[(z + dz * s + dx * t) * side + x + dx * s - dz * t] < 63) valid = false;
                        if (!valid) continue;
                        int width = 3;
                        if (wetCross(water, side, x, z, dx, dz, 0, 2, upper - 1)) width = 5;
                        if (width == 5 && wetCross(water, side, x, z, dx, dz, 0, 3, upper - 1)) width = 7;
                        var fall = new Waterfall(originX + x, originZ + z, dx, dz, upper, lower, width, run);
                        if (!fall.inside(originX, originZ, side)) continue;
                        if (owner != null && !fall.inside(owner.x(), owner.z(), owner.side())) continue;
                        double score = drop / (double) run + drop * .01;
                        if (score > bestScore) {
                            best = fall;
                            bestScore = score;
                        }
                    }
                }
            }
        return best == null ? List.of() : List.of(best);
    }

    private static boolean wetCross(int[] water, int side, int x, int z, int dx, int dz, int s, int r, int minimum) {
        for (int t = -r; t <= r; t++) {
            int level = water[(z + dz * s + dx * t) * side + x + dx * s - dz * t];
            if (level < minimum) return false;
        }
        return true;
    }

    static void apply(Waterfall fall, int originX, int originZ, int side, int[] air, int[] water) {
        apply(fall, originX, originZ, side, air, water, null);
    }

    static void apply(Waterfall fall, int originX, int originZ, int side, int[] air, int[] water, short[] flow) {
        if (!fall.inside(originX, originZ, side) || air.length != side * side || water.length != air.length)
            throw new IllegalArgumentException("Waterfall footprint does not fit terrain");
        for (int s = fall.start(); s <= fall.end(); s++)
            for (int t = -fall.margin(); t <= fall.margin(); t++) {
                int x = fall.worldX(s, t) - originX, z = fall.worldZ(s, t) - originZ, p = z * side + x;
                int radius = fall.wetRadius(s), level = s <= 0 ? fall.upperY() : fall.lowerY();
                if (Math.abs(t) <= radius) {
                    int sideDepth = 1 + radius - Math.abs(t);
                    int impact = s > 0 && s <= 4 && Math.abs(t) <= fall.radius() ? 2 : 0;
                    int depth = s <= 0 ? Math.min(3, sideDepth) : Math.min(6, sideDepth + impact);
                    // Preserve the actual outlet's slightly lower level at the far edge.
                    if (s > fall.run() && water[p] != NONE) level = Math.min(level, water[p]);
                    water[p] = level;
                    air[p] = level - depth + 1;
                } else {
                    int target = level + 1 + (Math.abs(t) - radius - 1) / 2;
                    double blend = Math.min(1, (Math.abs(t) - radius - 1) / 3.0);
                    air[p] = Math.max(level + 1, (int) Math.round(target + (air[p] - target) * blend));
                    water[p] = NONE;
                }
                if (flow != null && water[p] <= 62) flow[p] = 0;
            }
    }
}

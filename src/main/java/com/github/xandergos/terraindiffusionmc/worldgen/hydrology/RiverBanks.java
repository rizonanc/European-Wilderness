package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.Arrays;

/** First-air heights for a shallow shelf, stepped bed and a narrow dry bank. */
final class RiverBanks {
    static final int BANK_WIDTH = 6;
    private static final int NONE = PreparedWaterArea.NONE;

    static void shape(long seed, int originX, int originZ, int side, int[] air, int[] water, boolean[] lake) {
        shape(seed, originX, originZ, side, air, water, lake, null);
    }

    static void shape(
            long seed, int originX, int originZ, int side, int[] air, int[] water, boolean[] lake, short[] currents) {
        shape(seed, originX, originZ, side, air, water, lake, currents, p -> 100);
    }

    static void shape(
            long seed,
            int originX,
            int originZ,
            int side,
            int[] air,
            int[] water,
            boolean[] lake,
            short[] currents,
            java.util.function.IntUnaryOperator depthVariation) {
        int count = water.length, n = side / 2;
        boolean[] ocean = RiverMouths.oceanFringe(side, air, water);
        int[] distance = new int[count], queue = new int[count];
        int read = 0, write = 0;
        // Start at wet shore columns, then measure distance into each water body.
        for (int z = 1; z < side - 1; z++)
            for (int x = 1; x < side - 1; x++) {
                int p = z * side + x;
                if (water[p] != NONE
                        && (water[p - 1] == NONE
                                || water[p + 1] == NONE
                                || water[p - side] == NONE
                                || water[p + side] == NONE)) {
                    distance[p] = 1;
                    queue[write++] = p;
                }
            }
        while (read < write) {
            int p = queue[read++];
            for (int q : new int[] {p - 1, p + 1, p - side, p + side})
                if (water[q] != NONE && distance[q] == 0) {
                    distance[q] = distance[p] + 1;
                    queue[write++] = q;
                }
        }
        for (int p = 0; p < count; p++)
            if (water[p] != NONE) {
                if (water[p] == 62) {
                    air[p] = Math.max(62 - PreparedWaterArea.MAX_DEPTH + 1, Math.min(air[p], 62));
                    continue;
                }
                int naturalDepth = water[p] - (air[p] - 1), d = distance[p];
                boolean inLake = lake[(p / side / 2) * n + (p % side / 2)] && naturalDepth > 0;
                int ripple = d <= 4 ? 0 : (int) Math.round(relief(seed, originX + p % side, originZ + p / side));
                int depth =
                        inLake
                                ? Math.min(naturalDepth, Math.max(1, d - 2 + ripple))
                                : Math.min(5, Math.max(1, 1 + (d - 1) / 3 + ripple));
                if (!inLake && d >= 2 && currents != null && currents[p] != 0) {
                    int variation =
                            (int)
                                    Math.round(
                                            relief(seed ^ 0x4b17L, originX + p % side, originZ + p / side)
                                                    * (depthVariation.applyAsInt(p) / 100.0));
                    depth = Math.clamp(depth + variation, 1, 6);
                }
                depth = Math.min(PreparedWaterArea.MAX_DEPTH, depth);
                air[p] = Math.max(-63, water[p] - depth + 1);
            }

        // Distance and water level on the dry side. Equally close shores use
        // the higher level, so a high tributary cannot leak through a low bank.
        Arrays.fill(distance, 0);
        int[] level = new int[count];
        Arrays.fill(level, NONE);
        read = 0;
        write = 0;
        for (int z = 1; z < side - 1; z++)
            for (int x = 1; x < side - 1; x++) {
                int p = z * side + x;
                if (water[p] != NONE) continue;
                int adjacent =
                        Math.max(Math.max(water[p - 1], water[p + 1]), Math.max(water[p - side], water[p + side]));
                if (adjacent != NONE) {
                    distance[p] = 1;
                    level[p] = adjacent;
                    queue[write++] = p;
                }
            }
        while (read < write) {
            int p = queue[read++], d = distance[p];
            if (d >= BANK_WIDTH) continue;
            for (int q : new int[] {p - 1, p + 1, p - side, p + side}) {
                int x = q % side, z = q / side;
                if (x <= 0 || z <= 0 || x >= side - 1 || z >= side - 1 || water[q] != NONE) continue;
                if (distance[q] == 0) {
                    distance[q] = d + 1;
                    level[q] = level[p];
                    queue[write++] = q;
                } else if (distance[q] == d + 1) level[q] = Math.max(level[q], level[p]);
            }
        }
        for (int p = 0; p < count; p++)
            if (distance[p] > 0) {
                if (ocean != null && ocean[p]) continue;
                int d = distance[p], minimum = level[p] + 1;
                int terrace = minimum + (d - 1) / 2;
                // Cut the immediate bank, then blend back into unmodified terrain.
                // Large mountain slopes remain outside this small shoreline band.
                double blend = Math.max(0, (d - 3) / 3.0);
                int shaped = (int) Math.round(terrace + (air[p] - terrace) * blend);
                air[p] = Math.max(minimum, Math.min(air[p], shaped));
            }
    }

    static double relief(long seed, int x, int z) {
        int gx = Math.floorDiv(x, 12), gz = Math.floorDiv(z, 12);
        double tx = Math.floorMod(x, 12) / 12.0, tz = Math.floorMod(z, 12) / 12.0;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = noise(seed, gx, gz) * (1 - tx) + noise(seed, gx + 1, gz) * tx;
        double b = noise(seed, gx, gz + 1) * (1 - tx) + noise(seed, gx + 1, gz + 1) * tx;
        return (a * (1 - tz) + b * tz) * 1.35;
    }

    private static double noise(long seed, int x, int z) {
        long v = seed ^ ((long) x << 32) ^ (z & 0xffffffffL);
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        v ^= v >>> 31;
        return (v >>> 11) * 0x1.0p-53 * 2 - 1;
    }
}

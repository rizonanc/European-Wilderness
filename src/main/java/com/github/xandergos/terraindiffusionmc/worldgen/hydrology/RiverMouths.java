package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;

/** Short, low coastal connections. 62 is the actual top vanilla sea-water block. */
final class RiverMouths {
    static void connect(DrainagePlanner.DrainagePlan plan, boolean[] channels, int[][] raw, int[] water) {
        int side = raw.length, n = plan.width();
        Set<Integer> used = new HashSet<>();
        for (int c = 0; c < channels.length; c++)
            if (channels[c] && plan.filledHeight(c % n, c / n) <= 65) {
                int d = plan.direction(c % n, c / n);
                if (d < 0) continue;
                int nx = c % n + DrainagePlanner.DX[d], nz = c / n + DrainagePlanner.DZ[d];
                if (raw[nz * 2][nx * 2] > 64) continue;
                int cx = c % n * 2, cz = c / n * 2, best = -1, bestDistance = Integer.MAX_VALUE;
                for (int z = Math.max(10, cz - 16); z <= Math.min(side - 11, cz + 16); z++)
                    for (int x = Math.max(10, cx - 16); x <= Math.min(side - 11, cx + 16); x++) {
                        int dist = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                        if (raw[z][x] <= 62 && dist < bestDistance) {
                            best = z * side + x;
                            bestDistance = dist;
                        }
                    }
                if (best < 0 || !used.add(best)) continue;
                int start = -1, dist = Integer.MAX_VALUE;
                for (int z = Math.max(10, cz - 12); z <= Math.min(side - 11, cz + 12); z++)
                    for (int x = Math.max(10, cx - 12); x <= Math.min(side - 11, cx + 12); x++) {
                        int w = water[z * side + x], distance = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                        if (w >= 63 && w <= 64 && distance < dist) {
                            start = z * side + x;
                            dist = distance;
                        }
                    }
                if (start >= 0) connectOne(raw, water, start % side, start / side, best % side, best / side);
            }
    }

    static boolean connectOne(int[][] raw, int[] water, int x, int z, int seaX, int seaZ) {
        int side = raw.length;
        double length = Math.hypot(seaX - x, seaZ - z);
        if (length > 24 || raw[seaZ][seaX] > 62) return false;
        int steps = Math.max(1, (int) Math.ceil(length * 2));
        for (int s = 0; s <= steps; s++) {
            int px = (int) Math.round(x + (seaX - x) * s / (double) steps),
                    pz = (int) Math.round(z + (seaZ - z) * s / (double) steps);
            if (raw[pz][px] > 66) return false;
        }
        double dx = length == 0 ? 1 : (seaX - x) / length, dz = length == 0 ? 0 : (seaZ - z) / length;
        int endX = seaX, endZ = seaZ;
        for (int s = 1; s <= 6; s++) {
            int px = (int) Math.round(seaX + dx * s), pz = (int) Math.round(seaZ + dz * s);
            if (px < 10 || pz < 10 || px >= side - 10 || pz >= side - 10 || raw[pz][px] > 62) break;
            endX = px;
            endZ = pz;
        }
        steps = Math.max(1, (int) Math.ceil(Math.hypot(endX - x, endZ - z) * 2));
        for (int s = 0; s <= steps; s++) {
            int px = (int) Math.round(x + (endX - x) * s / (double) steps),
                    pz = (int) Math.round(z + (endZ - z) * s / (double) steps);
            for (int oz = -3; oz <= 3; oz++)
                for (int ox = -3; ox <= 3; ox++)
                    if (ox * ox + oz * oz <= 10) {
                        int wx = px + ox, wz = pz + oz;
                        if (wx < 8 || wz < 8 || wx >= side - 8 || wz >= side - 8 || raw[wz][wx] > 66) continue;
                        int p = wz * side + wx;
                        if (water[p] > 64) continue;
                        water[p] = 62;
                    }
        }
        return true;
    }

    /** Mark only already submerged natural cells connected to a new mouth. */
    static boolean[] oceanFringe(int side, int[] air, int[] water) {
        boolean any = false;
        for (int w : water)
            if (w == 62) {
                any = true;
                break;
            }
        if (!any) return null;
        boolean[] ocean = new boolean[water.length];
        int[] queue = new int[water.length];
        int read = 0, write = 0;
        for (int z = 1; z < side - 1; z++)
            for (int x = 1; x < side - 1; x++) {
                int p = z * side + x;
                if (water[p] == 62) {
                    ocean[p] = true;
                    queue[write++] = p;
                }
            }
        for (int depth = 0; depth < 6 && read < write; depth++) {
            int end = write;
            while (read < end) {
                int p = queue[read++];
                for (int d = 0; d < 4; d++) {
                    int q = d == 0 ? p - 1 : d == 1 ? p + 1 : d == 2 ? p - side : p + side;
                    int qx = q % side, qz = q / side;
                    if (qx <= 0
                            || qz <= 0
                            || qx >= side - 1
                            || qz >= side - 1
                            || ocean[q]
                            || air[q] > 62
                            || water[q] != PreparedWaterArea.NONE) continue;
                    ocean[q] = true;
                    queue[write++] = q;
                }
            }
        }
        return ocean;
    }
}

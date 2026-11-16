package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;

/** Deterministic bed/shore lining, shared by game code and tests. */
final class WaterLining {
    private static final int[][] NEIGHBOURS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    interface Writer {
        void write(int x, int y, int z, boolean sand);
    }

    static void forChunk(WorldSession.RiverOverlay overlay, int baseX, int baseZ, int bottom, int top, Writer writer) {
        for (int dz = -1; dz <= 16; dz++)
            for (int dx = -1; dx <= 16; dx++) {
                int x = baseX + dx, z = baseZ + dz;
                if (!overlay.isReady(x, z)) continue;
                int water = overlay.waterY(x, z);
                if (water != Integer.MIN_VALUE) {
                    int bed = overlay.bedY(x, z, water + 1);
                    int lowest = bed - 1;
                    for (int[] offset : NEIGHBOURS) {
                        int nx = x + offset[0], nz = z + offset[1], level = overlay.waterY(nx, nz);
                        if (level != Integer.MIN_VALUE) lowest = Math.min(lowest, overlay.bedY(nx, nz, level + 1) + 1);
                    }
                    lowest = Math.max(bottom + 1, lowest);
                    if (lowest - 1 <= top) writer.write(x, lowest - 1, z, false);
                    for (int y = lowest; y <= bed && y <= top; y++) writer.write(x, y, z, true);
                } else {
                    int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
                    for (int[] offset : NEIGHBOURS) {
                        int nx = x + offset[0], nz = z + offset[1], level = overlay.waterY(nx, nz);
                        if (level != Integer.MIN_VALUE) {
                            low = Math.min(low, overlay.bedY(nx, nz, level + 1) - 1);
                            high = Math.max(high, level);
                        }
                    }
                    if (high != Integer.MIN_VALUE) {
                        if (high == 62 && overlay.carvedHeight(x, z, 63) <= 62) continue;
                        int shoreTop = overlay.carvedHeight(x, z, high + 1) - 1;
                        if (low - 1 >= bottom && low - 1 <= top) writer.write(x, low - 1, z, false);
                        for (int y = Math.max(low, bottom); y <= high && y <= top; y++) writer.write(x, y, z, true);
                        if (shoreTop >= high && shoreTop <= high + 1 && shoreTop <= top) {
                            if (shoreTop - 1 >= bottom) writer.write(x, shoreTop - 1, z, false);
                            writer.write(x, shoreTop, z, true);
                        }
                    }
                }
            }
    }
}

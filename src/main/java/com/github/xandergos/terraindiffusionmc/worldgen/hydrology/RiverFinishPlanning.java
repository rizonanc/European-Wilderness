package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;

/** Late features may restore soil/path blocks inside an already planned wet channel. */
final class RiverFinishPlanning {
    interface Access {
        boolean soil(int x, int y, int z);

        void sand(int x, int y, int z);

        void water(int x, int y, int z, boolean surface);

        void removeUnsupportedPlant(int x, int y, int z);
    }

    static void finish(WorldSession.RiverOverlay overlay, int baseX, int baseZ, int bottom, int top, Access blocks) {
        for (int z = baseZ; z < baseZ + 16; z++)
            for (int x = baseX; x < baseX + 16; x++) {
                if (!overlay.isReady(x, z) || overlay.waterfallAt(x, z) != null) continue;
                int bed = overlay.bedY(x, z, 0);
                int level = overlay.waterY(x, z);
                if (level == PreparedWaterArea.NONE) {
                    if (bed >= bottom && bed < top) blocks.removeUnsupportedPlant(x, bed + 1, z);
                    continue;
                }
                if (bed < bottom || level > top || level <= bed || level - bed > PreparedWaterArea.MAX_DEPTH) continue;
                if (blocks.soil(x, bed, z)) blocks.sand(x, bed, z);
                for (int y = bed + 1; y <= level; y++) if (blocks.soil(x, y, z)) blocks.water(x, y, z, y == level);
                for (int y = bed + 1; y <= level && y <= top; y++) blocks.removeUnsupportedPlant(x, y, z);
                if (level < top) blocks.removeUnsupportedPlant(x, level + 1, z);
            }
    }

    private RiverFinishPlanning() {}
}

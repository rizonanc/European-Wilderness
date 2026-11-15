package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

/** A single, connected fringe of shallow water in low banks. Never grows recursively. */
final class ShoreAlcoves {
    static void extend(long seed, int originX, int originZ, int side, int[] air, int[] water) {
        int[] original = water.clone();
        int collar = PreparedWaterArea.COLLAR;
        for (int z = collar; z < side - collar; z++)
            for (int x = collar; x < side - collar; x++) {
                int p = z * side + x;
                if (original[p] != PreparedWaterArea.NONE) continue;
                int count = 0, level = PreparedWaterArea.NONE;
                for (int q : new int[] {p - 1, p + 1, p - side, p + side})
                    if (original[q] != PreparedWaterArea.NONE) {
                        count++;
                        level = original[q];
                    }
                // Do not bridge two waterways, or a height step, through a dry bank.
                if (count != 1 || air[p] - 1 > level + 2 || air[p] - 1 < level - 2) continue;
                boolean otherLevel = false;
                for (int dz = -1; dz <= 1; dz++)
                    for (int dx = -1; dx <= 1; dx++) {
                        int nearby = original[p + dz * side + dx];
                        if (nearby != PreparedWaterArea.NONE && nearby != level) otherLevel = true;
                    }
                if (!otherLevel && RiverBanks.relief(seed ^ 0x538ab974L, originX + x, originZ + z) > .25)
                    water[p] = level;
            }
    }
}

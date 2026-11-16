package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import net.minecraft.util.math.BlockBox;

/** Test a structure piece against the same planned water used by terrain generation. */
public final class VillageWaterGuard {
    private VillageWaterGuard() {}

    public static boolean intersects(WorldSession.RiverOverlay overlay, BlockBox box) {
        for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
            for (int x = box.getMinX(); x <= box.getMaxX(); x++) {
                if (!overlay.isReady(x, z)) continue;
                int water = overlay.waterY(x, z);
                if (water != PreparedWaterArea.NONE
                        && water >= box.getMinY() - 1
                        && overlay.bedY(x, z, water + 1) <= box.getMaxY()) return true;
                var fall = overlay.waterfallAt(x, z);
                if (fall != null
                        && fall.curtain(x, z)
                        && fall.upperY() >= box.getMinY() - 1
                        && fall.lowerY() <= box.getMaxY()) return true;
            }
        }
        return false;
    }
}

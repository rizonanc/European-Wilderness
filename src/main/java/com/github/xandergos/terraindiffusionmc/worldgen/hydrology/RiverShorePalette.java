package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;

/** Coherent soil patches on exposed banks; the submerged bed remains sand. */
final class RiverShorePalette {
    enum Material { DIRT, COARSE_DIRT, GRAVEL, GRASS, SAND }
    record Shore(int distance,int level) {}
    static Shore nearby(WorldSession.RiverOverlay overlay,int x,int z) {
        int distance=4,level=PreparedWaterArea.NONE;
        for(int dz=-3;dz<=3;dz++) for(int dx=-3;dx<=3;dx++) {
            int d=Math.abs(dx)+Math.abs(dz);if(d==0||d>3||d>distance) continue;
            int water=overlay.waterY(x+dx,z+dz);if(water==PreparedWaterArea.NONE) continue;
            if(d<distance) level=water;else level=Math.max(level,water);
            distance=d;
        }
        return new Shore(distance,level);
    }
    static Material material(long seed,int x,int z,int distance) {
        double patch=RiverBanks.relief(seed^0x8235bcd1L,x,z);
        if(patch>.7) return Material.SAND;
        if(patch<-.6) return Material.GRAVEL;
        if(distance>=2&&patch<.38) return Material.GRASS;
        return patch>.1?Material.COARSE_DIRT:Material.DIRT;
    }
}

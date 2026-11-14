package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

public final class RegionalGeology {
    public enum Rock { LIMESTONE, GRANITE, VOLCANIC }

    private RegionalGeology() {}

    public static Rock region(long seed, int x, int z) {
        double province = MountainSurfaceMaterials.valueNoise01(seed ^ 0x3de129a4L, x, z, 1536);
        return province < 0.43 ? Rock.LIMESTONE : province < 0.72 ? Rock.GRANITE : Rock.VOLCANIC;
    }

    public static Block rock(long seed, int x, int z) {
        return switch (region(seed, x, z)) {
            case LIMESTONE -> Blocks.CALCITE;
            case GRANITE -> Blocks.GRANITE;
            case VOLCANIC -> Blocks.ANDESITE;
        };
    }

    public static Block gravel(long seed, int x, int z) {
        if (MountainSurfaceMaterials.valueNoise01(seed ^ 0x79077L, x, z, 22) > 0.46) return Blocks.GRAVEL;
        return rock(seed, x, z);
    }

    static boolean glacial(float temperature, int y) {
        return y >= 210 && temperature - (y - 63) * 0.022 <= -5;
    }

    static MountainSurfaceMaterials.Material glacialSurface(long seed, int x, int z, int y, float temperature, int slope) {
        if (slope > 3 || !glacial(temperature, y)) return MountainSurfaceMaterials.Material.NONE;
        double flow = MountainSurfaceMaterials.valueNoise01(seed ^ 0x7e0195L, x, z, 132);
        if (flow > 0.66) return MountainSurfaceMaterials.Material.GLACIER;
        if (flow >= 0.59 && flow <= 0.62) return MountainSurfaceMaterials.Material.MORAINE;
        return MountainSurfaceMaterials.Material.NONE;
    }
}

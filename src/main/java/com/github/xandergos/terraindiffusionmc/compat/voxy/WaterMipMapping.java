package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Per-mapper classification: ids are local to a Voxy world, never global. */
public interface WaterMipMapping {
    boolean ewh$isWater(int blockId);

    /** A real water mapping for a natural water-covered top voxel; zero for dry/structural blocks. */
    default long ewh$surfaceWaterValue(long voxel) {
        return ewh$isWater(WaterMipPolicy.blockId(voxel)) ? voxel : 0;
    }
}

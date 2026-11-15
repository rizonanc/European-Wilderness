package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Preserve a complete water surface above a bed; leave mixed coast/roof cells alone. */
public final class WaterMipPolicy {
    private WaterMipPolicy() {}

    public static int blockId(long voxel) {
        return (int) ((voxel >>> 27) & 0xFFFFF);
    }

    /** The quick path requires no allocations, map lookups or Minecraft classes. */
    public static int candidate(long original, long upper00, long upper10, long upper01, long upper11) {
        int id = blockId(upper11);
        if (id == 0 || blockId(original) == id) return -1;
        return blockId(upper00) == id && blockId(upper10) == id && blockId(upper01) == id ? id : -1;
    }

    public static long preserve(
            long original, long upper00, long upper10, long upper01, long upper11, WaterMipMapping mapping) {
        int id = candidate(original, upper00, upper10, upper01, upper11);
        // Keep the upper corner's existing water state, biome and light. Never invent ids.
        return id >= 0 && mapping.ewh$isWater(id) ? upper11 : original;
    }
}

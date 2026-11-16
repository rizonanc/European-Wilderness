package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Derived-only CAS migration within an already occupied fine-section child. */
public final class GrassMipRepairData {
    private static final VarHandle ELEMENT = MethodHandles.arrayElementVarHandle(long[].class);

    private GrassMipRepairData() {}

    public static int patch(
            int level, int cx, int cy, int cz, long[] parent, long[] stock, long[] fixed, GrassMipMapping mapping) {
        if (level < 1 || level > 2) throw new IllegalArgumentException("Vegetation migration is L1/L2 only");
        int width = 16 >> level, mask = (1 << (level + 1)) - 1;
        int ox = (cx & mask) * width, oy = (cy & mask) * width, oz = (cz & mask) * width;
        int src = WaterMipRepairData.offset(level), changed = 0;
        for (int y = 0; y < width; y++)
            for (int z = 0; z < width; z++)
                for (int x = 0; x < width; x++, src++) {
                    long before = stock[src], after = fixed[src];
                    if (before == after) continue;
                    int a = mapping.ewh$grassKind(WaterMipPolicy.blockId(before)),
                            b = mapping.ewh$grassKind(WaterMipPolicy.blockId(after));
                    if ((a != GrassMipMapping.AIR && a < GrassMipMapping.SHORT)
                            || (b != GrassMipMapping.AIR && b < GrassMipMapping.SHORT)) continue;
                    int index = ((oy + y) << 10) | ((oz + z) << 5) | (ox + x);
                    if (ELEMENT.compareAndSet(parent, index, before, after)) changed++;
                }
        return changed;
    }
}

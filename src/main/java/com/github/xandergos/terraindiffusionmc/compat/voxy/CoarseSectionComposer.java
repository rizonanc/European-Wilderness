package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.function.*;

/** Pure renderer-only merge. Only owned real/exact columns, including air, win.
 * No Voxy WorldSection, terrain repository, model or filesystem is mutated. */
public final class CoarseSectionComposer {
    @FunctionalInterface
    public interface Protected {
        boolean test(int blockX, int blockZ);
    }

    @FunctionalInterface
    public interface Adjustment {
        double at(int blockX, int blockZ);
    }

    public record Result(int nonAir, byte children, boolean changed, byte protectedChildren) {
        public byte mergeChildren(byte original) {
            return (byte) (children | (original & protectedChildren));
        }
    }

    private CoarseSectionComposer() {}

    public static boolean fullyProtected(int level, int sx, int sz, Protected exact) {
        if (level < 0 || level > 4) throw new IllegalArgumentException("LOD level");
        int side = 32 << level;
        for (int z = 0; z < side; z += 16)
            for (int x = 0; x < side; x += 16) if (!exact.test(sx * side + x + 8, sz * side + z + 8)) return false;
        return true;
    }

    public static Result fill(
            int level,
            int sx,
            int sy,
            int sz,
            int bottom,
            int top,
            CoarseLodPatch.Surface[] field,
            long[] original,
            long[] target,
            Protected exact,
            IntFunction<long[]> palette,
            LongPredicate isAir) {
        return fill(level, sx, sy, sz, bottom, top, field, original, target, exact, palette, isAir, (x, z) -> 0);
    }

    public static Result fill(
            int level,
            int sx,
            int sy,
            int sz,
            int bottom,
            int top,
            CoarseLodPatch.Surface[] field,
            long[] original,
            long[] target,
            Protected exact,
            IntFunction<long[]> palette,
            LongPredicate isAir,
            Adjustment adjustment) {
        return fill(level, sx, sy, sz, bottom, top, field, original, target, exact, palette, isAir, adjustment, null);
    }

    /** Preserve both blocks and air from a loaded chunk awaiting ingest. */
    public static Result fill(
            int level,
            int sx,
            int sy,
            int sz,
            int bottom,
            int top,
            CoarseLodPatch.Surface[] field,
            long[] original,
            long[] target,
            Protected exact,
            IntFunction<long[]> palette,
            LongPredicate isAir,
            Adjustment adjustment,
            Protected preserveOriginal) {
        if (level < 0
                || level > 4
                || field.length != 1024
                || target.length != 32768
                || original != null && original.length != 32768)
            throw new IllegalArgumentException("Coarse section extent");
        int unit = 1 << level, side = 32 * unit;
        int children = 0, nonAir = 0, protectedChildren = 0;
        boolean changed = false;
        for (int z = 0; z < 32; z++)
            for (int x = 0; x < 32; x++) {
                int wx = sx * side + x * unit + unit / 2, wz = sz * side + z * unit + unit / 2, p = z * 32 + x;
                boolean covered = exact.test(wx, wz) || preserveOriginal != null && preserveOriginal.test(wx, wz);
                if (covered) protectedChildren |= 0x11 << ((x >>> 4) | ((z >>> 4) << 1));
                var surface = field[p];
                long[] materials = surface == null ? null : palette.apply(surface.biome());
                int air = surface == null
                        ? bottom
                        : !covered
                                ? Math.max(bottom, Math.min(top, (int)
                                        Math.round(surface.firstAir() + adjustment.at(wx, wz))))
                                : surface.firstAir();
                for (int y = 0; y < 32; y++) {
                    int index = (y << 10) + p, wy = sy * side + y * unit;
                    long old = original == null ? 0 : original[index], value = old;
                    if (!covered) {
                        value = 0;
                        if (surface != null && wy <= top && wy + unit > bottom) {
                            int bed = air - 1;
                            // A coarse cell may straddle bed and water. Keep the water surface,
                            // like the exact water mip path, instead of exposing a sand platform.
                            if (air <= 62 && wy <= 62 && wy + unit > air) value = materials[6];
                            else if (wy <= bed) {
                                int material;
                                if (wy + unit - 1 < bed - unit) material = 1;
                                else if (bed < 62) material = 4;
                                else if (surface.biome() == 32) material = 5;
                                else if (surface.biome() == 5 || surface.biome() == 18) material = 4;
                                else material = surface.biome() == 35 || air > 500 ? 1 : 3;
                                value = materials[material];
                            } else if (wy <= 62) value = materials[6];
                            else if (air > 63
                                    && surface.canopy() > 0
                                    && wy < air + surface.canopy()
                                    && wy + unit > air + surface.canopy() / 2)
                                value = materials[surface.biome() == 215 ? 12 : 10];
                        }
                    }
                    target[index] = value;
                    changed |= value != old;
                    if (!isAir.test(value)) {
                        nonAir++;
                        children |= 1 << ((x >>> 4) | ((z >>> 4) << 1) | ((y >>> 4) << 2));
                    }
                }
            }
        return new Result(nonAir, (byte) (level == 0 ? (nonAir == 0 ? 0 : 255) : children), changed, (byte)
                (level == 0 ? 0 : protectedChildren));
    }
}

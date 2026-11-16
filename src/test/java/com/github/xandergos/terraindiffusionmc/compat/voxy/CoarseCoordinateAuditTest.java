package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CoarseCoordinateAuditTest {
    @Test
    void testSignedSectionCoordinatesVerticalClipping() {
        long[] palette = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        for (int level = 0; level <= 4; level++)
            for (int sx : new int[] {-65, -1, 0, 63, 64})
                for (int sy : new int[] {-2, -1, 0, 1, 4}) {
                    int sz = -sx - 1, unit = 1 << level, side = 32 * unit;
                    long[] raw = new long[32768];
                    CoarseSectionComposer.Protected owned =
                            (x, z) -> Math.floorMod(Math.floorDiv(x, 16) + 3 * Math.floorDiv(z, 16), 11) == 0;
                    var result = CoarseSectionComposer.fill(
                            level,
                            sx,
                            sy,
                            sz,
                            -37,
                            1903,
                            CoarseSectionComposerTest.flat(133),
                            null,
                            raw,
                            owned,
                            b -> palette,
                            v -> v == 0);
                    int count = 0, children = 0;
                    for (int y = 0; y < 32; y++)
                        for (int z = 0; z < 32; z++)
                            for (int x = 0; x < 32; x++) {
                                int wy = sy * side + y * unit,
                                        wx = sx * side + x * unit + unit / 2,
                                        wz = sz * side + z * unit + unit / 2;
                                boolean solid = !owned.test(wx, wz) && wy + unit > -37 && wy <= 132;
                                assertEquals(
                                        solid, raw[(y << 10) | (z << 5) | x] != 0, "L" + level + " sectionY=" + sy);
                                if (solid) {
                                    count++;
                                    children |= 1 << ((x >>> 4) | ((z >>> 4) << 1) | ((y >>> 4) << 2));
                                }
                            }
                    assertEquals(count, result.nonAir());
                    assertEquals(level == 0 ? (count == 0 ? 0 : 255) : children, Byte.toUnsignedInt(result.children()));
                }
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoarseWaterSurfaceTest {
    @Test
    void shallowSeaKeepsWaterInsteadOfSandPlatformAtEveryNativeLevel() {
        long[] palette = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        for (int level = 0; level <= 4; level++)
            for (int firstAir = 48; firstAir <= 62; firstAir++) {
                int sy = Math.floorDiv(62, 32 << level), localY = Math.floorMod(62 >> level, 32);
                long[] raw = new long[32768];
                CoarseSectionComposer.fill(
                        level,
                        -1,
                        sy,
                        -1,
                        -64,
                        1871,
                        CoarseSectionComposerTest.flat(firstAir),
                        null,
                        raw,
                        (x, z) -> false,
                        b -> palette,
                        v -> v == 0);
                assertEquals(6, raw[localY * 1024], "Sea surface L" + level + " firstAir=" + firstAir);
            }
    }

    @Test
    void realAirAndRealSolidRemainUntouchedAtTheSameWaterTransition() {
        long[] palette = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, source = new long[32768], target = new long[32768];
        source[3 * 1024] = 99;
        CoarseSectionComposer.fill(
                4,
                0,
                0,
                0,
                -64,
                1871,
                CoarseSectionComposerTest.flat(57),
                source,
                target,
                (x, z) -> x < 16,
                b -> palette,
                v -> v == 0);
        assertEquals(99, target[3 * 1024]);
        assertEquals(0, target[4 * 1024]);
        assertEquals(6, target[3 * 1024 + 1]);
        assertEquals(99, source[3 * 1024]);
    }
}

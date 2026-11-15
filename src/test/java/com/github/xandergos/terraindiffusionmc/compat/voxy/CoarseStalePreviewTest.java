package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoarseStalePreviewTest {
    private static final long[] PALETTE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    @Test
    void unownedCachedSheetCannotOverrideNewTerrainOrItsSkyAtAnyMip() {
        for (int level = 0; level <= 4; level++) {
            int unit = 1 << level, side = 32 * unit, air = 64 + 8 * unit;
            var field = new CoarseLodPatch.Surface[1024];
            Arrays.fill(field, new CoarseLodPatch.Surface(air, (short) 229, 0));
            long[] source = new long[32768], target = new long[32768];
            // A legacy flat slab above the new surface, with no real/exact owner.
            Arrays.fill(source, 24 * 1024, 25 * 1024, 99);
            long[] unchanged = source.clone();
            var result =
                    CoarseSectionComposer.fill(
                            level,
                            -1,
                            64 / side,
                            -1,
                            -64,
                            1871,
                            field,
                            source,
                            target,
                            (x, z) -> false,
                            b -> PALETTE,
                            v -> v == 0);
            assertEquals(0, target[24 * 1024], "Stale floating slab, level " + level);
            assertNotEquals(0, target[0], "New ground, level " + level);
            assertTrue(result.changed());
            assertArrayEquals(unchanged, source);
        }
    }

    @Test
    void staleChildFlagsDisappearButThinChildrenUnderOwnedColumnsSurvive() {
        var field = new CoarseLodPatch.Surface[1024];
        Arrays.fill(field, new CoarseLodPatch.Surface(-64, (short) 229, 0));
        for (int level = 1; level <= 4; level++) {
            long[] target = new long[32768];
            int side = 32 << level;
            var empty =
                    CoarseSectionComposer.fill(
                            level, 0, 8, 0, -64, 1871, field, null, target, (x, z) -> false, b -> PALETTE, v -> v == 0);
            assertEquals(0, empty.mergeChildren((byte) 255));
            var owned =
                    CoarseSectionComposer.fill(
                            level,
                            0,
                            8,
                            0,
                            -64,
                            1871,
                            field,
                            null,
                            target,
                            (x, z) -> x < side / 2 && z < side / 2,
                            b -> PALETTE,
                            v -> v == 0);
            assertEquals(
                    0x11,
                    Byte.toUnsignedInt(owned.mergeChildren((byte) 255)),
                    "Both vertical children in owned quadrant");
        }
    }

    @Test
    void staleBlocksAboveWorldCeilingAreClearedInPartialTopSection() {
        var field = new CoarseLodPatch.Surface[1024];
        Arrays.fill(field, new CoarseLodPatch.Surface(64, (short) 229, 0));
        long[] source = new long[32768], target = new long[32768];
        Arrays.fill(source, 99);
        var result =
                CoarseSectionComposer.fill(
                        4, 0, 3, 0, -64, 1871, field, source, target, (x, z) -> false, b -> PALETTE, v -> v == 0);
        assertEquals(0, result.nonAir());
        assertArrayEquals(new long[32768], target);
        assertEquals(99, source[32767]);
    }

    @Test
    void levelZeroHasNoChildrenToPreserveWhenStaleGeometryIsRemoved() {
        var field = new CoarseLodPatch.Surface[1024];
        Arrays.fill(field, new CoarseLodPatch.Surface(64, (short) 229, 0));
        var result =
                CoarseSectionComposer.fill(
                        0,
                        0,
                        8,
                        0,
                        -64,
                        1871,
                        field,
                        null,
                        new long[32768],
                        (x, z) -> x < 16,
                        b -> PALETTE,
                        v -> v == 0);
        assertEquals(0, result.mergeChildren((byte) 255));
    }
}

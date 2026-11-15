package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoarseSectionComposerTest {
    private static final long[] PALETTE = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    static CoarseLodPatch.Surface[] flat(int air) {
        var field = new CoarseLodPatch.Surface[1024];
        Arrays.fill(field, new CoarseLodPatch.Surface(air, (short) 229, 0));
        return field;
    }

    private CoarseSectionComposer.Result fill(
            int level,
            int sy,
            CoarseLodPatch.Surface[] field,
            long[] source,
            long[] out,
            CoarseSectionComposer.Protected protectedColumn) {
        return CoarseSectionComposer.fill(
                level, 0, sy, 0, -64, 1871, field, source, out, protectedColumn, b -> PALETTE, v -> v == 0);
    }

    @Test
    void testCoarseSectionsEncodeLargeCells() {
        long[] data = new long[32768];
        var result = fill(4, 0, flat(64), null, data, (x, z) -> false);
        assertEquals(4 * 1024, result.nonAir());
        assertEquals(15, Byte.toUnsignedInt(result.children()));
        assertTrue(result.changed());
        assertEquals(3, data[3 * 1024]);
        assertEquals(0, data[4 * 1024]);
    }

    @Test
    void explicitOwnershipProtectsTheEntireColumnIncludingAirAboveIt() {
        long[] source = new long[32768], out = new long[32768];
        source[0] = 99;
        var copy = source.clone();
        fill(4, 0, flat(128), source, out, (x, z) -> x < 16 && z < 16);
        assertArrayEquals(copy, source);
        assertEquals(99, out[0]);
        assertEquals(0, out[1024]);
        assertEquals(1, out[1]);
        assertEquals(0, out[8 * 1024 + 1]);
    }

    @Test
    void loadedRealAirWinsEvenWhenNoVoxySectionExistsYet() {
        long[] out = new long[32768];
        fill(4, 0, flat(128), null, out, (x, z) -> x < 16);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) assertEquals(0, out[y * 1024 + z * 32]);
        assertNotEquals(0, out[1]);
    }

    @Test
    void testInPlaceSnapshotMergeMatchesSeparateBuffers() {
        long[] source = new long[32768];
        source[5] = 99;
        long[] separate = new long[32768], inPlace = source.clone();
        var expected = fill(2, 0, flat(64), source, separate, (x, z) -> false);
        var actual = fill(2, 0, flat(64), inPlace, inPlace, (x, z) -> false);
        assertEquals(expected, actual);
        assertArrayEquals(separate, inPlace);
    }

    @Test
    void exactCoverageRemovesPreviewAboveRealTerrainAcrossVerticalSections() {
        long[] out = new long[32768];
        var field = flat(600);
        assertTrue(fill(4, 1, field, null, out, (x, z) -> false).nonAir() > 0);
        assertEquals(0, fill(4, 1, field, null, out, (x, z) -> true).nonAir());
        assertArrayEquals(new long[32768], out);
    }

    @Test
    void testOceansAndCanopiesAreOnlyPreviewMaterials() {
        long[] out = new long[32768];
        var sea = fill(0, 1, flat(40), null, out, (x, z) -> false);
        assertEquals(31 * 1024, sea.nonAir());
        assertEquals(6, out[10 * 1024]);
        assertEquals(0, out[31 * 1024]);
        var canopy = flat(64);
        Arrays.fill(canopy, new CoarseLodPatch.Surface(64, (short) 215, 28));
        fill(2, 0, canopy, null, out, (x, z) -> false);
        assertEquals(12, out[20 * 1024]);
        assertEquals(0, out[24 * 1024]);
        assertEquals(0, fill(4, 4, canopy, null, out, (x, z) -> false).nonAir());
    }

    @Test
    void testRetiringProviderDoesNotChangeOrReleaseForeignSections() {
        Object engine = new Object(), section = new Object();
        assertSame(section, CoarseRenderHooks.render(engine, 0, section));
        var p =
                new CoarseRenderHooks.Provider() {
                    public Object render(Object e, long pos, Object o) {
                        return engine;
                    }

                    public byte children(Object e, Object s, byte a) {
                        return 7;
                    }
                };
        try {
            CoarseRenderHooks.attach(p);
            assertSame(engine, CoarseRenderHooks.render(engine, 0, section));
            assertEquals(7, CoarseRenderHooks.children(engine, section, (byte) 3));
        } finally {
            CoarseRenderHooks.detach(p);
        }
        assertSame(section, CoarseRenderHooks.render(engine, 0, section));
        assertEquals(3, CoarseRenderHooks.children(engine, section, (byte) 3));
    }

    @Test
    void testFullyExactSectionsBypassPreviewIncludingNullSky() {
        var coverage = new CoarseCoverage();
        for (int z = -2; z < 0; z++) for (int x = -2; x < 0; x++) coverage.mark(x, z);
        assertTrue(
                CoarseSectionComposer.fullyProtected(
                        0, -1, -1, (x, z) -> coverage.contains(Math.floorDiv(x, 16), Math.floorDiv(z, 16))));
        assertFalse(
                CoarseSectionComposer.fullyProtected(
                        1, -1, -1, (x, z) -> coverage.contains(Math.floorDiv(x, 16), Math.floorDiv(z, 16))));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(
                CoarseSectionComposer.fullyProtected(
                        4,
                        0,
                        0,
                        (x, z) -> {
                            calls.incrementAndGet();
                            return false;
                        }));
        assertEquals(1, calls.get());
    }
}

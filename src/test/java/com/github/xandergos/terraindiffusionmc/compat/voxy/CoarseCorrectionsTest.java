package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CoarseCorrectionsTest {
    @Test
    void exactResidualFadesSmoothlyToZeroAcross128BlocksIncludingNegativeCoordinates() {
        var correction = new CoarseLodCorrections(4096);
        correction.add(List.of(new CoarseLodCorrections.Anchor(0, 0, 40)));
        assertEquals(40, correction.adjustment(0, 0), 1e-9);
        assertEquals(20, correction.adjustment(64, 0), 1e-9);
        assertEquals(0, correction.adjustment(128, 0));
        assertEquals(0, correction.adjustment(10000, 0));
        double previous = 40;
        for (int x = 0; x <= 128; x++) {
            double value = correction.adjustment(x, 0);
            assertTrue(value <= previous + 1e-9);
            assertEquals(value, correction.adjustment(-x, 0), 1e-9);
            previous = value;
        }
    }

    @Test
    void testOverlappingAnchorsInterpolate() {
        var correction = new CoarseLodCorrections(4096);
        correction.add(
                List.of(new CoarseLodCorrections.Anchor(0, 0, 40), new CoarseLodCorrections.Anchor(128, 0, -40)));
        assertEquals(0, correction.adjustment(64, 0), 1e-9);
        for (int x = -128; x <= 256; x++) assertTrue(Math.abs(correction.adjustment(x, 0)) <= 40);
        var tiny = new CoarseLodCorrections(32);
        for (int n = 0; n < 10; n++) tiny.add(List.of(new CoarseLodCorrections.Anchor(n * 512, -512, 20)));
        assertTrue(tiny.size() <= 32);
    }

    @Test
    void badInputDoesNotPartiallyModifyTheField() {
        var correction = new CoarseLodCorrections(4096);
        assertThrows(
                IllegalArgumentException.class,
                () -> correction.add(List.of(
                        new CoarseLodCorrections.Anchor(0, 0, 40), new CoarseLodCorrections.Anchor(16, 0, Float.NaN))));
        assertEquals(0, correction.size());
    }

    @Test
    void testCorrectionOnlyAffectsUnknownPreview() {
        var field = CoarseSectionComposerTest.flat(64);
        long[] output = new long[32768];
        var calls = new AtomicInteger();
        long[] palette = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        var result = CoarseSectionComposer.fill(
                0, 0, 2, 0, -64, 1871, field, null, output, (x, z) -> x < 16, b -> palette, v -> v == 0, (x, z) -> {
                    calls.incrementAndGet();
                    return 16;
                });
        assertEquals(512, calls.get());
        assertTrue(result.changed());
        assertEquals(64, field[0].firstAir());
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++) for (int x = 0; x < 16; x++) assertEquals(0, output[y * 1024 + z * 32 + x]);
        assertNotEquals(0, output[16]);
        assertEquals(0, output[16 * 1024 + 16]);
    }
}

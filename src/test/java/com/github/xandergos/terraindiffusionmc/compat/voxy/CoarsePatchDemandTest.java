package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 4.3 coverage diagnostics: patchDemand counts the patch keys whose rectangles
 * intersect the radius disc (nearest-point distance <= radius) — the denominator of the
 * new "patches=X/needed" status line. */
class CoarsePatchDemandTest {
    @Test
    void tinyRadiusShortCircuitsToOnePatch() {
        assertEquals(1, CoarseLodController.patchDemand(0, 0, 32, 16384));
        assertEquals(1, CoarseLodController.patchDemand(123456, -98765, 32, 16384));
        // radius==span/…: candidates px in {-1,0}, both rectangles touch the disc -> 4.
        assertEquals(4, CoarseLodController.patchDemand(0, 0, 64, 16384));
    }

    @Test
    void oneSpanRadiusCountsBothSidesOfTheCenter() {
        // Candidates px in {-1,0,1}; dx = 0,0,16384. All pairs qualify except the
        // (16384,16384) diagonal (23170 > 16384): 9-1 = 8 patches.
        assertEquals(8, CoarseLodController.patchDemand(0, 0, 16384, 16384));
    }

    @Test
    void fullHorizonNeedsTheExpectedPatchCount() {
        // Candidates px in {-2..2}; dx = 16384,0,0,16384,32768. Pairs with hypot<=32768:
        // dx=32768 -> dz=0 (2), dx=16384 -> dz in {0,16384} (2x4=8), dx=0 -> all dz (2x5=10).
        assertEquals(20, CoarseLodController.patchDemand(0, 0, 32768, 16384));
    }

    @Test
    void negativeCentersMirrorPositiveOnes() {
        assertEquals(
                CoarseLodController.patchDemand(0, 0, 16384, 16384),
                CoarseLodController.patchDemand(-16384, -16384, 16384, 16384));
    }

    @Test
    void demandGrowsWithRadiusAndShrinksWithSpan() {
        assertTrue(
                CoarseLodController.patchDemand(0, 0, 16384, 16384)
                        <= CoarseLodController.patchDemand(0, 0, 32768, 16384),
                "radius growth");
        assertTrue(
                CoarseLodController.patchDemand(0, 0, 32768, 32768)
                        <= CoarseLodController.patchDemand(0, 0, 32768, 16384),
                "span growth");
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CoarseLodTest {
    @TempDir Path temp;

    static CoarseLodPatch patch(int x, int z) {
        int side = CoarseLodPatch.GRID, n = side * side;
        var tensor = new FloatTensor(new int[] {7, side, side});
        for (int row = 0; row < side; row++)
            for (int col = 0; col < side; col++) {
                int p = row * side + col;
                float weight = .25f + (p % 7) * .1f;
                tensor.data[6 * n + p] = weight;
                float elevation =
                        10
                                + (x * CoarseLodPatch.SIDE + col - CoarseLodPatch.HALO) * .01f
                                + (z * CoarseLodPatch.SIDE + row - CoarseLodPatch.HALO) * .02f;
                tensor.data[p] = elevation * weight;
                tensor.data[2 * n + p] = 10 * weight;
                tensor.data[4 * n + p] = 600 * weight;
            }
        return new CoarseLodPatch(new CoarseLodPatch.Key(x, z), 2, 8, 12345, 1871, tensor);
    }

    @Test
    void weightedChannelsAndHalosAgreeAcrossPositiveAndNegativeSeams() {
        int span = 64 * 512;
        for (int boundary : new int[] {-span, 0, span}) {
            var left = patch(Math.floorDiv(boundary, span) - 1, -1);
            var right = patch(Math.floorDiv(boundary, span), -1);
            for (int dx = -16; dx <= 16; dx++)
                for (int z : new int[] {-20000, -512, -1}) {
                    assertEquals(left.channel(0, boundary + dx, z), right.channel(0, boundary + dx, z), 2e-6);
                    assertEquals(10, left.channel(2, boundary + dx, z), 2e-6);
                    assertEquals(600, left.channel(4, boundary + dx, z), .001);
                }
        }
    }

    @Test
    void badShapesMissingWeightsAndNonFiniteValuesAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CoarseLodPatch(
                                new CoarseLodPatch.Key(0, 0), 2, 8, 0, 1871, new FloatTensor(new int[] {7, 1, 1})));
        var data = new FloatTensor(new int[] {7, CoarseLodPatch.GRID, CoarseLodPatch.GRID});
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoarseLodPatch(new CoarseLodPatch.Key(0, 0), 2, 8, 0, 1871, data));
        Arrays.fill(data.data, 1);
        data.data[0] = Float.NaN;
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoarseLodPatch(new CoarseLodPatch.Key(0, 0), 2, 8, 0, 1871, data));
    }

    @Test
    void cacheIsBoundedAndFrontierSelectsNearPatchesWithoutMillionsOfCells() {
        int span = 64 * 512;
        var cache = new CoarseLodCache(span, 2);
        assertEquals(new CoarseLodPatch.Key(0, 0), cache.next(span / 2, span / 2, 32768));
        cache.put(patch(0, 0));
        assertNotEquals(new CoarseLodPatch.Key(0, 0), cache.next(span / 2, span / 2, 32768));
        assertNull(cache.at(-1, 0));
        cache.put(patch(-1, 0));
        assertNotNull(cache.at(-1, 0));
        cache.put(patch(1, 0));
        assertEquals(2, cache.size());
        assertFalse(cache.contains(new CoarseLodPatch.Key(0, 0)));
    }

    @Test
    void modelSurfaceIsDeterministicAndDoesNotRequireRawTilesOrWaterPlans() {
        var a = patch(0, 0);
        var b = patch(0, 0);
        for (int z = 0; z < 512; z += 16)
            for (int x = 0; x < 512; x += 16) assertEquals(a.surface(x, z), b.surface(x, z));
        assertTrue(a.surface(0, 0).firstAir() >= 63);
    }

    @Test
    void coverageUsesChunkColumnsAcrossNegativeCoordinatesAndRealGuardDoesNoIo() {
        var coverage = new CoarseCoverage();
        coverage.mark(-1, -33);
        assertTrue(coverage.contains(-1, -33));
        assertFalse(coverage.contains(0, -33));
        var guard = new LodChunkGuard(temp.resolve("absent"));
        guard.markLoaded(-1, -33);
        assertTrue(guard.knownRealColumn(-1, -513));
        assertFalse(guard.knownRealColumn(0, -513));
        assertEquals(0, guard.failures());
    }

    @Test
    void automaticHorizonUsesVoxyRootSectionsRatherThanWorldGen512ChunkSlider() {
        assertEquals(32768, Voxy016Api.horizonBlocks(64));
        assertEquals(8192, Voxy016Api.horizonBlocks(16));
        assertEquals(32768, Voxy016Api.horizonBlocks(1000));
        assertEquals(1024, Voxy016Api.horizonBlocks(Double.NaN));
    }
}

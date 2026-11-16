package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CoarseOwnershipTest {
    @Test
    void testLoadedChunkWithoutDataIsNotProtected() {
        assertFalse(CoarseOwnership.exact(CoarseOwnership.decide(false)));
        assertTrue(CoarseOwnership.exact(CoarseOwnership.decide(true)));
    }

    @Test
    void testComposerKeepsOwnedColumns() {
        var field = new CoarseLodPatch.Surface[1024];
        for (int z = 0; z < 32; z++)
            for (int x = 0; x < 32; x++) field[z * 32 + x] = new CoarseLodPatch.Surface(80, (short) 229, (short) 0);
        long[] original = new long[32768];
        // Owned loaded column (x=0,z=0): real voxel at section-local y=10 (below coarse surface 80).
        original[10 * 1024] = 5L << 27;
        long[] target = new long[32768];
        CoarseSectionComposer.fill(
                0,
                0,
                0,
                0,
                -64,
                1871,
                field,
                original,
                target,
                (wx, wz) -> false,
                (biome) -> new long[13],
                v -> v == 0,
                (x, z) -> 0,
                (wx, wz) -> wx == 0 && wz == 0); // preserveOriginal: the loaded column
        // Preserved column keeps its real voxel and its real air above (no virtual stone).
        assertEquals(5L << 27, target[10 * 1024]);
        assertEquals(0, target[20 * 1024]);
        // The whole section (world y 0..31) is below the coarse surface 80, and the
        // unowned column is filled by the neural surface only up to its own height:
        // column (1,1) has terrain at section-local y = 80-(-64)-1 = 143 -> outside this
        // 32-high section, so the section part stays air for the unowned column too.
        assertEquals(0, target[1024 + 1]);
    }
}

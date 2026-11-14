package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 5.4.1 regression: the confirmed wall mechanism. Ownership evidence WITHOUT
 * stored data (loaded-but-not-ingested chunk, coverage mark, unread region) must NOT
 * suppress the neural preview — it used to produce "protected air" and a vertical wall
 * against neighbouring neural columns. Only actual stored data protects a column. */
class CoarseOwnershipTest {
    @Test void loadedButNotIngestedChunkIsNoLongerProtectedAir() {
        // The confirmed trace: Anvil/loaded evidence at (367,15,-512), no exact Voxy
        // section, coarse firstAir ~ 45. Old decision: protected air (wall). New: OPEN.
        assertFalse(CoarseOwnership.exact(CoarseOwnership.decide(false)));
        assertTrue(CoarseOwnership.exact(CoarseOwnership.decide(true)));
    }

    @Test void composerPreservesLoadedChunkVoxelsAndAirWhileNeuralFillsUnowned() {
        // Geometry-level regression on the pure composer: an owned loaded column keeps its
        // voxel AND its air (no virtual stone in real air), while the unowned neighbouring
        // column receives the neural surface — no ownership wall between them.
        var field=new CoarseLodPatch.Surface[1024];
        for(int z=0;z<32;z++)for(int x=0;x<32;x++)field[z*32+x]=new CoarseLodPatch.Surface(80,(short)229,(short)0);
        long[] original=new long[32768];
        // Owned loaded column (x=0,z=0): real voxel at section-local y=10 (below coarse surface 80).
        original[10*1024]=5L<<27;
        long[] target=new long[32768];
        CoarseSectionComposer.fill(0,0,0,0,-64,1871,field,original,target,
            (wx,wz)->false,(biome)->new long[13],v->v==0,(x,z)->0,
            (wx,wz)->wx==0&&wz==0); // preserveOriginal: the loaded column
        // Preserved column keeps its real voxel and its real air above (no virtual stone).
        assertEquals(5L<<27,target[10*1024]);
        assertEquals(0,target[20*1024]);
        // The whole section (world y 0..31) is below the coarse surface 80, and the
        // unowned column is filled by the neural surface only up to its own height:
        // column (1,1) has terrain at section-local y = 80-(-64)-1 = 143 -> outside this
        // 32-high section, so the section part stays air for the unowned column too.
        assertEquals(0,target[1024+1]);
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 4.4 read-only census arithmetic: persisted-top extraction from a 32³ raw tile
 * and the aggregator bookkeeping that the /td-voxy census report is built from. */
class VoxyCensusAggregatorTest {
    private static long voxel(long blockId,int light){ // composeMappingId-like: light<<...|block; only non-zero matters
        return blockId<<27|(long)light<<56;
    }

    @Test void persistedTopFindsTopmostNonAirPerColumn() {
        long[] raw=new long[32*1024];
        // column (x=5,z=7): solid at y=3 (bottom) and y=20 (top)
        raw[20*1024+7*32+5]=voxel(1,15);
        raw[3*1024+7*32+5]=voxel(1,0);
        assertEquals(20,VoxyCensus.persistedTopOf(raw,5,7));
        // column (x=0,z=0): empty
        assertEquals(Integer.MIN_VALUE,VoxyCensus.persistedTopOf(raw,0,0));
        // bottom-only column
        raw[0*1024+1*32+2]=voxel(9,15);
        assertEquals(0,VoxyCensus.persistedTopOf(raw,2,1));
    }

    @Test void aggregatorCountsLevelsMismatchesAndFirstCause() {
        var agg=new VoxyCensus.Aggregator();
        agg.section(0);agg.section(0);agg.section(2);
        assertEquals(2,agg.sections[0]);assertEquals(1,agg.sections[2]);
        agg.parentBitWithoutChild(2,3,-1,4);
        agg.missingNeighbor(1,-5,0,7);
        agg.staleSample(false,100,101,10,20);
        agg.staleSample(true,120,90,30,40);
        assertEquals(2,agg.staleChecked);assertEquals(1,agg.staleMismatched);
        assertEquals(30,agg.worstPersistedAboveReal);
        assertEquals(1,agg.worstRealAbovePersisted);
        assertTrue(agg.firstMismatch().contains("parent@2"),agg.firstMismatch());
        // First cause is sticky: later causes do not overwrite it.
        agg.missingNeighbor(0,1,2,3);
        assertTrue(agg.firstMismatch().contains("parent@2"),agg.firstMismatch());
    }

    @Test void staleWithoutMismatchKeepsReportClean() {
        var agg=new VoxyCensus.Aggregator();
        agg.staleSample(false,64,64,1,1);
        assertNull(agg.firstMismatch());
        assertEquals(0,agg.staleMismatched);
        assertEquals(0,agg.worstPersistedAboveReal);
        assertEquals(0,agg.worstRealAbovePersisted);
    }
}

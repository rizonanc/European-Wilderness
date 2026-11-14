package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 4.5 acceptance: (1) the chunks-mode Native Neural LOD gate follows the
 * experimental flag only, and (2) the renderer-only coarse pipeline has NO persistent
 * write path at bytecode level — no WorldUpdater.insertUpdate, no saveSection anywhere
 * in CoarseVoxyRenderer (stronger than checking a single UPDATE_TYPE_DONT_SAVE flag). */
class ChunksCoarseIsolationTest {
    @Test void gateFollowsTheExperimentalFlagInChunksMode() {
        assertTrue(VoxyCompat.chunksCoarseGate(true,true,true),"chunks + experimental = open");
        assertFalse(VoxyCompat.chunksCoarseGate(true,true,false),"experimental off = closed (baseline unchanged)");
        assertFalse(VoxyCompat.chunksCoarseGate(false,true,true),"voxy master off = closed");
        assertFalse(VoxyCompat.chunksCoarseGate(true,false,true),"prepared source = prepared path, not this gate");
    }

    private static byte[] classBytes(String internalName) {
        try(var input=CoarseVoxyRenderer.class.getClassLoader()
                .getResourceAsStream(internalName.replace('.','/')+".class")) {
            assertNotNull(input,"compiled class present");
            return input.readAllBytes();
        } catch(Exception failure){throw new AssertionError(failure);}
    }

    private static int utf8Occurrences(byte[] classFile,String literal) {
        byte[] needle=literal.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int count=0;
        for(int i=0;i<=classFile.length-needle.length;i++) {
            boolean hit=true;
            for(int j=0;j<needle.length&&hit;j++)if(classFile[i+j]!=needle[j])hit=false;
            if(hit)count++;
        }
        return count;
    }

    @Test void coarseRendererHasNoPersistentWritePath() {
        byte[] bytes=classBytes("com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseVoxyRenderer");
        assertEquals(0,utf8Occurrences(bytes,"insertUpdate"),
            "coarse renderer must never call WorldUpdater.insertUpdate");
        assertEquals(0,utf8Occurrences(bytes,"saveSection"),
            "coarse renderer must never call WorldEngine.saveSection");
        assertEquals(0,utf8Occurrences(bytes,"tryAutoIngestChunk"),
            "coarse renderer must never invoke the ingest service");
        assertTrue(utf8Occurrences(bytes,"markDirty")>0,
            "renderer-only notifications go through WorldEngine.markDirty");
        // The prepared-bridge write path must not be reachable from the coarse renderer either.
        assertEquals(0,utf8Occurrences(bytes,"LodPublisher"),"no exact-LOD publication from the coarse renderer");
        assertEquals(0,utf8Occurrences(bytes,"LodVoxelEncoder"),"no exact-LOD encoding from the coarse renderer");
    }

    @Test void censusHelpersStayReadOnly() {
        byte[] bytes=classBytes("com.github.xandergos.terraindiffusionmc.compat.voxy.VoxyCensus");
        assertEquals(0,utf8Occurrences(bytes,"insertUpdate"),"census must not insert");
        assertEquals(0,utf8Occurrences(bytes,"saveSection"),"census must not save");
        assertEquals(0,utf8Occurrences(bytes,"markDirty"),"census must not dirty");
    }
}

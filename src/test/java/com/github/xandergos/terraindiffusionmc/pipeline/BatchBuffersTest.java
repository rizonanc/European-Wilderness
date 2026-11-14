package com.github.xandergos.terraindiffusionmc.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 4.2 regression contract: variable-batch ONNX input buffers must be exact-fit
 * for every batch size, reused only between equal-size calls, and must never hand
 * runModel an array whose length differs from batch*perBatch (the production
 * "Tensor shape/data mismatch: x" world-creation failure). */
class BatchBuffersTest {
    private static final int PER_BATCH=5*64*64;

    @Test void decreasingBatchSequenceAlwaysHandsExactLengths() {
        // The production crash order: full batches first, smaller remainder later.
        float[][] cache=null;
        for(int batch:new int[]{4,4,4,1,4,2,1,3,4}) {
            cache=BatchBuffers.ensureSize(cache,batch);
            float[] buffer=BatchBuffers.exact(cache,batch,PER_BATCH);
            assertEquals(batch*PER_BATCH,buffer.length,"batch "+batch+" must be exact-fit");
        }
    }

    @Test void equalSizeCallsReuseTheSameBuffer() {
        float[][] cache=BatchBuffers.ensureSize(null,4);
        float[] first=BatchBuffers.exact(cache,4,PER_BATCH);
        first[0]=1.5F;
        assertSame(first,BatchBuffers.exact(cache,4,PER_BATCH),"reuse between equal batches");
        assertSame(first,BatchBuffers.exact(cache,4,PER_BATCH));
    }

    @Test void differentBatchSizesUseIndependentSlots() {
        float[][] cache=BatchBuffers.ensureSize(null,4);
        float[] big=BatchBuffers.exact(cache,4,PER_BATCH);
        float[] small=BatchBuffers.exact(cache,1,PER_BATCH);
        assertNotSame(big,small,"batch 1 must not receive the batch-4 buffer");
        assertEquals(PER_BATCH,small.length);
        assertNotSame(big,BatchBuffers.exact(cache,2,PER_BATCH));
        assertSame(big,BatchBuffers.exact(cache,4,PER_BATCH),"growing the table keeps old slots");
    }

    @Test void growingCacheTablePreservesExistingBuffers() {
        float[][] cache=BatchBuffers.ensureSize(null,2);
        float[] two=BatchBuffers.exact(cache,2,PER_BATCH);
        cache=BatchBuffers.ensureSize(cache,4);
        assertSame(two,BatchBuffers.exact(cache,2,PER_BATCH),"table growth must not discard slots");
    }
}

package com.github.xandergos.terraindiffusionmc.pipeline;

/** Phase 4.2: exact-fit reusable ONNX input buffers for variable batch sizes.
 * PipelineChecks.inputs compares the full array length to the shape product, so every
 * buffer handed to runModel must have length exactly batch*perBatch — a longer reused
 * buffer is rejected as "Tensor shape/data mismatch". Fixed-size stages keep the
 * grow-only scratch in WorldPipeline; only variable-size inputs go through here. */
final class BatchBuffers {
    private BatchBuffers() {}

    /** Grows the per-batch-size cache table when a larger batch appears, preserving
     * buffers already cached for the smaller sizes. */
    static float[][] ensureSize(float[][] cache, int batch) {
        if (cache == null) return new float[batch][];
        if (cache.length < batch) return java.util.Arrays.copyOf(cache, batch);
        return cache;
    }

    /** Returns cache[batch-1], reallocated exactly when its length differs from
     * batch*perBatch; identical-size calls reuse the same buffer. */
    static float[] exact(float[][] cache, int batch, int perBatch) {
        float[] buffer = cache[batch - 1];
        if (buffer == null || buffer.length != batch * perBatch) {
            buffer = new float[batch * perBatch];
            cache[batch - 1] = buffer;
        }
        return buffer;
    }
}

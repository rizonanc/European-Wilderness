package com.github.xandergos.terraindiffusionmc.pipeline;

/** cache ONNX input arrays by exact length because tensor shapes forbid spare capacity. */
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

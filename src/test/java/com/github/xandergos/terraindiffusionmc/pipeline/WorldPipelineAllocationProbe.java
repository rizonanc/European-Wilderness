package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 4 headless allocation probe (Шаг 12). Measures real allocation bytes of the
 * in-place scratch paths against the allocation-heavy originals on the same thread via
 * com.sun.management.ThreadMXBean. These are headless component numbers, not in-game
 * frametime measurements. */
class WorldPipelineAllocationProbe {
    private static com.sun.management.ThreadMXBean bean() {
        var raw = java.lang.management.ManagementFactory.getThreadMXBean();
        assertTrue(raw instanceof com.sun.management.ThreadMXBean, "ThreadMXBean allocation support");
        var bean = (com.sun.management.ThreadMXBean) raw;
        assertTrue(bean.isThreadAllocatedMemorySupported(), "thread allocation measurement");
        bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }

    @Test
    void inPlaceSchedulerChainAllocatesAnOrderLessThanStepChain() {
        var bean = bean();
        long self = Thread.currentThread().getId();
        Random random = new Random(19);
        int n = 6 * 64 * 64;
        float[] initial = new float[n];
        for (int i = 0; i < n; i++) initial[i] = random.nextFloat() * 2 - 1;
        List<float[]> outs = new ArrayList<>();
        for (int step = 0; step < 20; step++) {
            float[] m = new float[n];
            for (int i = 0; i < n; i++) m[i] = random.nextFloat() * 2 - 1;
            outs.add(m);
        }
        // Warmup both paths so one-time classloading does not pollute the measurement.
        var warmRef = new EDMScheduler(20);
        var warmIn = new EDMScheduler(20);
        float[] warm = initial.clone();
        float[] warmPred = new float[n];
        for (int step = 0; step < 20; step++) warm = warmRef.step(outs.get(step), warm);
        for (int step = 0; step < 20; step++) warm = warmIn.stepInto(outs.get(step), warm, warmPred);
        // Reference chain: allocation-heavy API.
        long before = bean.getThreadAllocatedBytes(self);
        var reference = new EDMScheduler(20);
        float[] sample = initial.clone();
        for (int step = 0; step < 20; step++) sample = reference.step(outs.get(step), sample);
        long referenceBytes = bean.getThreadAllocatedBytes(self) - before;
        // In-place chain, including its two retained x0Pred buffers.
        before = bean.getThreadAllocatedBytes(self);
        var inPlace = new EDMScheduler(20);
        float[] actual = initial.clone();
        float[] predA = new float[n], predB = new float[n];
        float[] pred = predA;
        for (int step = 0; step < 20; step++) {
            actual = inPlace.stepInto(outs.get(step), actual, pred);
            pred = pred == predA ? predB : predA;
        }
        long inPlaceBytes = bean.getThreadAllocatedBytes(self) - before;
        assertTrue(
                inPlaceBytes * 10 < referenceBytes,
                () -> "in-place chain allocated " + inPlaceBytes + " B vs " + referenceBytes + " B reference (warm)");
    }

    @Test
    void flatNoiseAllocatesLessThanGeneratePlusFlatten() {
        var bean = bean();
        long self = Thread.currentThread().getId();
        long seed = 42L;
        int h = 64, w = 64, c = 5, th = 64, tw = 64, y0 = 10, x0 = -30;
        // Warmup.
        GaussianNoisePatch.generateFlatInto(seed, y0, x0, h, w, c, th, tw, new float[c * h * w]);
        WorldPipelineIndependent.flatten3D(GaussianNoisePatch.generate(seed, y0, x0, h, w, c, th, tw));
        long before = bean.getThreadAllocatedBytes(self);
        float[] flat = WorldPipelineIndependent.flatten3D(GaussianNoisePatch.generate(seed, y0, x0, h, w, c, th, tw));
        long referenceBytes = bean.getThreadAllocatedBytes(self) - before;
        before = bean.getThreadAllocatedBytes(self);
        float[] direct = new float[c * h * w];
        GaussianNoisePatch.generateFlatInto(seed, y0, x0, h, w, c, th, tw, direct);
        long flatBytes = bean.getThreadAllocatedBytes(self) - before;
        assertTrue(
                flatBytes < referenceBytes,
                () -> "flat noise allocated " + flatBytes + " B vs " + referenceBytes + " B reference");
        assertArrayEquals(flat, direct);
    }

    @Test
    void upsampleIntoAvoidsPerCallDestination() {
        var bean = bean();
        long self = Thread.currentThread().getId();
        Random random = new Random(23);
        float[] src = new float[4 * 128 * 128];
        for (int i = 0; i < src.length; i++) src[i] = random.nextFloat();
        float[] reused = new float[4 * 256 * 256];
        // Warmup.
        LaplacianUtils.nearestUpsample(src, 4, 128, 128, 256, 256);
        LaplacianUtils.nearestUpsampleInto(src, 4, 128, 128, 256, 256, reused);
        long before = bean.getThreadAllocatedBytes(self);
        var expected = LaplacianUtils.nearestUpsample(src, 4, 128, 128, 256, 256);
        long referenceBytes = bean.getThreadAllocatedBytes(self) - before;
        before = bean.getThreadAllocatedBytes(self);
        LaplacianUtils.nearestUpsampleInto(src, 4, 128, 128, 256, 256, reused);
        long intoBytes = bean.getThreadAllocatedBytes(self) - before;
        assertTrue(
                intoBytes * 100 < referenceBytes,
                () -> "upsampleInto allocated " + intoBytes + " B vs " + referenceBytes + " B reference");
        assertArrayEquals(expected, reused);
    }

    /** Local copy of the original flatten3D so this probe never touches WorldPipeline's
     * model-config class initialization outside the game. */
    private static final class WorldPipelineIndependent {
        static float[] flatten3D(float[][][] arr) {
            int C = arr.length, H = arr[0].length, W = arr[0][0].length;
            float[] out = new float[C * H * W];
            for (int c = 0; c < C; c++)
                for (int r = 0; r < H; r++) System.arraycopy(arr[c][r], 0, out, c * H * W + r * W, W);
            return out;
        }
    }
}

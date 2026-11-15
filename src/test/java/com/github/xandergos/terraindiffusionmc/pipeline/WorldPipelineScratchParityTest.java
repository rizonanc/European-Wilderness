package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 4 scratch-buffer parity. The reused in-place paths must be bit-identical to the
 * allocation-heavy originals. Reference semantics below are copied verbatim from the
 * pre-change sources (WorldPipeline.flatten3d / WorldPipeline.nearestUpsample) because
 * touching WorldPipeline itself would trigger model-config loading outside the game.
 * EDMScheduler and GaussianNoisePatch are safe to load directly. */
class WorldPipelineScratchParityTest {
    private static void assertBitsEqual(float[] expected, float[] actual, String what) {
        assertEquals(expected.length, actual.length, what + ": length");
        for (int i = 0; i < expected.length; i++)
            assertEquals(
                    Float.floatToRawIntBits(expected[i]),
                    Float.floatToRawIntBits(actual[i]),
                    what + ": bit mismatch at " + i);
    }

    /** Verbatim copy of the original WorldPipeline.flatten3D (pre-Phase-4). */
    private static float[] flatten3D(float[][][] arr) {
        int C = arr.length, H = arr[0].length, W = arr[0][0].length;
        float[] out = new float[C * H * W];
        for (int c = 0; c < C; c++)
            for (int r = 0; r < H; r++) System.arraycopy(arr[c][r], 0, out, c * H * W + r * W, W);
        return out;
    }

    /** Verbatim copy of the original WorldPipeline.nearestUpsample (pre-Phase-4). */
    private static float[] nearestUpsample(float[] src, int C, int sH, int sW, int dH, int dW) {
        float[] dst = new float[C * dH * dW];
        for (int c = 0; c < C; c++)
            for (int r = 0; r < dH; r++) {
                int sr = r * sH / dH;
                for (int col = 0; col < dW; col++)
                    dst[c * dH * dW + r * dW + col] = src[c * sH * sW + sr * sW + col * sW / dW];
            }
        return dst;
    }

    @Test
    void flatNoiseMatchesGenerateAndFlatten3D() {
        long seed = 42L;
        // Covers exact tiles, partial tiles, multi-tile spans, negative origins and
        // the two production channel counts plus the single-channel decoder case.
        int[][] cases = {
            {64, 64, 5, 64, 64},
            {64, 64, 6, 64, 64},
            {256, 256, 1, 256, 256},
            {100, 97, 3, 64, 64},
            {130, 64, 5, 64, 64}
        };
        for (int[] dims : cases) {
            int h = dims[0], w = dims[1], c = dims[2], th = dims[3], tw = dims[4];
            int y0 = 10, x0 = -30;
            float[] expected = flatten3D(GaussianNoisePatch.generate(seed, y0, x0, h, w, c, th, tw));
            float[] actual = new float[c * h * w];
            GaussianNoisePatch.generateFlatInto(seed, y0, x0, h, w, c, th, tw, actual);
            assertBitsEqual(expected, actual, "noise " + h + "x" + w + "x" + c + " tile " + th + "x" + tw);
        }
    }

    @Test
    void nearestUpsampleIntoMatchesOriginal() {
        Random random = new Random(7);
        float[] src = new float[4 * 32 * 32];
        for (int i = 0; i < src.length; i++) src[i] = random.nextFloat();
        float[] actual = new float[4 * 128 * 128];
        LaplacianUtils.nearestUpsampleInto(src, 4, 32, 32, 128, 128, actual);
        assertBitsEqual(nearestUpsample(src, 4, 32, 32, 128, 128), actual, "nearest upsample 32->128");
        float[] src2 = new float[4 * 64 * 64];
        for (int i = 0; i < src2.length; i++) src2[i] = random.nextFloat();
        float[] actual2 = new float[4 * 256 * 256];
        LaplacianUtils.nearestUpsampleInto(src2, 4, 64, 64, 256, 256, actual2);
        assertBitsEqual(nearestUpsample(src2, 4, 64, 64, 256, 256), actual2, "nearest upsample 64->256");
    }

    @Test
    void schedulerStepIntoChainMatchesStepChainBitForBit() {
        Random random = new Random(11);
        int n = 6 * 64 * 64;
        // Pre-generate identical model outputs for both chains: the Random stream must not
        // diverge between the reference and in-place runs.
        float[] initial = new float[n];
        List<float[]> modelOuts = new ArrayList<>();
        for (int i = 0; i < n; i++) initial[i] = random.nextFloat() * 2 - 1;
        for (int step = 0; step < 20; step++) {
            float[] modelOut = new float[n];
            for (int i = 0; i < n; i++) modelOut[i] = random.nextFloat() * 2 - 1;
            modelOuts.add(modelOut);
        }
        // Reference chain: allocation-heavy API, untouched by this phase.
        EDMScheduler reference = new EDMScheduler(20);
        float[] expectedSample = initial.clone();
        List<float[]> expectedPerStep = new ArrayList<>();
        for (int step = 0; step < 20; step++) {
            expectedSample = reference.step(modelOuts.get(step), expectedSample);
            expectedPerStep.add(expectedSample.clone());
        }
        // Scratch chain: in-place API with two alternating x0Pred buffers, as in coarseTile.
        EDMScheduler inPlace = new EDMScheduler(20);
        float[] actualSample = initial.clone();
        float[] predA = new float[n], predB = new float[n];
        float[] x0Pred = predA;
        for (int step = 0; step < 20; step++) {
            actualSample = inPlace.stepInto(modelOuts.get(step), actualSample, x0Pred);
            x0Pred = x0Pred == predA ? predB : predA;
            assertBitsEqual(expectedPerStep.get(step), actualSample, "stepInto sample at step " + step);
        }
    }

    @Test
    void preconditionOutputsIntoMatchesOriginal() {
        Random random = new Random(13);
        int n = 6 * 64 * 64;
        float[] sample = new float[n], modelOut = new float[n];
        for (int i = 0; i < n; i++) {
            sample[i] = random.nextFloat() * 2 - 1;
            modelOut[i] = random.nextFloat() * 2 - 1;
        }
        float[] expected = EDMScheduler.preconditionOutputs(sample, modelOut, 3.25F);
        float[] actual = new float[n];
        EDMScheduler.preconditionOutputsInto(actual, sample, modelOut, 3.25F);
        assertBitsEqual(expected, actual, "preconditionOutputs");
    }

    @Test
    void reusedNoiseBufferCarriesNoStaleValuesAcrossSeedsAndBoundaries() {
        // Phase 4.1: the production scratch buffer is reused call after call. Fill it with
        // garbage (including NaN) first: any stale element would surface here. The buffer
        // is sized for the largest case, as a real caller-sized buffer would be; only the
        // first c*h*w elements are written and compared.
        float[] reused = new float[6 * 130 * 64];
        java.util.Arrays.fill(reused, Float.NaN);
        long[] seeds = {42L, -777L, Long.MAX_VALUE - 1};
        int[][] cases = {
            {64, 64, 6, 64, 64}, {130, 64, 6, 64, 64}, {64, 64, 6, 256, 256}
        }; // exact, partial-span, multi-tile
        for (int run = 0; run < cases.length; run++) {
            int h = cases[run][0], w = cases[run][1], c = cases[run][2], th = cases[run][3], tw = cases[run][4];
            int y0 = -run * 97 - 5, x0 = run * 64 - 200;
            float[] expected =
                    flatten3D(GaussianNoisePatch.generate(seeds[run % seeds.length], y0, x0, h, w, c, th, tw));
            GaussianNoisePatch.generateFlatInto(seeds[run % seeds.length], y0, x0, h, w, c, th, tw, reused);
            assertBitsEqual(
                    expected,
                    java.util.Arrays.copyOf(reused, expected.length),
                    "reuse run " + run + " (" + h + "x" + w + " tile " + th + "x" + tw + ")");
        }
    }

    @Test
    void schedulerPredBuffersStayCorrectAcrossConsecutiveChains() {
        // Phase 4.1: coarseTile reuses predA/predB across tiles. Three consecutive chains
        // with different noise must each match a fresh reference bit for bit.
        Random random = new Random(29);
        int n = 6 * 64 * 64;
        float[] predA = new float[n], predB = new float[n];
        for (int chain = 0; chain < 3; chain++) {
            float[] initial = new float[n];
            List<float[]> modelOuts = new ArrayList<>();
            for (int i = 0; i < n; i++) initial[i] = random.nextFloat() * 2 - 1;
            for (int step = 0; step < 20; step++) {
                float[] m = new float[n];
                for (int i = 0; i < n; i++) m[i] = random.nextFloat() * 2 - 1;
                modelOuts.add(m);
            }
            EDMScheduler reference = new EDMScheduler(20);
            float[] expectedSample = initial.clone();
            for (int step = 0; step < 20; step++) expectedSample = reference.step(modelOuts.get(step), expectedSample);
            EDMScheduler inPlace = new EDMScheduler(20);
            float[] actualSample = initial.clone();
            float[] pred = predA;
            for (int step = 0; step < 20; step++) {
                actualSample = inPlace.stepInto(modelOuts.get(step), actualSample, pred);
                pred = pred == predA ? predB : predA;
            }
            assertBitsEqual(expectedSample, actualSample, "chain " + chain + " on reused pred buffers");
        }
    }

    @Test
    void reusedUpsampleDestinationWithGarbageIsFullyOverwritten() {
        Random random = new Random(31);
        float[] src = new float[4 * 64 * 64];
        for (int i = 0; i < src.length; i++) src[i] = random.nextFloat();
        float[] reused = new float[4 * 256 * 256];
        java.util.Arrays.fill(reused, Float.NaN);
        LaplacianUtils.nearestUpsampleInto(src, 4, 64, 64, 256, 256, reused);
        assertBitsEqual(nearestUpsample(src, 4, 64, 64, 256, 256), reused, "upsample into garbage-filled buffer");
    }
}

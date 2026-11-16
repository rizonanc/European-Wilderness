package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.infinitetensor.InfiniteTensor;
import com.github.xandergos.terraindiffusionmc.infinitetensor.MemoryTileStore;
import com.github.xandergos.terraindiffusionmc.infinitetensor.TensorWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldPipeline implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WorldPipeline.class);
    static final int LATENT_COMPRESSION = WorldPipelineModelConfig.latentCompression();
    static final float SIGMA_DATA = 0.5F;
    static final int COARSE_TILE_SIZE = 64;
    static final int COARSE_TILE_STRIDE = 48;
    static final int LATENT_TILE_SIZE = 64;
    static final int LATENT_TILE_STRIDE = 32;
    static final int DECODER_TILE_SIZE = 256;
    static final int DECODER_TILE_STRIDE = 192;
    static final float[] MODEL_MEANS = WorldPipelineModelConfig.coarseMeans();
    static final float[] MODEL_STDS = WorldPipelineModelConfig.coarseStds();
    static final float[] COND_MEANS = new float[] {14.99F, 11.65F, 15.87F, 619.26F, 833.12F, 69.4F, 0.66F};
    static final float[] COND_STDS = new float[] {21.72F, 21.78F, 10.4F, 452.29F, 738.09F, 34.59F, 0.47F};
    static final float LOWFREQ_MEAN = -31.4F;
    static final float LOWFREQ_STD = 38.6F;
    static final float RESIDUAL_MEAN = WorldPipelineModelConfig.residualMean();
    static final float RESIDUAL_STD = WorldPipelineModelConfig.residualStd();
    static final float[] COND_SNR = WorldPipelineModelConfig.conditioningSnr();
    static final int COARSE_POOLING = WorldPipelineModelConfig.coarsePooling();
    static final float[] COND_VALS;
    static final int[] COND_DIMS;
    static final float[] MP_CONCAT_SCALES;
    static final float[] HISTOGRAM_RAW;
    private final OnnxModel coarseModel;
    private final OnnxModel baseModel;
    private final OnnxModel decoderModel;
    private final boolean ownModels;
    private volatile SyntheticMapFactory syntheticMapFactory;
    private volatile long seed;
    private final MemoryTileStore tileStore;
    private final long cacheLimitBytes = 104857600L;
    final InfiniteTensor coarse;
    final InfiniteTensor latents;
    final InfiniteTensor residual;

    // Access inference scratch buffers on the owner thread.
    private float[] scratchCondImg;
    private float[] scratchCondNoise;
    private float[] scratchCondMixed;
    private float[] scratchSample;
    private float[] scratchScaledIn;
    private float[] scratchXIn;
    private float[] scratchOut;
    private float[] scratchPredA;
    private float[] scratchPredB;
    private float[] scratchLatFlat;
    private float[] scratchUpsampled;
    private float[] scratchNoise;
    private float[] scratchXT;
    private float[] scratchModelIn;
    private float[] scratchNewSample;
    private float[] scratchBatchSample;
    private float[] scratchBatchNoise;
    private float[] scratchBatchNew;
    private float[][] scratchBatchXTArr;
    // Variable-batch ONNX inputs must be exact-fit: PipelineChecks.inputs compares the full
    // array length to the shape product, so a longer reused buffer is rejected (the Phase
    // 4.2 field failure "Tensor shape/data mismatch: x"). Batch sizes vary because
    // computeBatched ends with a smaller remainder group, so one exact buffer per size.
    private float[][] scratchBatchInCache;
    private float[][] scratchBatchCondCache;

    private static float[] scratch(float[] current, int need) {
        return current == null || current.length < need ? new float[need] : current;
    }

    public WorldPipeline(long seed, PipelineModels models) {
        this(seed, models.getCoarseModel(), models.getBaseModel(), models.getDecoderModel());
    }

    WorldPipeline(long seed, OnnxModel coarseModel, OnnxModel baseModel, OnnxModel decoderModel) {
        this.seed = seed & -1L;
        this.coarseModel = coarseModel;
        this.baseModel = baseModel;
        this.decoderModel = decoderModel;
        this.ownModels = false;
        this.syntheticMapFactory = new SyntheticMapFactory(this.seed);
        this.tileStore = new MemoryTileStore();
        this.coarse = this.buildCoarseStage();
        this.latents = this.buildLatentStage();
        this.residual = this.buildDecoderStage();
    }

    public WorldPipeline(long seed) {
        this.seed = seed & -1L;
        ModelAssetManager.ensureAssetsReady();
        this.coarseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("coarse_model.onnx"), "coarse");
        this.baseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("base_model.onnx"), "base");
        this.decoderModel = new OnnxModel(ModelAssetManager.resolveAssetPath("decoder_model.onnx"), "decoder");
        this.ownModels = true;
        this.syntheticMapFactory = new SyntheticMapFactory(this.seed);
        this.tileStore = new MemoryTileStore();
        this.coarse = this.buildCoarseStage();
        this.latents = this.buildLatentStage();
        this.residual = this.buildDecoderStage();
    }

    public void setSeed(long newSeed) {
        long s = newSeed & -1L;
        if (s != this.seed) {
            this.seed = s;
            this.syntheticMapFactory = new SyntheticMapFactory(s);
            this.tileStore.clearAllCaches();
        }
    }

    private InfiniteTensor buildCoarseStage() {
        int S = 64;
        int ST = 48;
        float[] ww = linearWeightWindow(S);
        TensorWindow outWin = new TensorWindow(new int[] {7, S, S}, new int[] {7, ST, ST});
        return this.tileStore.getOrCreate(
                "base_coarse_map",
                new Integer[] {7, null, null},
                (wi, args) -> this.coarseTile(wi, ww),
                outWin,
                new InfiniteTensor[0],
                new TensorWindow[0],
                104857600L);
    }

    private FloatTensor coarseTile(int[] wi, float[] ww) {
        int S = 64;
        int ST = 48;
        int i = wi[1];
        int j = wi[2];
        int i1 = i * ST;
        int j1 = j * ST;
        float[][][] syn = this.syntheticMapFactory.sample(j1, i1, j1 + S, i1 + S);

        for (int r = 0; r < S; r++) {
            for (int c = 0; c < S; c++) {
                float v = syn[1][r][c];
                if (v <= 20.0F) {
                    syn[1][r][c] = (v - 20.0F) * 1.25F + 20.0F;
                }
            }
        }

        int[] meanIdx = new int[] {0, 2, 3, 4, 5};
        float[] condImg = this.scratchCondImg = scratch(this.scratchCondImg, 5 * S * S);

        for (int ch = 0; ch < 5; ch++) {
            float mean = MODEL_MEANS[meanIdx[ch]];
            float std = MODEL_STDS[meanIdx[ch]];

            for (int px = 0; px < S * S; px++) {
                condImg[ch * S * S + px] = (syn[ch][px / S][px % S] - mean) / std;
            }
        }

        float[] condNoise = this.scratchCondNoise = scratch(this.scratchCondNoise, 5 * S * S);
        GaussianNoisePatch.generateFlatInto(this.seed, i1, j1, S, S, 5, S, S, condNoise);
        float[] condMixed = this.scratchCondMixed = scratch(this.scratchCondMixed, 5 * S * S);

        for (int ch = 0; ch < 5; ch++) {
            float cosT = (float) Math.cos(Math.atan(COND_SNR[ch]));
            float sinT = (float) Math.sin(Math.atan(COND_SNR[ch]));

            for (int px = 0; px < S * S; px++) {
                condMixed[ch * S * S + px] = cosT * condImg[ch * S * S + px] + sinT * condNoise[ch * S * S + px];
            }
        }

        EDMScheduler sched = new EDMScheduler(20);
        float[] sample = this.scratchSample = scratch(this.scratchSample, 6 * S * S);
        GaussianNoisePatch.generateFlatInto(this.seed + 1L, i1, j1, S, S, 6, S, S, sample);

        for (int k = 0; k < sample.length; k++) {
            sample[k] *= sched.sigmas[0];
        }

        // Two alternating x0Pred buffers: the scheduler retains one as prevModelOutput.
        float[] scaledIn = this.scratchScaledIn = scratch(this.scratchScaledIn, 6 * S * S);
        float[] xIn = this.scratchXIn = scratch(this.scratchXIn, 11 * S * S);
        float[] predA = this.scratchPredA = scratch(this.scratchPredA, 6 * S * S);
        float[] predB = this.scratchPredB = scratch(this.scratchPredB, 6 * S * S);
        float[] x0Pred = predA;
        float[][] condInputs = new float[5][1];
        long[][] condShapes = new long[5][1];

        for (int ci = 0; ci < 5; ci++) {
            condInputs[ci] = new float[] {COND_VALS[ci]};
            condShapes[ci] = new long[] {1L};
        }

        LOG.debug(
                "Coarse model called for chunk ({}, {}) tile pixels [{}, {}]-[{}, {}]",
                new Object[] {i, j, i1, j1, i1 + S, j1 + S});

        for (int step = 0; step < 20; step++) {
            float sigma = sched.sigmas[step];
            float cnoise = EDMScheduler.trigflowPreconditionNoise(sigma);
            System.arraycopy(sample, 0, scaledIn, 0, 6 * S * S);
            EDMScheduler.preconditionInputsInPlace(scaledIn, sigma);
            System.arraycopy(scaledIn, 0, xIn, 0, 6 * S * S);
            System.arraycopy(condMixed, 0, xIn, 6 * S * S, 5 * S * S);
            float[] modelOut = this.coarseModel.runModel(
                    xIn, new long[] {1L, 11L, S, S}, new float[] {cnoise}, condInputs, condShapes);
            PipelineChecks.output(modelOut, 6 * S * S, "COARSE");
            sample = sched.stepInto(modelOut, sample, x0Pred);
            x0Pred = x0Pred == predA ? predB : predA;
        }

        float[] out = this.scratchOut = scratch(this.scratchOut, 6 * S * S);

        for (int ch = 0; ch < 6; ch++) {
            for (int px = 0; px < S * S; px++) {
                out[ch * S * S + px] = sample[ch * S * S + px] / 0.5F * MODEL_STDS[ch] + MODEL_MEANS[ch];
            }
        }

        for (int px = 0; px < S * S; px++) {
            out[S * S + px] = out[px] - out[S * S + px];
        }

        FloatTensor result = new FloatTensor(new int[] {7, S, S});

        for (int ch = 0; ch < 6; ch++) {
            for (int px = 0; px < S * S; px++) {
                result.data[ch * S * S + px] = out[ch * S * S + px] * ww[px];
            }
        }

        System.arraycopy(ww, 0, result.data, 6 * S * S, S * S);
        return result;
    }

    private InfiniteTensor buildLatentStage() {
        int S = 64;
        int ST = 32;
        TensorWindow outWin = new TensorWindow(new int[] {6, S, S}, new int[] {6, ST, ST});
        TensorWindow coarseWin = new TensorWindow(new int[] {7, 4, 4}, new int[] {7, 1, 1}, new int[] {0, -1, -1});
        float[] ww = linearWeightWindow(S);
        float tInit = (float) Math.atan(160.0);
        InfiniteTensor initLatent = this.tileStore.getOrCreateBatched(
                "init_latent_map",
                new Integer[] {6, null, null},
                (wis, args) -> this.latentBatch(wis, null, args.get(0), tInit, 5819, ww),
                outWin,
                new InfiniteTensor[] {this.coarse},
                new TensorWindow[] {coarseWin},
                104857600L,
                4);
        float interT = (float) Math.atan(0.7F);
        return this.tileStore.getOrCreateBatched(
                "step_latent_map_0",
                new Integer[] {6, null, null},
                (wis, args) -> this.latentBatch(wis, args.get(0), args.get(1), interT, 5820, ww),
                outWin,
                new InfiniteTensor[] {initLatent, this.coarse},
                new TensorWindow[] {outWin, coarseWin},
                104857600L,
                4);
    }

    private List<FloatTensor> latentBatch(
            List<int[]> wis,
            List<FloatTensor> prevSamples,
            List<FloatTensor> coarseSlices,
            float t,
            int seedOffset,
            float[] ww) {
        int S = 64;
        int ST = 32;
        int batch = wis.size();
        float cosT = (float) Math.cos(t);
        float sinT = (float) Math.sin(t);
        float[][] xTArr = this.scratchBatchXTArr;
        if (xTArr == null || xTArr.length < batch) {
            xTArr = this.scratchBatchXTArr = new float[batch][];
        }

        for (int b = 0; b < batch; b++) {
            if (xTArr[b] == null || xTArr[b].length != 5 * S * S) {
                xTArr[b] = new float[5 * S * S];
            }
        }

        this.scratchBatchInCache = BatchBuffers.ensureSize(this.scratchBatchInCache, batch);
        this.scratchBatchCondCache = BatchBuffers.ensureSize(this.scratchBatchCondCache, batch);
        float[] modelInBatch = BatchBuffers.exact(this.scratchBatchInCache, batch, 5 * S * S);
        float[] condInputBatch = BatchBuffers.exact(this.scratchBatchCondCache, batch, 58);
        float[] sampleScratch = this.scratchBatchSample = scratch(this.scratchBatchSample, 5 * S * S);
        float[] noise = this.scratchBatchNoise = scratch(this.scratchBatchNoise, 5 * S * S);
        float[] newSample = this.scratchBatchNew = scratch(this.scratchBatchNew, 5 * S * S);

        for (int b = 0; b < batch; b++) {
            int[] ctx = wis.get(b);
            int i1 = ctx[1] * ST;
            int j1 = ctx[2] * ST;
            float[] cond58 = this.buildLatentConditioning(coarseSlices.get(b));
            System.arraycopy(cond58, 0, condInputBatch, b * 58, 58);
            float[] sample = sampleScratch;
            java.util.Arrays.fill(sample, 0.0F);
            if (prevSamples != null) {
                FloatTensor ps = prevSamples.get(b);

                for (int ch = 0; ch < 5; ch++) {
                    for (int px = 0; px < S * S; px++) {
                        float w = ps.data[5 * S * S + px];
                        sample[ch * S * S + px] = w > 1.0E-6F ? ps.data[ch * S * S + px] / w * 0.5F : 0.0F;
                    }
                }
            }

            GaussianNoisePatch.generateFlatInto(this.seed + seedOffset, i1, j1, S, S, 5, S, S, noise);
            float[] xT = xTArr[b];

            for (int k = 0; k < 5 * S * S; k++) {
                float z = noise[k] * 0.5F;
                xT[k] = cosT * sample[k] + sinT * z;
            }

            for (int k = 0; k < 5 * S * S; k++) {
                modelInBatch[b * 5 * S * S + k] = xT[k] / 0.5F;
            }
        }

        String chunkList = wis.stream().map(w -> "(" + w[1] + "," + w[2] + ")").collect(Collectors.joining(", "));
        LOG.debug("Base model called for {} chunks: {}", batch, chunkList);
        float[] noiseLabels = new float[batch];

        for (int b = 0; b < batch; b++) {
            noiseLabels[b] = t;
        }

        float[] predBatch = this.baseModel.runModel(
                modelInBatch, new long[] {batch, 5L, S, S}, noiseLabels, new float[][] {condInputBatch}, new long[][] {
                    {batch, 58L}
                });
        PipelineChecks.output(predBatch, batch * 5 * S * S, "LATENT");
        List<FloatTensor> results = new ArrayList<>(batch);

        for (int b = 0; b < batch; b++) {
            float[] xT = xTArr[b];

            for (int k = 0; k < 5 * S * S; k++) {
                float pred = -predBatch[b * 5 * S * S + k];
                newSample[k] = (cosT * xT[k] - sinT * 0.5F * pred) / 0.5F;
            }

            FloatTensor out = new FloatTensor(new int[] {6, S, S});

            for (int ch = 0; ch < 5; ch++) {
                for (int px = 0; px < S * S; px++) {
                    out.data[ch * S * S + px] = newSample[ch * S * S + px] * ww[px];
                }
            }

            System.arraycopy(ww, 0, out.data, 5 * S * S, S * S);
            results.add(out);
        }

        return results;
    }

    private float[] buildLatentConditioning(FloatTensor coarseSlice) {
        int N = 16;
        float[] condFlat = new float[6 * N];

        for (int ch = 0; ch < 6; ch++) {
            for (int px = 0; px < N; px++) {
                float w = coarseSlice.data[6 * N + px];
                condFlat[ch * N + px] = w > 1.0E-6F ? coarseSlice.data[ch * N + px] / w : 0.0F;
            }
        }

        float[] condImg7 = new float[7 * N];
        System.arraycopy(condFlat, 0, condImg7, 0, 6 * N);
        float maskNorm = (1.0F - COND_MEANS[6]) / COND_STDS[6];

        for (int px = 0; px < N; px++) {
            condImg7[6 * N + px] = maskNorm;
        }

        for (int ch = 0; ch < 6; ch++) {
            for (int px = 0; px < N; px++) {
                float v = (condFlat[ch * N + px] - COND_MEANS[ch]) / COND_STDS[ch];
                condImg7[ch * N + px] = Float.isNaN(v) ? 0.0F : v;
            }
        }

        float[] meansCrop = new float[16];
        System.arraycopy(condImg7, 0, meansCrop, 0, 16);
        float[] p5Crop = new float[16];
        System.arraycopy(condImg7, 16, p5Crop, 0, 16);
        float[] maskCrop = new float[16];
        System.arraycopy(condImg7, 96, maskCrop, 0, 16);
        float[] climateMeans = new float[4];

        for (int ch = 0; ch < 4; ch++) {
            float sum = 0.0F;

            for (int r = 1; r < 3; r++) {
                for (int c = 1; c < 3; c++) {
                    sum += condImg7[(2 + ch) * 16 + r * 4 + c];
                }
            }

            climateMeans[ch] = sum / 4.0F;
            if (Float.isNaN(climateMeans[ch])) {
                climateMeans[ch] = 0.0F;
            }
        }

        float noiseLevelNorm = -0.5F * (float) Math.sqrt(12.0);
        float[] histRaw = HISTOGRAM_RAW;
        float[] out = new float[58];
        int off = 0;
        off = appendScaled(out, off, meansCrop, MP_CONCAT_SCALES[0]);
        off = appendScaled(out, off, p5Crop, MP_CONCAT_SCALES[1]);
        off = appendScaled(out, off, climateMeans, MP_CONCAT_SCALES[2]);
        off = appendScaled(out, off, maskCrop, MP_CONCAT_SCALES[3]);
        off = appendScaled(out, off, histRaw, MP_CONCAT_SCALES[4]);
        out[off] = noiseLevelNorm * MP_CONCAT_SCALES[5];
        return out;
    }

    private InfiniteTensor buildDecoderStage() {
        int S = 256;
        int ST = 192;
        int lc = LATENT_COMPRESSION;
        TensorWindow outWin = new TensorWindow(new int[] {2, S, S}, new int[] {2, ST, ST});
        TensorWindow inpWin = new TensorWindow(new int[] {6, S / lc, S / lc}, new int[] {6, ST / lc, ST / lc});
        float[] ww = linearWeightWindow(S);
        float t = (float) Math.atan(160.0);
        return this.tileStore.getOrCreate(
                "init_residual_map",
                new Integer[] {2, null, null},
                (wi, args) -> this.decoderTile(wi, args.get(0), t, ww),
                outWin,
                new InfiniteTensor[] {this.latents},
                new TensorWindow[] {inpWin},
                104857600L);
    }

    private FloatTensor decoderTile(int[] wi, FloatTensor latentSlice, float t, float[] ww) {
        int S = 256;
        int ST = 192;
        int lc = LATENT_COMPRESSION;
        int Slc = S / lc;
        int i1 = wi[1] * ST;
        int j1 = wi[2] * ST;
        float cosT = (float) Math.cos(t);
        float sinT = (float) Math.sin(t);
        float[] latFlat = this.scratchLatFlat = scratch(this.scratchLatFlat, 4 * Slc * Slc);

        for (int ch = 0; ch < 4; ch++) {
            for (int px = 0; px < Slc * Slc; px++) {
                float w = latentSlice.data[5 * Slc * Slc + px];
                latFlat[ch * Slc * Slc + px] = w > 1.0E-6F ? latentSlice.data[ch * Slc * Slc + px] / w : 0.0F;
            }
        }

        float[] upsampled = this.scratchUpsampled = scratch(this.scratchUpsampled, 4 * S * S);
        LaplacianUtils.nearestUpsampleInto(latFlat, 4, Slc, Slc, S, S, upsampled);
        float[] noise = this.scratchNoise = scratch(this.scratchNoise, S * S);
        GaussianNoisePatch.generateFlatInto(this.seed + 5819L, i1, j1, S, S, 1, S, S, noise);
        float[] xT = this.scratchXT = scratch(this.scratchXT, S * S);

        for (int k = 0; k < S * S; k++) {
            xT[k] = sinT * noise[k] * 0.5F;
        }

        float[] modelIn = this.scratchModelIn = scratch(this.scratchModelIn, 5 * S * S);

        for (int k = 0; k < S * S; k++) {
            modelIn[k] = xT[k] / 0.5F;
        }

        System.arraycopy(upsampled, 0, modelIn, S * S, 4 * S * S);
        LOG.debug(
                "Decoder model called for chunk ({}, {}) tile pixels [{}, {}]-[{}, {}]",
                new Object[] {wi[1], wi[2], i1, j1, i1 + S, j1 + S});
        float[] rawPred = this.decoderModel.runModel(modelIn, new long[] {1L, 5L, S, S}, new float[] {t}, null, null);
        PipelineChecks.output(rawPred, S * S, "DECODER");
        float[] newSample = this.scratchNewSample = scratch(this.scratchNewSample, S * S);

        for (int k = 0; k < S * S; k++) {
            float pred = -rawPred[k];
            newSample[k] = (cosT * xT[k] - sinT * 0.5F * pred) / 0.5F;
        }

        FloatTensor result = new FloatTensor(new int[] {2, S, S});

        for (int px = 0; px < S * S; px++) {
            result.data[px] = newSample[px] * ww[px];
        }

        System.arraycopy(ww, 0, result.data, S * S, S * S);
        return result;
    }

    public long getSeed() {
        return this.seed;
    }

    public long getTotalComputedWindowCount() {
        return this.tileStore.getTotalComputedWindowCount();
    }

    public FloatTensor getCoarseSlice(int ci0, int cj0, int ci1, int cj1) {
        return this.coarse.getSlice(new int[] {0, ci0, cj0}, new int[] {7, ci1, cj1});
    }

    public FloatTensor prepareCoarseLodStep(int ci0, int cj0, int ci1, int cj1) {
        int[] start = {0, ci0, cj0}, end = {7, ci1, cj1};
        return coarse.prepareIndependentSliceStep(start, end) ? coarse.getSlice(start, end) : null;
    }

    /** Prepares one coarse window. Returns 1 for progress or 0 when complete. */
    public int prepareCoarseLodWindow(int ci0, int cj0, int ci1, int cj1) {
        int[] start = {0, ci0, cj0}, end = {7, ci1, cj1};
        return coarse.prepareIndependentWindowStep(start, end);
    }

    public float[][] get(int i1, int j1, int i2, int j2, boolean withClimate) {
        float[] elevFlat = this.computeElev(i1, j1, i2, j2);
        int H = i2 - i1;
        int W = j2 - j1;
        float[] climate = withClimate ? this.computeClimate(i1, j1, i2, j2, elevFlat, H, W) : null;
        return new float[][] {elevFlat, climate};
    }

    private float[] computeElev(int i1, int j1, int i2, int j2) {
        int lc = LATENT_COMPRESSION;
        float sigma = 5.0F;
        int ks = (int) (sigma * 2.0F) / 2 * 2 + 1;
        int padLr = ks / 2 + 1;
        int padHr = padLr * lc;
        int pi1 = Math.floorDiv(i1 - padHr, lc) * lc;
        int pj1 = Math.floorDiv(j1 - padHr, lc) * lc;
        int pi2 = -Math.floorDiv(-(i2 + padHr), lc) * lc;
        int pj2 = -Math.floorDiv(-(j2 + padHr), lc) * lc;
        int pH = pi2 - pi1;
        int pW = pj2 - pj1;
        FloatTensor resSlice = this.residual.getSlice(new int[] {0, pi1, pj1}, new int[] {2, pi2, pj2});
        float[][] residualP = new float[pH][pW];

        for (int r = 0; r < pH; r++) {
            for (int c = 0; c < pW; c++) {
                float w = resSlice.data[pH * pW + r * pW + c];
                float v = w > 1.0E-6F ? resSlice.data[r * pW + c] / w : 0.0F;
                residualP[r][c] = v * RESIDUAL_STD + RESIDUAL_MEAN;
            }
        }

        int lH = pH / lc;
        int lW = pW / lc;
        FloatTensor latSlice =
                this.latents.getSlice(new int[] {0, pi1 / lc, pj1 / lc}, new int[] {6, pi2 / lc, pj2 / lc});
        float[][] lowfreqP = new float[lH][lW];

        for (int r = 0; r < lH; r++) {
            for (int c = 0; c < lW; c++) {
                float w = latSlice.data[5 * lH * lW + r * lW + c];
                float v = w > 1.0E-6F ? latSlice.data[4 * lH * lW + r * lW + c] / w : 0.0F;
                lowfreqP[r][c] = v * 38.6F + -31.4F;
            }
        }

        float[][] newLowres = LaplacianUtils.laplacianDenoise(residualP, lowfreqP, sigma);
        float[][] elevP = LaplacianUtils.laplacianDecode(residualP, newLowres);
        int oi = i1 - pi1;
        int oj = j1 - pj1;
        int H = i2 - i1;
        int W = j2 - j1;
        float[] flat = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float es = elevP[oi + r][oj + c];
                flat[r * W + c] = Math.signum(es) * es * es;
            }
        }

        return flat;
    }

    private float[] computeClimate(int i1, int j1, int i2, int j2, float[] elevFlat, int H, int W) {
        int lc = LATENT_COMPRESSION;
        int S = 32 * lc;
        int ci1 = Math.floorDiv(i1, S);
        int cj1 = Math.floorDiv(j1, S);
        int ci2 = -Math.floorDiv(-i2, S);
        int cj2 = -Math.floorDiv(-j2, S);
        int win = 15;
        int pad = (win - 1) / 2 + 1;
        FloatTensor coarseSlice =
                this.coarse.getSlice(new int[] {0, ci1 - pad, cj1 - pad}, new int[] {7, ci2 + pad, cj2 + pad});
        int cH = ci2 + pad - (ci1 - pad);
        int cW = cj2 + pad - (cj1 - pad);
        float[][] coarseMap = new float[6][cH * cW];

        for (int ch = 0; ch < 6; ch++) {
            for (int px = 0; px < cH * cW; px++) {
                float w = coarseSlice.data[6 * cH * cW + px];
                coarseMap[ch][px] = w > 1.0E-6F ? coarseSlice.data[ch * cH * cW + px] / w : 0.0F;
            }
        }

        float[] coarseElev = new float[cH * cW];

        for (int px = 0; px < cH * cW; px++) {
            float v = Math.max(0.0F, coarseMap[0][px]);
            coarseElev[px] = v * v;
        }

        float[][][] lbt = LaplacianUtils.localBaselineTemperature(
                to2D(coarseMap[2], cH, cW), to2D(coarseElev, cH, cW), win, 0.02F);
        int lH = lbt[0].length;
        int lW = lbt[0][0].length;
        int cenPad = win / 2;
        int cenH = cH - 2 * cenPad;
        int cenW = cW - 2 * cenPad;
        float[][][] centralCoarse = new float[6][cenH][cenW];

        for (int ch = 0; ch < 6; ch++) {
            float[][] full = to2D(coarseMap[ch], cH, cW);
            centralCoarse[ch] = cropArray(full, cenPad, cenPad, cenH, cenW);
        }

        float[] climate = new float[5 * H * W];

        for (int r = 0; r < H; r++) {
            float gridY = (i1 + r + 0.5F) / S - ci1 + 0.5F;
            float cenGridY = gridY;

            for (int c = 0; c < W; c++) {
                float gridX = (j1 + c + 0.5F) / S - cj1 + 0.5F;
                float cenGridX = gridX;
                float tBase = bilinearSample2D(lbt[0], lH, lW, gridY, gridX);
                float beta = bilinearSample2D(lbt[1], lH, lW, gridY, gridX);
                float tempReal = tBase + beta * Math.max(0.0F, elevFlat[r * W + c]);
                climate[r * W + c] = tempReal;
                climate[H * W + r * W + c] = bilinearSample2D(centralCoarse[3], cenH, cenW, cenGridY, cenGridX);
                climate[2 * H * W + r * W + c] = bilinearSample2D(centralCoarse[4], cenH, cenW, cenGridY, cenGridX);
                climate[3 * H * W + r * W + c] = bilinearSample2D(centralCoarse[5], cenH, cenW, cenGridY, cenGridX);
                climate[4 * H * W + r * W + c] = beta;
            }
        }

        return climate;
    }

    static float[] linearWeightWindow(int size) {
        float[] w = new float[size * size];
        float mid = (size - 1) / 2.0F;
        float eps = 0.001F;

        for (int r = 0; r < size; r++) {
            float wy = 1.0F - (1.0F - eps) * Math.min(1.0F, Math.abs(r - mid) / mid);

            for (int c = 0; c < size; c++) {
                float wx = 1.0F - (1.0F - eps) * Math.min(1.0F, Math.abs(c - mid) / mid);
                w[r * size + c] = wy * wx;
            }
        }

        return w;
    }

    static float[] flatten3D(float[][][] arr) {
        int C = arr.length;
        int H = arr[0].length;
        int W = arr[0][0].length;
        float[] out = new float[C * H * W];

        for (int c = 0; c < C; c++) {
            for (int r = 0; r < H; r++) {
                System.arraycopy(arr[c][r], 0, out, c * H * W + r * W, W);
            }
        }

        return out;
    }

    static int appendScaled(float[] out, int off, float[] arr, float scale) {
        for (float v : arr) {
            out[off++] = v * scale;
        }

        return off;
    }

    static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];

        for (int r = 0; r < H; r++) {
            System.arraycopy(flat, r * W, a[r], 0, W);
        }

        return a;
    }

    static float[][] cropArray(float[][] src, int r0, int c0, int H, int W) {
        float[][] out = new float[H][W];

        for (int r = 0; r < H; r++) {
            System.arraycopy(src[r + r0], c0, out[r], 0, W);
        }

        return out;
    }

    static float bilinearSample2D(float[][] src, int H, int W, float gy, float gx) {
        float y = Math.max(0.0F, Math.min(H - 1.0F, gy));
        float x = Math.max(0.0F, Math.min(W - 1.0F, gx));
        int y0 = (int) y;
        int y1 = Math.min(H - 1, y0 + 1);
        int x0 = (int) x;
        int x1 = Math.min(W - 1, x0 + 1);
        float wy = y - y0;
        float wx = x - x0;
        return (1.0F - wy) * (1.0F - wx) * src[y0][x0]
                + (1.0F - wy) * wx * src[y0][x1]
                + wy * (1.0F - wx) * src[y1][x0]
                + wy * wx * src[y1][x1];
    }

    @Override
    public void close() {
        if (this.ownModels) {
            this.coarseModel.close();
            this.baseModel.close();
            this.decoderModel.close();
        }
    }

    static {
        if (COARSE_POOLING != 1) {
            throw new IllegalStateException(
                    "coarse_pooling=" + COARSE_POOLING + " is not supported in the Java pipeline yet");
        }

        COND_VALS = new float[COND_SNR.length];

        for (int i = 0; i < COND_SNR.length; i++) {
            COND_VALS[i] = (float) Math.log(COND_SNR[i] / 8.0);
        }

        COND_DIMS = new int[] {16, 16, 4, 16, 5, 1};
        int sumN = 0;

        for (int n : COND_DIMS) {
            sumN += n;
        }

        int k = COND_DIMS.length;
        float C = (float) Math.sqrt((double) sumN * k);
        MP_CONCAT_SCALES = new float[k];

        for (int i = 0; i < k; i++) {
            MP_CONCAT_SCALES[i] = C / (float) Math.sqrt(COND_DIMS[i]) / k;
        }

        float[] configuredHistogramRaw = WorldPipelineModelConfig.histogramRaw();
        HISTOGRAM_RAW =
                configuredHistogramRaw != null ? configuredHistogramRaw : new float[] {0.0F, 0.0F, 0.0F, 0.0F, 0.0F};
    }
}

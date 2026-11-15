package com.github.xandergos.terraindiffusionmc.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class SyntheticMapFactory {
    private static final int N_CHANNELS = 5;
    private static final float[] FREQUENCY_MULT = WorldPipelineModelConfig.frequencyMultipliers();
    private static final float BASE_FREQUENCY = 0.05F;
    private static final int[] OCTAVES = new int[] {4, 2, 4, 4, 4};
    private static final float LACUNARITY = 2.0F;
    private static final float GAIN = 0.5F;
    private final float[][] noiseQuantiles;
    private final float[][] dataQuantiles;
    private final float aTempStd;
    private final float bTempStd;
    private final float tempStdP1;
    private final float tempStdP99;
    private final FastNoiseLite[] noises = new FastNoiseLite[5];
    private static float[][] cachedDataQuantiles;
    private static float cachedATempStd;
    private static float cachedBTempStd;
    private static float cachedTempStdP1;
    private static float cachedTempStdP99;
    private static boolean dataLoaded = false;

    public SyntheticMapFactory(long worldSeed) {
        loadDataIfNeeded();
        this.dataQuantiles = cachedDataQuantiles;
        this.aTempStd = cachedATempStd;
        this.bTempStd = cachedBTempStd;
        this.tempStdP1 = cachedTempStdP1;
        this.tempStdP99 = cachedTempStdP99;
        this.noiseQuantiles = new float[5][];

        for (int ch = 0; ch < 5; ch++) {
            FastNoiseLite fnl = new FastNoiseLite((int) (worldSeed + ch + 1L & 2147483647L));
            fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
            fnl.SetFrequency(0.05F * FREQUENCY_MULT[ch]);
            fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
            fnl.SetFractalOctaves(OCTAVES[ch]);
            fnl.SetFractalLacunarity(2.0F);
            fnl.SetFractalGain(0.5F);
            this.noises[ch] = fnl;
            this.noiseQuantiles[ch] = buildNoiseQuantiles(fnl, 64, 1.0E-4F);
        }
    }

    private static synchronized void loadDataIfNeeded() {
        if (!dataLoaded) {
            try {
                ModelAssetManager.ensureAssetsReady();
                Path pipelineDataPath = ModelAssetManager.resolveAssetPath("pipeline_data.json");

                try (Reader reader = Files.newBufferedReader(pipelineDataPath, StandardCharsets.UTF_8)) {
                    JsonObject data = (JsonObject) new Gson().fromJson(reader, JsonObject.class);
                    int nQ = data.get("n_quantiles").getAsInt();
                    JsonArray dataArr = data.getAsJsonArray("data_quantile_tables");
                    cachedDataQuantiles = new float[5][nQ];

                    for (int ch = 0; ch < 5; ch++) {
                        JsonArray dq = dataArr.get(ch).getAsJsonArray();

                        for (int i = 0; i < nQ; i++) {
                            cachedDataQuantiles[ch][i] = dq.get(i).getAsFloat();
                        }
                    }

                    cachedATempStd = data.get("a_temp_std").getAsFloat();
                    cachedBTempStd = data.get("b_temp_std").getAsFloat();
                    cachedTempStdP1 = data.get("temp_std_p1").getAsFloat();
                    cachedTempStdP99 = data.get("temp_std_p99").getAsFloat();
                    dataLoaded = true;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to load pipeline_data.json", e);
            }
        }
    }

    static float[] buildNoiseQuantiles(FastNoiseLite fnl, int nQuantiles, float eps) {
        float[] values = new float[1048576];
        int k = 0;

        for (int r = 0; r < 1024; r++) {
            for (int c = 0; c < 1024; c++) {
                values[k++] = fnl.GetNoise(c * 32, r * 32);
            }
        }

        Arrays.sort(values);
        float[] q = new float[nQuantiles];
        int n = values.length;

        for (int i = 0; i < nQuantiles; i++) {
            float pct = eps + i * (1.0F - 2.0F * eps) / (nQuantiles - 1);
            float idx = pct * (n - 1);
            int lo = (int) idx;
            int hi = Math.min(lo + 1, n - 1);
            q[i] = values[lo] + (idx - lo) * (values[hi] - values[lo]);
        }

        float minDiff = Float.MAX_VALUE;

        for (int i = 1; i < nQuantiles; i++) {
            if (q[i] > q[i - 1]) {
                minDiff = Math.min(minDiff, q[i] - q[i - 1]);
            }
        }

        if (minDiff == Float.MAX_VALUE) {
            minDiff = 1.0E-10F;
        }

        for (int i = 1; i < nQuantiles; i++) {
            if (q[i] <= q[i - 1]) {
                q[i] = q[i - 1] + minDiff * 0.1F;
            }
        }

        return q;
    }

    public float[][][] sample(int x1, int y1, int x2, int y2) {
        int H = y2 - y1;
        int W = x2 - x1;
        float[][] rawChannels = new float[5][H * W];

        for (int ch = 0; ch < 5; ch++) {
            FastNoiseLite fnl = this.noises[ch];
            float[] nq = this.noiseQuantiles[ch];
            float[] dq = this.dataQuantiles[ch];
            int k = 0;

            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    float noiseVal = fnl.GetNoise(x1 + c, y1 + r);
                    rawChannels[ch][k++] = interp(noiseVal, nq, dq);
                }
            }
        }

        float[][] ch2d = new float[5][H * W];
        System.arraycopy(rawChannels[0], 0, ch2d[0], 0, H * W);
        System.arraycopy(rawChannels[1], 0, ch2d[1], 0, H * W);
        System.arraycopy(rawChannels[2], 0, ch2d[2], 0, H * W);
        System.arraycopy(rawChannels[3], 0, ch2d[3], 0, H * W);
        System.arraycopy(rawChannels[4], 0, ch2d[4], 0, H * W);
        float[][][] result = new float[5][H][W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float elev = ch2d[0][idx];
                float temp = ch2d[1][idx];
                float tempStd = ch2d[2][idx];
                float precip = ch2d[3][idx];
                float precipStd = ch2d[4][idx];
                float lapseRate = -6.5F + 0.0015F * precip;
                lapseRate = Math.max(-9.8F, Math.min(-4.0F, lapseRate)) / 1000.0F;
                temp += lapseRate * Math.max(0.0F, elev);
                temp = Math.max(-10.0F, Math.min(40.0F, temp));
                float baseline = this.aTempStd * temp + this.bTempStd;
                float t01 = (tempStd - this.tempStdP1) / (this.tempStdP99 - this.tempStdP1);
                float baselineClipped = Math.max(this.tempStdP1, -baseline);
                tempStd = t01 * (this.tempStdP99 - baselineClipped) + baselineClipped + baseline;
                tempStd = Math.max(tempStd, 20.0F);
                precipStd *= Math.max(0.0F, (185.0F - 0.04111F * precip) / 185.0F);
                float elevSqrt = (float) (Math.signum(elev) * Math.sqrt(Math.abs(elev)));
                result[0][r][c] = elevSqrt;
                result[1][r][c] = temp;
                result[2][r][c] = tempStd;
                result[3][r][c] = precip;
                result[4][r][c] = precipStd;
            }
        }

        return result;
    }

    static float interp(float x, float[] xp, float[] fp) {
        int n = xp.length;
        if (x <= xp[0]) {
            return fp[0];
        }

        if (x >= xp[n - 1]) {
            return fp[n - 1];
        }

        int lo = 0;
        int hi = n - 1;

        while (hi - lo > 1) {
            int mid = lo + hi >>> 1;
            if (xp[mid] <= x) {
                lo = mid;
            } else {
                hi = mid;
            }
        }

        float t = (x - xp[lo]) / (xp[hi] - xp[lo]);
        return fp[lo] + t * (fp[hi] - fp[lo]);
    }
}

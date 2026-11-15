package com.github.xandergos.terraindiffusionmc.pipeline;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorldPipelineModelConfig {
    private static final String CONFIG_FILE_NAME = "world_pipeline_config.json";
    private static final Gson GSON = new Gson();

    private WorldPipelineModelConfig() {}

    public static float nativeResolution() {
        return config().nativeResolution;
    }

    public static int latentCompression() {
        return config().latentCompression;
    }

    public static float[] coarseMeans() {
        return (float[]) config().coarseMeans.clone();
    }

    public static float[] coarseStds() {
        return (float[]) config().coarseStds.clone();
    }

    public static float[] conditioningSnr() {
        return (float[]) config().condSnr.clone();
    }

    public static float[] frequencyMultipliers() {
        return (float[]) config().frequencyMult.clone();
    }

    public static float residualMean() {
        return config().residualMean;
    }

    public static float residualStd() {
        return config().residualStd;
    }

    public static int coarsePooling() {
        return config().coarsePooling;
    }

    public static String elevCoarsePoolMode() {
        return config().elevCoarsePoolMode;
    }

    public static String p5CoarsePoolMode() {
        return config().p5CoarsePoolMode;
    }

    public static float[] histogramRaw() {
        WorldPipelineModelConfig.ConfigJson loadedConfig = config();
        return loadedConfig.histogramRaw == null ? null : (float[]) loadedConfig.histogramRaw.clone();
    }

    public static float dropWaterPercent() {
        return config().dropWaterPercent;
    }

    private static WorldPipelineModelConfig.ConfigJson config() {
        return WorldPipelineModelConfig.ConfigHolder.CONFIG;
    }

    private static WorldPipelineModelConfig.ConfigJson loadConfig() {
        try {
            ModelAssetManager.ensureAssetsReady();
            Path configPath = ModelAssetManager.resolveAssetPath("world_pipeline_config.json");

            try (Reader configReader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                WorldPipelineModelConfig.ConfigJson config =
                        (WorldPipelineModelConfig.ConfigJson)
                                GSON.fromJson(configReader, WorldPipelineModelConfig.ConfigJson.class);
                validateConfig(config);
                return config;
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to load model config from world_pipeline_config.json: " + exception.getMessage(),
                    exception);
        }
    }

    private static void validateConfig(WorldPipelineModelConfig.ConfigJson config) {
        if (config == null) {
            throw new IllegalArgumentException("Model config is empty");
        }

        requireArrayLength(config.coarseMeans, 6, "coarse_means");
        requireArrayLength(config.coarseStds, 6, "coarse_stds");
        requireArrayLength(config.condSnr, 5, "cond_snr");
        requireArrayLength(config.frequencyMult, 5, "frequency_mult");
        if (config.coarsePooling <= 0) {
            throw new IllegalArgumentException("coarse_pooling must be > 0");
        }

        if (config.histogramRaw != null && config.histogramRaw.length != 5) {
            throw new IllegalArgumentException("histogram_raw must contain exactly 5 values when provided");
        }

        if (config.latentCompression <= 0) {
            throw new IllegalArgumentException("latent_compression must be > 0");
        }

        validateNativeResolution(config.nativeResolution);
    }

    static void validateNativeResolution(float nativeResolution) {
        if (nativeResolution <= 0.0F) {
            throw new IllegalArgumentException("native_resolution must be > 0");
        }

        if (Float.compare(nativeResolution, 30.0F) != 0) {
            throw new IllegalArgumentException(
                    "native_resolution "
                            + nativeResolution
                            + " does not match the generation contract of 30.0 metres; changing model geometry requires a generator-version change");
        }
    }

    private static void requireArrayLength(float[] values, int expectedLength, String fieldName) {
        if (values == null || values.length != expectedLength) {
            throw new IllegalArgumentException(fieldName + " must contain exactly " + expectedLength + " values");
        }
    }

    private static final class ConfigHolder {
        private static final WorldPipelineModelConfig.ConfigJson CONFIG = WorldPipelineModelConfig.loadConfig();
    }

    private static final class ConfigJson {
        @SerializedName("_class_name")
        String className;

        @SerializedName("_diffusers_version")
        String diffusersVersion;

        @SerializedName("coarse_means")
        float[] coarseMeans;

        @SerializedName("coarse_pooling")
        int coarsePooling;

        @SerializedName("coarse_stds")
        float[] coarseStds;

        @SerializedName("cond_snr")
        float[] condSnr;

        @SerializedName("drop_water_pct")
        float dropWaterPercent;

        @SerializedName("elev_coarse_pool_mode")
        String elevCoarsePoolMode;

        @SerializedName("frequency_mult")
        float[] frequencyMult;

        @SerializedName("histogram_raw")
        float[] histogramRaw;

        @SerializedName("latent_compression")
        int latentCompression;

        @SerializedName("native_resolution")
        float nativeResolution;

        @SerializedName("p5_coarse_pool_mode")
        String p5CoarsePoolMode;

        @SerializedName("residual_mean")
        float residualMean;

        @SerializedName("residual_std")
        float residualStd;
    }
}

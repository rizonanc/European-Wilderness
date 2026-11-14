package com.github.xandergos.terraindiffusionmc.worldgen.settings;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record WaterGenerationOptions(int widthVariation, int depthVariation, int lakeThreshold, boolean mountainRivers) {
    public static final WaterGenerationOptions DEFAULT = new WaterGenerationOptions(100, 100, 100);
    public WaterGenerationOptions(int widthVariation, int depthVariation, int lakeThreshold) {
        this(widthVariation, depthVariation, lakeThreshold, true);
    }

    public static final Codec<WaterGenerationOptions> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.intRange(0, 200).fieldOf("width_variation").forGetter(WaterGenerationOptions::widthVariation),
        Codec.intRange(0, 200).fieldOf("depth_variation").forGetter(WaterGenerationOptions::depthVariation),
        Codec.intRange(25, 400).fieldOf("lake_threshold").forGetter(WaterGenerationOptions::lakeThreshold),
        Codec.BOOL.optionalFieldOf("mountain_rivers", true).forGetter(WaterGenerationOptions::mountainRivers)
    ).apply(instance, WaterGenerationOptions::new));

    public WaterGenerationOptions {
        if (widthVariation < 0 || widthVariation > 200 || depthVariation < 0 || depthVariation > 200
                || lakeThreshold < 25 || lakeThreshold > 400) throw new IllegalArgumentException("Invalid water generation options");
    }

    public static WaterGenerationOptions configured() {
        return new WaterGenerationOptions(TerrainDiffusionConfig.percent("rivers.width_variation"),
            TerrainDiffusionConfig.percent("rivers.depth_variation"), TerrainDiffusionConfig.percent("rivers.lake_threshold"), TerrainDiffusionConfig.mountainRivers());
    }
}

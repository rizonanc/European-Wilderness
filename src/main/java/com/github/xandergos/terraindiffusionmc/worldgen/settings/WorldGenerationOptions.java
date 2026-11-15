package com.github.xandergos.terraindiffusionmc.worldgen.settings;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.config.TerrainSettingsCatalog;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;

public record WorldGenerationOptions(
        int mountainRelief,
        int biomeVariety,
        WaterGenerationOptions water,
        BiomeBalance biomeBalance,
        int climateShift) {
    public static final Codec<WorldGenerationOptions> CODEC =
            RecordCodecBuilder.create(instance ->
                    instance.group(
                                    Codec.intRange(0, 200)
                                            .fieldOf("mountain_relief")
                                            .forGetter(WorldGenerationOptions::mountainRelief),
                                    Codec.intRange(0, 100)
                                            .fieldOf("biome_variety")
                                            .forGetter(WorldGenerationOptions::biomeVariety),
                                    WaterGenerationOptions.CODEC
                                            .fieldOf("water")
                                            .forGetter(WorldGenerationOptions::water),
                                    BiomeBalance.CODEC
                                            .optionalFieldOf("biome_balance", BiomeBalance.DEFAULT)
                                            .forGetter(WorldGenerationOptions::biomeBalance),
                                    Codec.intRange(-8, 8)
                                            .optionalFieldOf("climate_shift", 0)
                                            .forGetter(WorldGenerationOptions::climateShift))
                            .apply(instance, WorldGenerationOptions::new));

    public static WorldGenerationOptions configured() {
        return new WorldGenerationOptions(
                TerrainDiffusionConfig.percent("terrain.mountain_relief"),
                TerrainDiffusionConfig.percent("terrain.biome_variety"),
                WaterGenerationOptions.configured(),
                new BiomeBalance(
                        TerrainDiffusionConfig.percent("terrain.mountain_amount"),
                        TerrainDiffusionConfig.percent("terrain.ocean_amount"),
                        TerrainDiffusionConfig.percent("terrain.desert_amount"),
                        TerrainDiffusionConfig.percent("terrain.forest_amount"),
                        TerrainDiffusionConfig.percent("terrain.field_amount")),
                climateShift(TerrainDiffusionConfig.climate()));
    }

    public static WorldGenerationOptions fromSettings(Map<String, String> values) {
        return new WorldGenerationOptions(
                percent(values, "terrain.mountain_relief"),
                percent(values, "terrain.biome_variety"),
                new WaterGenerationOptions(
                        percent(values, "rivers.width_variation"),
                        percent(values, "rivers.depth_variation"),
                        percent(values, "rivers.lake_threshold"),
                        Boolean.parseBoolean(values.get("rivers.mountain_rivers"))),
                new BiomeBalance(
                        percent(values, "terrain.mountain_amount"),
                        percent(values, "terrain.ocean_amount"),
                        percent(values, "terrain.desert_amount"),
                        percent(values, "terrain.forest_amount"),
                        percent(values, "terrain.field_amount")),
                climateShift(values.getOrDefault("terrain.climate", "temperate")));
    }

    private static int climateShift(String climate) {
        return switch (climate) {
            case "cool" -> -3;
            case "cold" -> -7;
            default -> 0;
        };
    }

    private static int percent(Map<String, String> values, String key) {
        var range = TerrainSettingsCatalog.PERCENTAGES.get(key);
        try {
            return Math.clamp(Integer.parseInt(values.get(key)), range.minimum(), range.maximum());
        } catch (NumberFormatException invalid) {
            return range.defaultValue();
        }
    }
}

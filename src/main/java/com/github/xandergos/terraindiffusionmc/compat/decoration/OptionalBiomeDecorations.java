package com.github.xandergos.terraindiffusionmc.compat.decoration;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.mixin.decoration.BiomeSelectionRegistries;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import java.util.List;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.gen.GenerationStep;

public final class OptionalBiomeDecorations {
    private static final String MOD = "terrain-diffusion-mc";
    private static final int GEOPHILIC = frequency("geophilic");
    private static final int WILDER_WILD = frequency("wilder_wild");

    private static int frequency(String option) {
        return TerrainDiffusionConfig.enabled("nature." + option)
                ? TerrainDiffusionConfig.percent("nature." + option + "_frequency")
                : 0;
    }

    public static int frequencyFor(Identifier id) {
        return switch (id.getNamespace()) {
            case "geophilic" -> GEOPHILIC;
            case "wilderwild" -> WILDER_WILD;
            default -> 100;
        };
    }

    public static boolean hasFrequencyOverride() {
        return GEOPHILIC < 100 || WILDER_WILD < 100;
    }

    private OptionalBiomeDecorations() {}

    public static boolean isTerrainDiffusion(BiomeSelectionContext context) {
        var registries = ((BiomeSelectionRegistries) context).terrainDiffusion$getRegistries();
        var overworld = registries.getOrThrow(RegistryKeys.DIMENSION).get(DimensionOptions.OVERWORLD);
        return overworld != null && overworld.chunkGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource;
    }

    public static void register() {
        BiomeModifications.create(Identifier.of(MOD, "optional_biome_decorations"))
                .add(
                        ModificationPhase.POST_PROCESSING,
                        context -> isTerrainDiffusion(context)
                                && !features(context.getBiomeKey().getValue()).isEmpty(),
                        (context, modification) -> {
                            var registries = ((BiomeSelectionRegistries) context).terrainDiffusion$getRegistries();
                            var placed = registries.getOrThrow(RegistryKeys.PLACED_FEATURE);
                            for (String feature : features(context.getBiomeKey().getValue())) {
                                var key = RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of(feature));
                                if (frequencyFor(key.getValue()) > 0 && placed.contains(key)) {
                                    // Let terrain, trees and river finishes run before optional surface decorations.
                                    modification
                                            .getGenerationSettings()
                                            .addFeature(GenerationStep.Feature.TOP_LAYER_MODIFICATION, key);
                                }
                            }
                        });
    }

    static List<String> features(Identifier biome) {
        String path = biome.getPath();
        if (biome.getNamespace().equals(MOD)) {
            return switch (path) {
                case "european_deciduous", "european_mixed", "forest_sparse" -> forest();
                case "european_coniferous", "taiga_sparse" -> taiga();
                case "snowy_taiga_sparse", "grove" -> List.of("geophilic:taiga/grove/logs");
                case "european_meadow" -> meadow();
                default -> List.of();
            };
        }
        if (!biome.getNamespace().equals("minecraft")) return List.of();
        return switch (path) {
            case "forest" -> forest();
            case "taiga" -> taiga();
            case "snowy_taiga" -> List.of("geophilic:taiga/grove/logs");
            case "plains", "meadow" -> meadow();
            case "swamp" ->
                List.of("geophilic:swamp/logs", "wilderwild:cattail_uncommon", "wilderwild:patch_algae_small");
            case "jungle" -> List.of("geophilic:jungle/bushes", "geophilic:jungle/logs");
            case "windswept_hills" -> List.of("geophilic:windswept/rocks");
            default -> List.of();
        };
    }

    private static List<String> forest() {
        return List.of("geophilic:forest/logs", "geophilic:forest/rocks", "wilderwild:clovers_sparse");
    }

    private static List<String> taiga() {
        return List.of("geophilic:taiga/logs", "geophilic:taiga/rocks", "wilderwild:patch_spruce_leaf_litter");
    }

    private static List<String> meadow() {
        return List.of("geophilic:plains/rocks", "wilderwild:wildflowers_sparse", "wilderwild:milkweed_rare");
    }
}

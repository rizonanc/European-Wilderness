package com.github.xandergos.terraindiffusionmc.compat.decoration;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import java.util.List;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.ModificationPhase;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

public final class AlexMobSpawns {
    private AlexMobSpawns() {}

    public static void register() {
        if (!FabricLoader.getInstance().isModLoaded("alexsmobs")) return;
        int percent = TerrainDiffusionConfig.percent("animals.alex_weight");
        boolean land = TerrainDiffusionConfig.enabled("animals.alex_land");
        boolean aquatic = TerrainDiffusionConfig.enabled("animals.alex_aquatic");
        boolean hostile = TerrainDiffusionConfig.enabled("animals.alex_hostile");
        if (percent == 100 && land && aquatic && hostile) return;

        BiomeModifications.create(Identifier.of("terrain-diffusion-mc", "alex_spawn_settings"))
                .add(
                        ModificationPhase.POST_PROCESSING,
                        OptionalBiomeDecorations::isTerrainDiffusion,
                        modification -> {
                            var settings = modification.getSpawnSettings();
                            for (SpawnGroup group : SpawnGroup.values()) {
                                boolean enabled =
                                        switch (group) {
                                            case MONSTER -> hostile;
                                            case WATER_CREATURE, WATER_AMBIENT, UNDERGROUND_WATER_CREATURE, AXOLOTLS ->
                                                    aquatic;
                                            default -> land;
                                        };
                                if (enabled && percent == 100) continue;
                                var original = List.copyOf(settings.getSpawnEntries(group));
                                if (!settings.removeSpawns(
                                        (current, spawn) -> current == group && isAlexMob(spawn.type()))) continue;
                                if (!enabled || percent == 0) continue;
                                for (var weighted : original) {
                                    var entry = weighted.value();
                                    if (isAlexMob(entry.type()) && weighted.weight() > 0) {
                                        int weight =
                                                (int)
                                                        Math.clamp(
                                                                Math.round(weighted.weight() * (percent / 100.0)),
                                                                1L,
                                                                Integer.MAX_VALUE);
                                        settings.addSpawn(group, entry, weight);
                                    }
                                }
                            }
                        });
    }

    private static boolean isAlexMob(EntityType<?> type) {
        return Registries.ENTITY_TYPE.getId(type).getNamespace().equals("alexsmobs");
    }
}

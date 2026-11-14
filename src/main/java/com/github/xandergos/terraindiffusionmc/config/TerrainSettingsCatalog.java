package com.github.xandergos.terraindiffusionmc.config;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** One catalogue for the editor and screen, so new settings cannot silently disappear. */
public final class TerrainSettingsCatalog {
    public record Page(String name, List<String> keys) {}

    public static final List<Page> PAGES = List.of(
        new Page("models", List.of("inference.device", "inference.offload_models", "inference.gpu_session_cache", "validate_model")),
        new Page("explorer", List.of("explorer.port", "spawn_search.initial_size", "spawn_search.max_size")),
        new Page("generation", List.of("tile_size", "preparation.mode")),
        new Page("performance", List.of("performance.disk_quota_mb", "performance.prefetch", "performance.water_prefetch", "performance.water_write_behind")),
        new Page("voxy", List.of("compat.voxy.enabled", "compat.voxy_worldgen.enabled", "compat.voxy.prepare_ahead", "compat.voxy.trees")),
        new Page("lod", List.of("compat.voxy.source", "compat.voxy.radius_mode", "compat.voxy.radius_blocks", "compat.voxy.detail_radius_blocks", "compat.voxy.coarse_experimental")),
        new Page("visibility", List.of()),
        new Page("compatibility", List.of("compat.roadweaver.enabled")),
        new Page("rivers", List.of("rivers.current", "rivers.boats", "rivers.items", "rivers.strength",
            "rivers.mountain_rivers", "rivers.width_variation", "rivers.depth_variation", "rivers.lake_threshold")),
        new Page("nature", List.of("nature.geophilic", "nature.geophilic_frequency", "nature.wilder_wild",
            "nature.wilder_wild_frequency", "nature.tree_density")),
        new Page("animals", List.of("animals.alex_weight", "animals.alex_land", "animals.alex_aquatic", "animals.alex_hostile")),
        new Page("terrain", List.of("terrain.mountain_amount", "terrain.ocean_amount", "terrain.desert_amount",
            "terrain.forest_amount", "terrain.field_amount", "terrain.mountain_relief", "terrain.biome_variety", "terrain.climate"))
    );

    public static final List<String> KEYS = PAGES.stream().flatMap(page -> page.keys().stream()).toList();
    public static final Set<String> BOOLEANS = Set.of(
        "inference.offload_models", "validate_model", "compat.voxy.enabled", "compat.voxy_worldgen.enabled",
        "compat.voxy.prepare_ahead", "compat.voxy.trees", "compat.voxy.coarse_experimental",
        "performance.water_write_behind", "compat.roadweaver.enabled",
        "rivers.current", "rivers.boats", "rivers.items", "rivers.mountain_rivers", "nature.geophilic", "nature.wilder_wild",
        "animals.alex_land", "animals.alex_aquatic", "animals.alex_hostile"
    );

    public record Percent(int minimum, int maximum, int defaultValue) {}

    public static final Map<String, Percent> PERCENTAGES = Map.ofEntries(
        Map.entry("rivers.strength", new Percent(0, 200, 100)),
        Map.entry("rivers.width_variation", new Percent(0, 200, 100)),
        Map.entry("rivers.depth_variation", new Percent(0, 200, 100)),
        Map.entry("rivers.lake_threshold", new Percent(25, 400, 100)),
        Map.entry("nature.geophilic_frequency", new Percent(0, 100, 100)),
        Map.entry("nature.wilder_wild_frequency", new Percent(0, 100, 100)),
        Map.entry("nature.tree_density", new Percent(0, 200, 100)),
        Map.entry("animals.alex_weight", new Percent(0, 300, 100)),
        Map.entry("terrain.mountain_amount", new Percent(0, 200, 100)),
        Map.entry("terrain.ocean_amount", new Percent(0, 200, 100)),
        Map.entry("terrain.desert_amount", new Percent(0, 200, 100)),
        Map.entry("terrain.forest_amount", new Percent(0, 200, 100)),
        Map.entry("terrain.field_amount", new Percent(0, 200, 100)),
        Map.entry("terrain.mountain_relief", new Percent(0, 200, 100)),
        Map.entry("terrain.biome_variety", new Percent(0, 100, 0))
    );

    public static String[] choices(String key) {
        if (BOOLEANS.contains(key)) {
            return new String[] {"true", "false"};
        }

        return switch (key) {
            case "terrain.climate" -> new String[] {"temperate", "cool", "cold"};
            case "inference.device" -> new String[] {"gpu", "cpu", "auto"};
            case "preparation.mode" -> new String[] {"on_demand", "prepared_only"};
            case "performance.prefetch", "performance.water_prefetch" -> new String[] {"off", "adaptive"};
            case "compat.voxy.radius_mode" -> new String[] {"auto", "manual"};
            case "compat.voxy.source" -> new String[] {"chunks", "prepared"};
            default -> null;
        };
    }

    public static String application(String key) {
        return switch (key) {
            case "terrain.climate", "terrain.mountain_amount", "terrain.ocean_amount", "terrain.desert_amount",
                "terrain.forest_amount", "terrain.field_amount", "terrain.mountain_relief", "terrain.biome_variety", "rivers.width_variation",
                "rivers.depth_variation", "rivers.lake_threshold", "rivers.mountain_rivers", "tile_size" -> "new_world";
            case "nature.geophilic", "nature.geophilic_frequency", "nature.wilder_wild",
                "nature.wilder_wild_frequency", "nature.tree_density", "compat.roadweaver.enabled" -> "new_chunks";
            default -> "restart";
        };
    }

    public static int pageFor(String key) {
        for (int index = 0; index < PAGES.size(); index++) {
            if (PAGES.get(index).keys().contains(key)) {
                return index;
            }
        }
        return 0;
    }

    private TerrainSettingsCatalog() {}
}

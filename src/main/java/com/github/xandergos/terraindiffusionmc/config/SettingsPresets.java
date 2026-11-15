package com.github.xandergos.terraindiffusionmc.config;

import java.util.List;
import java.util.Map;

public final class SettingsPresets {
    public record Preset(String id, Map<String, String> values) {}

    public static final List<Preset> ALL = List.of(
            new Preset(
                    "balanced",
                    Map.of(
                            "terrain.mountain_relief",
                            "100",
                            "terrain.biome_variety",
                            "0",
                            "rivers.width_variation",
                            "100",
                            "rivers.depth_variation",
                            "100",
                            "rivers.lake_threshold",
                            "100",
                            "nature.tree_density",
                            "100",
                            "nature.geophilic_frequency",
                            "100",
                            "nature.wilder_wild_frequency",
                            "100")),
            new Preset("mountains", Map.of("terrain.mountain_relief", "175")),
            new Preset(
                    "nature",
                    Map.of(
                            "terrain.biome_variety",
                            "70",
                            "nature.tree_density",
                            "125",
                            "nature.geophilic",
                            "true",
                            "nature.geophilic_frequency",
                            "100",
                            "nature.wilder_wild",
                            "true",
                            "nature.wilder_wild_frequency",
                            "100")),
            new Preset(
                    "water",
                    Map.of(
                            "rivers.width_variation",
                            "150",
                            "rivers.depth_variation",
                            "150",
                            "rivers.lake_threshold",
                            "60")),
            new Preset(
                    "alps",
                    Map.of(
                            "terrain.climate",
                            "cool",
                            "terrain.mountain_amount",
                            "165",
                            "terrain.mountain_relief",
                            "165",
                            "terrain.ocean_amount",
                            "65",
                            "terrain.forest_amount",
                            "80",
                            "terrain.field_amount",
                            "115",
                            "terrain.biome_variety",
                            "35")),
            new Preset(
                    "carpathians",
                    Map.of(
                            "terrain.climate",
                            "temperate",
                            "terrain.mountain_amount",
                            "125",
                            "terrain.mountain_relief",
                            "110",
                            "terrain.ocean_amount",
                            "65",
                            "terrain.forest_amount",
                            "165",
                            "terrain.field_amount",
                            "80",
                            "terrain.biome_variety",
                            "40")),
            new Preset(
                    "scandinavia",
                    Map.of(
                            "terrain.climate",
                            "cold",
                            "terrain.mountain_amount",
                            "120",
                            "terrain.mountain_relief",
                            "125",
                            "terrain.ocean_amount",
                            "145",
                            "terrain.forest_amount",
                            "140",
                            "terrain.field_amount",
                            "85",
                            "terrain.biome_variety",
                            "55")),
            new Preset(
                    "performance",
                    Map.of(
                            "nature.tree_density",
                            "65",
                            "nature.geophilic_frequency",
                            "50",
                            "nature.wilder_wild_frequency",
                            "50",
                            "compat.voxy.coarse_experimental",
                            "false")));

    private SettingsPresets() {}
}

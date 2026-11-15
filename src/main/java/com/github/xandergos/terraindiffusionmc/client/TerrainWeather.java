package com.github.xandergos.terraindiffusionmc.client;

import net.minecraft.world.World;

public final class TerrainWeather {
    private TerrainWeather() {}

    public static boolean applies(World world) {
        if (world == null) return false;
        return world.getDimensionEntry()
                .getKey()
                .map(key -> key.getValue().getNamespace().equals("terrain-diffusion-mc"))
                .orElse(false);
    }

    public static boolean snow(int aboveSea, float temperature) {
        return aboveSea >= 150 && temperature - aboveSea * 0.003F < 0.15F;
    }
}

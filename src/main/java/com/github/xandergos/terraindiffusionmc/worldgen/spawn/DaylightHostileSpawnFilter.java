package com.github.xandergos.terraindiffusionmc.worldgen.spawn;

public final class DaylightHostileSpawnFilter {
    private DaylightHostileSpawnFilter() {}

    public static boolean shouldBlock(
            boolean naturalSpawn, boolean monsterGroup, boolean daytime, boolean surfaceSpawn, boolean ourForestBiome) {
        return naturalSpawn && monsterGroup && daytime && surfaceSpawn && ourForestBiome;
    }

    public static boolean isSurfaceSpawn(int spawnY, int groundTopY) {
        return spawnY >= groundTopY - 1;
    }

    public static boolean isOurForestBiome(String namespace, String path) {
        return !"terrain-diffusion-mc".equals(namespace)
                ? false
                : "european_deciduous".equals(path)
                        || "european_mixed".equals(path)
                        || "european_coniferous".equals(path)
                        || "european_meadow".equals(path)
                        || "forest_sparse".equals(path)
                        || "taiga_sparse".equals(path)
                        || "snowy_taiga_sparse".equals(path)
                        || "grove".equals(path);
    }
}

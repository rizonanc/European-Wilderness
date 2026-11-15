package com.github.xandergos.terraindiffusionmc.worldgen.terrain;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanEcology;

public final class UpstreamTerrainAdapter {
    private UpstreamTerrainAdapter() {}

    public static TerrainTile toTile(TerrainTileKey key, LocalTerrainProvider.HeightmapData data) {
        return toTile(key, data, 0);
    }

    public static TerrainTile toTile(TerrainTileKey key, LocalTerrainProvider.HeightmapData data, int biomeVariety) {
        if (data != null && data.heightmap != null && data.biomeIds != null) {
            int size = key.tileSize();
            if (data.width == size && data.height == size) {
                short[] heights = new short[size * size];
                short[] biomes = new short[size * size];

                for (int row = 0; row < size; row++) {
                    System.arraycopy(data.heightmap[row], 0, heights, row * size, size);
                    System.arraycopy(data.biomeIds[row], 0, biomes, row * size, size);
                    if (biomeVariety != 0)
                        for (int column = 0; column < size; column++) {
                            int index = row * size + column;
                            biomes[index] =
                                    EuropeanEcology.varyBiome(
                                            key.seed(),
                                            key.blockStartX() + column,
                                            key.blockStartZ() + row,
                                            biomes[index],
                                            biomeVariety);
                        }
                }

                float[] climate = null;
                if (data.climate != null && data.climate.length == 4 * size * size) {
                    climate = (float[]) data.climate.clone();
                }

                return new TerrainTile(key, heights, biomes, climate);
            } else {
                throw new IllegalArgumentException(
                        "provider region " + data.width + "x" + data.height + " does not match tile size " + size);
            }
        } else {
            throw new IllegalArgumentException("provider data must not be null");
        }
    }
}

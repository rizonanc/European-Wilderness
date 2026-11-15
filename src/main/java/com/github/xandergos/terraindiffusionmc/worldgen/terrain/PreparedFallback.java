package com.github.xandergos.terraindiffusionmc.worldgen.terrain;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.PerformanceProfile;
import java.util.Optional;

public final class PreparedFallback {
    public static final short FALLBACK_HEIGHT_METERS = 100;
    public static final short FALLBACK_BIOME_ID = 1;

    private PreparedFallback() {}

    public static short fallbackHeightMeters(int blockX, int blockZ) {
        return 100;
    }

    public static short fallbackBiomeId(int blockX, int blockZ) {
        return 1;
    }

    public static PerformanceProfile.PreparationMode mode() {
        return TerrainDiffusionConfig.preparationMode();
    }

    public static TileAssembler.TileFunction requiredTilesFor(
            TerrainRepository repository, PerformanceProfile.PreparationMode mode) {
        return mode == PerformanceProfile.PreparationMode.PREPARED_ONLY
                ? key -> repository.peek(key).orElseThrow(() -> new PreparedFallback.PreparedTileMissingException(key))
                : repository::get;
    }

    public static TileAssembler.AssembledRegion regionFor(
            TerrainRepository repository,
            long seed,
            String fingerprint,
            PerformanceProfile.PreparationMode mode,
            int i1,
            int j1,
            int i2,
            int j2)
            throws Exception {
        return mode == PerformanceProfile.PreparationMode.PREPARED_ONLY
                ? TileAssembler.assembleReady(
                        i1,
                        j1,
                        i2,
                        j2,
                        repository.tileSize(),
                        seed,
                        fingerprint,
                        repository::peek,
                        (short) 100,
                        (short) 1)
                : TileAssembler.assemble(i1, j1, i2, j2, repository.tileSize(), seed, fingerprint, repository::get);
    }

    public static short biomeFor(
            TerrainRepository repository,
            TerrainTileKey key,
            int blockX,
            int blockZ,
            PerformanceProfile.PreparationMode mode)
            throws Exception {
        if (mode == PerformanceProfile.PreparationMode.PREPARED_ONLY) {
            Optional<TerrainTile> ready = repository.peek(key);
            return ready.isPresent()
                    ? ready.get().biomeAt(key.localX(blockX), key.localZ(blockZ))
                    : fallbackBiomeId(blockX, blockZ);
        } else {
            TerrainTile tile = repository.get(key);
            return tile.biomeAt(key.localX(blockX), key.localZ(blockZ));
        }
    }

    public static final class PreparedTileMissingException extends RuntimeException {
        public PreparedTileMissingException(TerrainTileKey key) {
            super("Prepared terrain tile is unavailable: " + key);
        }
    }
}

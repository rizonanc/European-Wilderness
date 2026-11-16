package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.SpawnChoice;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.WorldHeightProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SpawnSelector {
    private static final Logger LOG = LoggerFactory.getLogger(SpawnSelector.class);

    private SpawnSelector() {}

    public static BlockPos findSpawnBlockPos() {
        WorldSession session = WorldSession.current();
        WorldSession.requireActive(session);
        int scale = session.profile().worldScale();
        int initialSize = TerrainDiffusionConfig.spawnSearchInitialSize();
        int maxSize = TerrainDiffusionConfig.spawnSearchMaxSize();

        for (int regionSize = initialSize; regionSize <= maxSize; regionSize += 8) {
            int halfSize = regionSize / 2;
            int ci0 = -halfSize;
            int cj0 = -halfSize;
            int ci1 = halfSize;
            int cj1 = halfSize;
            LOG.info("Querying coarse map to find spawn. Region size: {} x {}", regionSize, regionSize);
            FloatTensor coarse = null;
            Exception lookupFailure = null;

            try {
                coarse = LocalTerrainProvider.getSpawnCoarse(ci0, cj0, ci1, cj1, session);
            } catch (Exception e) {
                lookupFailure = e;
                LOG.error("SpawnSelector: failed to query coarse map at size {}", regionSize, e);
            }

            WorldSession.requireActive(session);
            if (lookupFailure == null) {
                int H = ci1 - ci0;
                int W = cj1 - cj0;
                BlockPos candidate = findNearestLandPixel(coarse, H, W, ci0, cj0, session, scale);
                if (candidate != null) {
                    LOG.info(
                            "SpawnSelector: found land spawn at {} (coarse region {}x{})",
                            new Object[] {candidate, regionSize, regionSize});
                    WorldSession.requireActive(session);
                    return candidate;
                }

                LOG.debug("SpawnSelector: no land found in {}x{} coarse region, expanding", regionSize, regionSize);
            }
        }

        LOG.warn(
                "SpawnSelector: no land found within max coarse region {}x{}, falling back to origin",
                maxSize,
                maxSize);
        BlockPos fallback = fallbackToOrigin(session, scale);
        WorldSession.requireActive(session);
        return fallback;
    }

    public static BlockPos findSelectedSpawn(SpawnChoice choice) {
        WorldSession session = WorldSession.current();
        WorldSession.requireActive(session);
        int size = 97, offset = size / 2;
        int x0 = choice.x() - offset, z0 = choice.z() - offset;
        var data = LocalTerrainProvider.getInstance().fetchHeightmap(z0, x0, z0 + size, x0 + size);
        if (!data.complete()) return findSpawnBlockPos();
        int scale = session.profile().worldScale();
        int maxY = WorldHeightProfile.surfaceCapYForScale(scale);
        var water = session.riverOverlay();
        var selected = findNearby(
                choice,
                data,
                scale,
                maxY,
                (x, z) -> water == null || water.waterY(x, z) == Integer.MIN_VALUE && water.waterfallAt(x, z) == null);
        if (selected != null) return selected;
        LOG.warn("Selected spawn {} has no safe terrain within 32 blocks; using normal search", choice);
        return findSpawnBlockPos();
    }

    @FunctionalInterface
    interface DryColumn {
        boolean test(int x, int z);
    }

    static BlockPos findNearby(
            SpawnChoice choice, LocalTerrainProvider.HeightmapData data, int scale, int maxY, DryColumn dry) {
        int offset = 48;
        BlockPos safeFallback = null;
        for (int radius = 0; radius <= 32; radius += 4) {
            for (int dz = -radius; dz <= radius; dz += 4)
                for (int dx = -radius; dx <= radius; dx += 4) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int x = choice.x() + dx, z = choice.z() + dz, ix = offset + dx, iz = offset + dz;
                    int y = data.surfaceYAt(ix, iz, scale);
                    short biome = data.biomeIds[iz][ix];
                    if (y < 65 || y > maxY || biome == 5 || !dry.test(x, z)) continue;
                    int low = y, high = y;
                    for (int oz = -2; oz <= 2; oz++)
                        for (int ox = -2; ox <= 2; ox++) {
                            int height = data.surfaceYAt(ix + ox, iz + oz, scale);
                            low = Math.min(low, height);
                            high = Math.max(high, height);
                        }
                    if (high - low > 3) continue;
                    var candidate = new BlockPos(x, y + 1, z);
                    if (safeFallback == null) safeFallback = candidate;
                    if (choice.matches(
                            y,
                            data.surfaceYAt(ix - 16, iz, scale),
                            data.surfaceYAt(ix + 16, iz, scale),
                            data.surfaceYAt(ix, iz - 16, scale),
                            data.surfaceYAt(ix, iz + 16, scale),
                            biome)) {
                        return candidate;
                    }
                }
        }
        return safeFallback;
    }

    private static int heightmapY(int blockX, int blockZ, WorldSession session, int scale) {
        LocalTerrainProvider.HeightmapData data = null;
        Exception lookupFailure = null;

        try {
            data = LocalTerrainProvider.getInstance().fetchHeightmap(blockZ, blockX, blockZ + 1, blockX + 1);
        } catch (Exception e) {
            lookupFailure = e;
            LOG.error("SpawnSelector: failed to fetch heightmap at ({}, {})", new Object[] {blockX, blockZ, e});
        }

        WorldSession.requireActive(session);
        if (lookupFailure != null) {
            WorldSession.requireActive(session);
            return 64;
        }

        int spawnY;
        try {
            spawnY = clampSpawnY(data.surfaceYAt(0, 0, scale));
        } catch (Exception e) {
            LOG.error("SpawnSelector: failed to fetch heightmap at ({}, {})", new Object[] {blockX, blockZ, e});
            WorldSession.requireActive(session);
            return 64;
        }

        WorldSession.requireActive(session);
        return spawnY;
    }

    static int clampSpawnY(int surfaceY) {
        return Math.max(surfaceY, 64);
    }

    private static BlockPos fallbackToOrigin(WorldSession session, int scale) {
        LOG.warn("SpawnSelector: falling back to origin (0, ?, 0)");
        int blockY = heightmapY(0, 0, session, scale);
        WorldSession.requireActive(session);
        return new BlockPos(0, blockY, 0);
    }

    private static BlockPos findNearestLandPixel(
            FloatTensor coarse, int H, int W, int ci0, int cj0, WorldSession session, int scale) {
        int centerRow = H / 2;
        int centerCol = W / 2;
        int maxDist = Math.max(H, W);

        for (int dist = 0; dist <= maxDist; dist++) {
            for (int dr = -dist; dr <= dist; dr++) {
                for (int dc = -dist; dc <= dist; dc++) {
                    if (Math.abs(dr) == dist || Math.abs(dc) == dist) {
                        int row = centerRow + dr;
                        int col = centerCol + dc;
                        if (row >= 1
                                && row < H - 1
                                && col >= 1
                                && col < W - 1
                                && isFullyOnLand(coarse, row, col, H, W)) {
                            int ci = ci0 + row;
                            int cj = cj0 + col;
                            int blockX = cj * SpawnSelector.CoarseScale.TO_NATIVE * scale;
                            int blockZ = ci * SpawnSelector.CoarseScale.TO_NATIVE * scale;
                            int blockY = heightmapY(blockX, blockZ, session, scale);
                            WorldSession.requireActive(session);
                            return new BlockPos(blockX, blockY, blockZ);
                        }
                    }
                }
            }
        }

        return null;
    }

    private static boolean isFullyOnLand(FloatTensor coarse, int row, int col, int H, int W) {
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (!isLand(coarse, row + dr, col + dc, H, W)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isLand(FloatTensor coarse, int row, int col, int H, int W) {
        int pixelCount = H * W;
        int px = row * W + col;
        float weight = coarse.data[6 * pixelCount + px];
        if (weight <= 1.0E-6F) {
            return false;
        }

        float elevSqrt = coarse.data[px] / weight;
        return elevSqrt > 0.0F;
    }

    private static final class CoarseScale {
        private static final int TO_NATIVE = 32 * WorldPipelineModelConfig.latentCompression();
    }
}

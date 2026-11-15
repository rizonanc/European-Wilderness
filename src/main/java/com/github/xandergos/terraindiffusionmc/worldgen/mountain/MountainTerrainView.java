package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

final class MountainTerrainView {
    private final int tileSize;
    private final long seed;
    private final String fingerprint;
    private final int scale;
    private final int surfaceCapY;
    private final int worldTopYInclusive;
    private final Function<TerrainTileKey, TerrainTile> loader;
    private final Map<TerrainTileKey, TerrainTile> tiles = new HashMap<>();
    private final Map<Long, Integer> firstAir = new HashMap<>();

    MountainTerrainView(
            int tileSize,
            long seed,
            String fingerprint,
            int scale,
            int worldTopYInclusive,
            Function<TerrainTileKey, TerrainTile> loader) {
        TerrainTileKey.ofTile(seed, 0, 0, tileSize, fingerprint);
        this.tileSize = tileSize;
        this.seed = seed;
        this.fingerprint = fingerprint;
        this.scale = scale;
        this.worldTopYInclusive = worldTopYInclusive;
        this.surfaceCapY = WorldHeightProfile.surfaceCapY(worldTopYInclusive);
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    MountainTerrainView.Sample sample(int x, int z) {
        TerrainTile tile = this.tile(x, z);
        if (!tile.hasClimate()) {
            throw new IllegalStateException("Mountain surface requires climate for " + tile.key());
        } else {
            return new MountainTerrainView.Sample(
                    this.firstAir(x, z),
                    tile.climateAt(0, tile.key().localX(x), tile.key().localZ(z)));
        }
    }

    int firstAir(int x, int z) {
        long packed = (long) x << 32 ^ z & 4294967295L;
        return this.firstAir.computeIfAbsent(packed, ignored -> {
            TerrainTile tile = this.tile(x, z);
            short metres = tile.heightAt(tile.key().localX(x), tile.key().localZ(z));
            int raw = MountainHeightField.sampleForWorld(this.seed, x, z, metres, this.scale, this.surfaceCapY)
                    .surfaceY();
            var session = com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession.current();
            return session != null
                            && session.worldSeed() == seed
                            && session.fingerprint().equals(fingerprint)
                            && session.riverOverlay() != null
                    ? session.riverOverlay().carvedHeight(x, z, raw)
                    : raw;
        });
    }

    int firstAirNeighbour(int x, int z, int dx, int dz) {
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            throw new IllegalArgumentException("Expected one cardinal step");
        }

        int nextX = Math.addExact(x, dx);
        int nextZ = Math.addExact(z, dz);
        TerrainTileKey centerKey = TerrainTileKey.forBlock(x, z, this.tileSize, this.seed, this.fingerprint);
        TerrainTileKey nextKey = TerrainTileKey.forBlock(nextX, nextZ, this.tileSize, this.seed, this.fingerprint);
        if (centerKey.equals(nextKey)) {
            return this.firstAir(nextX, nextZ);
        }

        int centerY = this.firstAir(x, z);
        int innerY = this.firstAir(Math.subtractExact(x, dx), Math.subtractExact(z, dz));
        long projected = 2L * centerY - innerY;
        return (int) Math.max(-64L, Math.min(this.worldTopYInclusive, projected));
    }

    static Function<TerrainTileKey, TerrainTile> adaptChecked(MountainTerrainView.CheckedTileLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return key -> {
            try {
                return loader.load(key);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new MountainTerrainView.LookupException(interrupted);
            } catch (RuntimeException runtime) {
                throw runtime;
            } catch (Exception checked) {
                throw new MountainTerrainView.LookupException(checked);
            }
        };
    }

    private TerrainTile tile(int x, int z) {
        TerrainTileKey key = TerrainTileKey.forBlock(x, z, this.tileSize, this.seed, this.fingerprint);
        return this.tiles.computeIfAbsent(key, requested -> {
            TerrainTile loaded = Objects.requireNonNull(
                    this.loader.apply(requested), () -> "Terrain loader returned null for " + requested);
            if (!requested.equals(loaded.key())) {
                throw new IllegalStateException("Terrain loader returned foreign tile for " + requested);
            } else {
                return loaded;
            }
        });
    }

    @FunctionalInterface
    interface CheckedTileLoader {
        TerrainTile load(TerrainTileKey var1) throws Exception;
    }

    static final class LookupException extends RuntimeException {
        LookupException(Exception cause) {
            super(cause);
        }
    }

    record Sample(int firstAir, float temperatureC) {}
}

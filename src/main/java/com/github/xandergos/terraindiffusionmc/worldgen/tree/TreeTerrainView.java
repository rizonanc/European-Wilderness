package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.WorldHeightProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

final class TreeTerrainView {
    private final int tileSize;
    private final long seed;
    private final String fingerprint;
    private final TreeTerrainView.SurfaceHeightResolver heightResolver;
    private final Function<TerrainTileKey, TerrainTile> loader;
    private final Map<TerrainTileKey, TerrainTile> tiles = new HashMap<>();
    private final Map<Long, TreeTerrainView.Sample> samples = new HashMap<>();
    private final Map<Long, Short> heightsMeters = new HashMap<>();
    private final Map<Long, Integer> firstAir = new HashMap<>();
    private final Map<Long, Integer> clearance = new HashMap<>();
    private final Map<Long, Integer> waterLevels = new HashMap<>();

    TreeTerrainView(
            int tileSize, long seed, String fingerprint, int scale, Function<TerrainTileKey, TerrainTile> loader) {
        this(
                tileSize,
                seed,
                fingerprint,
                loader,
                (x, z, metres) -> HeightConverter.convertToMinecraftHeight(metres, scale));
    }

    TreeTerrainView(
            int tileSize,
            long seed,
            String fingerprint,
            Function<TerrainTileKey, TerrainTile> loader,
            IntUnaryOperator heightConverter) {
        this(tileSize, seed, fingerprint, loader, adapt(heightConverter));
    }

    TreeTerrainView(
            int tileSize,
            long seed,
            String fingerprint,
            Function<TerrainTileKey, TerrainTile> loader,
            TreeTerrainView.SurfaceHeightResolver heightResolver) {
        TerrainTileKey.ofTile(seed, 0, 0, tileSize, fingerprint);
        this.tileSize = tileSize;
        this.seed = seed;
        this.fingerprint = fingerprint;
        this.loader = Objects.requireNonNull(loader, "loader");
        this.heightResolver = Objects.requireNonNull(heightResolver, "heightResolver");
    }

    TreeTerrainView(
            int tileSize,
            long seed,
            String fingerprint,
            int scale,
            int worldTopYInclusive,
            Function<TerrainTileKey, TerrainTile> loader) {
        this(
                tileSize,
                seed,
                fingerprint,
                loader,
                (x, z, metres) ->
                        MountainHeightField.sampleForWorld(
                                        seed, x, z, metres, scale, WorldHeightProfile.surfaceCapY(worldTopYInclusive))
                                .surfaceY());
    }

    private static TreeTerrainView.SurfaceHeightResolver adapt(IntUnaryOperator heightConverter) {
        Objects.requireNonNull(heightConverter, "heightConverter");
        return (x, z, metres) -> heightConverter.applyAsInt(metres);
    }

    TreeTerrainView.Sample sample(int x, int z) {
        long column = pack(x, z);
        return this.samples.computeIfAbsent(
                column,
                ignored -> {
                    TerrainTile tile = this.tile(x, z);
                    if (!tile.hasClimate()) {
                        throw new IllegalStateException("Tree generation requires climate for " + tile.key());
                    }

                    int localX = tile.key().localX(x);
                    int localZ = tile.key().localZ(z);
                    short height = this.heightMeters(x, z);
                    return new TreeTerrainView.Sample(
                            height,
                            tile.biomeAt(localX, localZ),
                            tile.climateAt(0, localX, localZ),
                            tile.climateAt(1, localX, localZ),
                            tile.climateAt(2, localX, localZ),
                            tile.climateAt(3, localX, localZ));
                });
    }

    short heightMeters(int x, int z) {
        return this.heightsMeters.computeIfAbsent(
                pack(x, z),
                ignored -> {
                    TerrainTile tile = this.tile(x, z);
                    return tile.heightAt(tile.key().localX(x), tile.key().localZ(z));
                });
    }

    int firstAir(int x, int z) {
        return this.firstAir.computeIfAbsent(
                pack(x, z),
                ignored -> {
                    int raw = this.heightResolver.surfaceY(x, z, this.heightMeters(x, z));
                    var session = com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession.current();
                    return session != null
                                    && session.worldSeed() == seed
                                    && session.fingerprint().equals(fingerprint)
                                    && session.riverOverlay() != null
                            ? session.riverOverlay().carvedHeight(x, z, raw)
                            : raw;
                });
    }

    private com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession.RiverOverlay waterOverlay() {
        var session = com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession.current();
        return session != null
                        && session.isActive()
                        && session.worldSeed() == seed
                        && session.fingerprint().equals(fingerprint)
                ? session.riverOverlay()
                : null;
    }

    int waterY(int x, int z) {
        return waterLevels.computeIfAbsent(
                pack(x, z),
                ignored -> {
                    var overlay = waterOverlay();
                    if (overlay == null) return Integer.MIN_VALUE;
                    int level = overlay.waterY(x, z);
                    var fall = overlay.waterfallAt(x, z);
                    return fall != null && fall.curtain(x, z) ? Math.max(level, fall.upperY()) : level;
                });
    }

    int clearanceFirstAir(int x, int z) {
        return clearance.computeIfAbsent(
                pack(x, z),
                ignored ->
                        com.github.xandergos.terraindiffusionmc.worldgen.hydrology.RiverDetailPlanning
                                .clearanceFirstAir(waterOverlay(), seed, x, z, firstAir(x, z), -1024, 4096));
    }

    static Function<TerrainTileKey, TerrainTile> adaptChecked(TreeTerrainView.CheckedTileLoader loader) {
        Objects.requireNonNull(loader, "loader");
        return key -> {
            try {
                return loader.load(key);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new TreeTerrainView.LookupException(interrupted);
            } catch (RuntimeException runtime) {
                throw runtime;
            } catch (Exception checked) {
                throw new TreeTerrainView.LookupException(checked);
            }
        };
    }

    private TerrainTile tile(int x, int z) {
        TerrainTileKey key = TerrainTileKey.forBlock(x, z, this.tileSize, this.seed, this.fingerprint);
        return this.tiles.computeIfAbsent(
                key,
                requested -> {
                    TerrainTile tile =
                            Objects.requireNonNull(
                                    this.loader.apply(requested),
                                    () -> "Terrain loader returned null for " + requested);
                    if (!requested.equals(tile.key())) {
                        throw new IllegalStateException("Terrain loader returned foreign data for " + requested);
                    } else {
                        return tile;
                    }
                });
    }

    private static long pack(int x, int z) {
        return (long) x << 32 ^ z & 4294967295L;
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

    record Sample(
            short heightMeters,
            short biome,
            float temperatureC,
            float seasonalTemperature,
            float precipitationMm,
            float precipitationVariability) {}

    @FunctionalInterface
    interface SurfaceHeightResolver {
        int surfaceY(int var1, int var2, short var3);
    }
}

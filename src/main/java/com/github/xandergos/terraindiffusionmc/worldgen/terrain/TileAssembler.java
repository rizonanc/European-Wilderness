package com.github.xandergos.terraindiffusionmc.worldgen.terrain;

import java.util.Optional;

public final class TileAssembler {
    public static final int MAX_REGION_SIDE = 4096;

    private TileAssembler() {}

    private static TileAssembler.RegionDimensions validateRegion(int i1, int j1, int i2, int j2, int tileSize) {
        if (tileSize >= 16 && tileSize <= 1024) {
            long height = (long) i2 - i1;
            long width = (long) j2 - j1;
            if (height <= 0L || width <= 0L) {
                throw new IllegalArgumentException(
                        "empty or reversed region (" + i1 + "," + j1 + ")-(" + i2 + "," + j2 + ")");
            } else if (height <= 4096L && width <= 4096L) {
                return new TileAssembler.RegionDimensions((int) width, (int) height);
            } else {
                throw new IllegalArgumentException("region too large: " + width + "x" + height);
            }
        } else {
            throw new IllegalArgumentException("tileSize must be 16..1024, got " + tileSize);
        }
    }

    public static TileAssembler.AssembledRegion assemble(
            int i1,
            int j1,
            int i2,
            int j2,
            int tileSize,
            long seed,
            String fingerprint,
            TileAssembler.TileFunction tiles)
            throws Exception {
        TileAssembler.RegionDimensions dimensions = validateRegion(i1, j1, i2, j2, tileSize);
        int height = dimensions.height();
        int width = dimensions.width();
        int tx0 = Math.floorDiv(j1, tileSize);
        int tx1 = Math.floorDiv(j2 - 1, tileSize);
        int tz0 = Math.floorDiv(i1, tileSize);
        int tz1 = Math.floorDiv(i2 - 1, tileSize);
        TerrainTile[][] grid = new TerrainTile[tz1 - tz0 + 1][tx1 - tx0 + 1];

        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                TerrainTileKey key = TerrainTileKey.ofTile(seed, tx, tz, tileSize, fingerprint);
                TerrainTile tile = tiles.get(key);
                if (tile == null || !tile.key().equals(key)) {
                    throw new IllegalStateException("Tile function returned foreign data for " + key);
                }

                grid[tz - tz0][tx - tx0] = tile;
            }
        }

        short[][] heights = new short[height][width];
        short[][] biomes = new short[height][width];

        for (int row = 0; row < height; row++) {
            int blockZ = i1 + row;
            int tz = Math.floorDiv(blockZ, tileSize);
            int lz = blockZ - tz * tileSize;

            for (int col = 0; col < width; col++) {
                int blockX = j1 + col;
                int tx = Math.floorDiv(blockX, tileSize);
                int lx = blockX - tx * tileSize;
                TerrainTile tile = grid[tz - tz0][tx - tx0];
                heights[row][col] = tile.heightAt(lx, lz);
                biomes[row][col] = tile.biomeAt(lx, lz);
            }
        }

        return new TileAssembler.AssembledRegion(heights, biomes, width, height, true);
    }

    public static TileAssembler.AssembledRegion assembleReady(
            int i1,
            int j1,
            int i2,
            int j2,
            int tileSize,
            long seed,
            String fingerprint,
            TileAssembler.ReadyTileFunction tiles,
            short fallbackHeight,
            short fallbackBiome)
            throws Exception {
        TileAssembler.RegionDimensions dimensions = validateRegion(i1, j1, i2, j2, tileSize);
        int height = dimensions.height();
        int width = dimensions.width();
        int tx0 = Math.floorDiv(j1, tileSize);
        int tx1 = Math.floorDiv(j2 - 1, tileSize);
        int tz0 = Math.floorDiv(i1, tileSize);
        int tz1 = Math.floorDiv(i2 - 1, tileSize);
        TerrainTile[][] grid = new TerrainTile[tz1 - tz0 + 1][tx1 - tx0 + 1];
        boolean complete = true;

        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                TerrainTileKey key = TerrainTileKey.ofTile(seed, tx, tz, tileSize, fingerprint);
                Optional<TerrainTile> ready = tiles.get(key);
                if (ready == null) {
                    throw new IllegalStateException("Ready tile function returned null for " + key);
                }

                if (ready.isPresent() && !ready.get().key().equals(key)) {
                    throw new IllegalStateException("Ready tile function returned foreign data for " + key);
                }

                if (ready.isEmpty()) {
                    complete = false;
                }

                grid[tz - tz0][tx - tx0] = ready.orElse(null);
            }
        }

        short[][] heights = new short[height][width];
        short[][] biomes = new short[height][width];

        for (int row = 0; row < height; row++) {
            int blockZ = i1 + row;
            int tz = Math.floorDiv(blockZ, tileSize);
            int lz = blockZ - tz * tileSize;

            for (int col = 0; col < width; col++) {
                int blockX = j1 + col;
                int tx = Math.floorDiv(blockX, tileSize);
                int lx = blockX - tx * tileSize;
                TerrainTile tile = grid[tz - tz0][tx - tx0];
                heights[row][col] = tile == null ? fallbackHeight : tile.heightAt(lx, lz);
                biomes[row][col] = tile == null ? fallbackBiome : tile.biomeAt(lx, lz);
            }
        }

        return new TileAssembler.AssembledRegion(heights, biomes, width, height, complete);
    }

    public record AssembledRegion(short[][] heights, short[][] biomes, int width, int height, boolean complete) {}

    @FunctionalInterface
    public interface ReadyTileFunction {
        Optional<TerrainTile> get(TerrainTileKey var1) throws Exception;
    }

    private record RegionDimensions(int width, int height) {}

    @FunctionalInterface
    public interface TileFunction {
        TerrainTile get(TerrainTileKey var1) throws Exception;
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WaterGenerationOptions;
import java.util.*;

/** Sparse whole watersheds owned by the location of their lake minimum or sea mouth. */
final class WorldWaterUnit {
    final WorldWaterGrid.Cell cell;
    final int[] indices;
    final short[] levels;
    final short[] currents;
    final byte[] lakes;
    final List<Waterfall> falls;
    final int revision;
    final int depthVariation;

    WorldWaterUnit(
            WorldWaterGrid grid,
            WorldWaterGrid.Cell cell,
            int[] indices,
            short[] levels,
            byte[] lakes,
            List<Waterfall> falls) {
        this(grid, cell, indices, levels, lakes, falls, 1);
    }

    WorldWaterUnit(
            WorldWaterGrid grid,
            WorldWaterGrid.Cell cell,
            int[] indices,
            short[] levels,
            byte[] lakes,
            List<Waterfall> falls,
            int revision) {
        this(grid, cell, indices, levels, lakes, falls, revision, null);
    }

    WorldWaterUnit(
            WorldWaterGrid grid,
            WorldWaterGrid.Cell cell,
            int[] indices,
            short[] levels,
            byte[] lakes,
            List<Waterfall> falls,
            int revision,
            short[] currents) {
        this(grid, cell, indices, levels, lakes, falls, revision, currents, 100);
    }

    WorldWaterUnit(
            WorldWaterGrid grid,
            WorldWaterGrid.Cell cell,
            int[] indices,
            short[] levels,
            byte[] lakes,
            List<Waterfall> falls,
            int revision,
            short[] currents,
            int depthVariation) {
        if (revision < 1 || revision > 6) throw new IllegalArgumentException("Unsupported water algorithm");
        this.revision = revision;
        if (depthVariation < 0 || depthVariation > 200)
            throw new IllegalArgumentException("Invalid river depth variation");
        this.depthVariation = depthVariation;
        this.cell = cell;
        this.indices = indices.clone();
        this.levels = levels.clone();
        this.lakes = lakes.clone();
        this.falls = List.copyOf(falls);
        this.currents = currents == null ? null : currents.clone();
        if (indices.length != levels.length
                || indices.length != lakes.length
                || indices.length > grid.unitSide() * grid.unitSide()
                || falls.size() > 1
                || currents != null && currents.length != indices.length)
            throw new IllegalArgumentException("Invalid sparse water plan");
        int previous = -1, side = grid.unitSide();
        for (int i = 0; i < indices.length; i++) {
            int p = indices[i], x = p % side, z = p / side;
            if (p <= previous
                    || p >= side * side
                    || x < PreparedWaterArea.COLLAR
                    || z < PreparedWaterArea.COLLAR
                    || x >= side - PreparedWaterArea.COLLAR
                    || z >= side - PreparedWaterArea.COLLAR
                    || levels[i] < (revision < 2 ? 63 : 62)
                    || levels[i] >= 4096
                    || lakes[i] < 0
                    || lakes[i] > 1
                    || currents != null && currents[i] != 0 && levels[i] <= 62)
                throw new IllegalArgumentException("Invalid sparse water column");
            previous = p;
        }
        for (var f : falls)
            if (!f.inside(grid.x(cell), grid.z(cell), grid.core()))
                throw new IllegalArgumentException("Waterfall crosses its owning core");
    }

    static WorldWaterUnit plan(long seed, WorldWaterGrid grid, WorldWaterGrid.Cell cell, int[][] raw) {
        return plan(seed, grid, cell, raw, 1);
    }

    static WorldWaterUnit plan(long seed, WorldWaterGrid grid, WorldWaterGrid.Cell cell, int[][] raw, int revision) {
        return plan(seed, grid, cell, raw, revision, WaterGenerationOptions.DEFAULT);
    }

    static WorldWaterUnit plan(
            long seed,
            WorldWaterGrid grid,
            WorldWaterGrid.Cell cell,
            int[][] raw,
            int revision,
            WaterGenerationOptions options) {
        return plan(seed, grid, cell, raw, revision, options, 2);
    }

    static WorldWaterUnit plan(
            long seed,
            WorldWaterGrid grid,
            WorldWaterGrid.Cell cell,
            int[][] raw,
            int revision,
            WaterGenerationOptions options,
            int scale) {
        return plan(seed, grid, cell, raw, revision, revision >= 5, options, scale);
    }

    private static WorldWaterUnit plan(
            long seed,
            WorldWaterGrid grid,
            WorldWaterGrid.Cell cell,
            int[][] raw,
            int revision,
            boolean currentsEnabled,
            WaterGenerationOptions options,
            int scale) {
        int side = grid.unitSide(), x = grid.x(cell) - grid.unitHalo(), z = grid.z(cell) - grid.unitHalo();
        var data = PreparedWaterArea.calculate(
                seed,
                x,
                z,
                raw,
                new PreparedWaterArea.Owner(grid.x(cell), grid.z(cell), grid.core()),
                revision,
                currentsEnabled,
                revision >= 5,
                options,
                scale);
        int count = 0;
        for (int w : data.water()) if (w != PreparedWaterArea.NONE) count++;
        int[] index = new int[count];
        short[] levels = new short[count];
        byte[] lakes = new byte[count];
        int at = 0;
        short[] currents = data.currents() == null ? null : new short[count];
        for (int p = 0; p < data.water().length; p++)
            if (data.water()[p] != PreparedWaterArea.NONE) {
                index[at] = p;
                levels[at] = (short) data.water()[p];
                lakes[at] = (byte) (data.lake()[(p / side / 2) * (side / 2) + p % side / 2] ? 1 : 0);
                if (currents != null) currents[at] = data.currents()[p];
                at++;
            }
        return new WorldWaterUnit(
                grid, cell, index, levels, lakes, data.falls(), revision, currents, options.depthVariation());
    }

    WorldWaterUnit restoreCurrents(long seed, WorldWaterGrid grid, int[][] raw) {
        var rebuilt = plan(seed, grid, cell, raw, revision, true, WaterGenerationOptions.DEFAULT, 2);
        short[] matched = currents == null ? new short[indices.length] : currents.clone();
        int at = 0;
        for (int i = 0; i < indices.length && at < rebuilt.indices.length; i++) {
            while (at < rebuilt.indices.length && rebuilt.indices[at] < indices[i]) at++;
            if (matched[i] == 0
                    && at < rebuilt.indices.length
                    && rebuilt.indices[at] == indices[i]
                    && rebuilt.levels[at] == levels[i]
                    && rebuilt.lakes[at] == lakes[i]) matched[i] = rebuilt.currents[at];
        }
        return new WorldWaterUnit(grid, cell, indices, levels, lakes, falls, revision, matched, depthVariation);
    }

    boolean hasRiverIn(WorldWaterGrid grid, WorldWaterTile tile) {
        int side = grid.unitSide(), ux = grid.x(cell) - grid.unitHalo(), uz = grid.z(cell) - grid.unitHalo();
        for (int i = 0; i < indices.length; i++) {
            if (levels[i] <= 62 || currents != null && lakes[i] == 0) continue;
            int x = ux + indices[i] % side, z = uz + indices[i] / side;
            if (tile.contains(x, z) && tile.water[tile.index(x, z)] == levels[i]) return true;
        }
        return false;
    }

    void merge(WorldWaterGrid grid, int originX, int originZ, int side, int[] water, boolean[] lake) {
        merge(grid, originX, originZ, side, water, lake, null);
    }

    void merge(WorldWaterGrid grid, int originX, int originZ, int side, int[] water, boolean[] lake, short[] flow) {
        merge(grid, originX, originZ, side, water, lake, flow, null);
    }

    void merge(
            WorldWaterGrid grid,
            int originX,
            int originZ,
            int side,
            int[] water,
            boolean[] lake,
            short[] flow,
            byte[] depth) {
        int unitSide = grid.unitSide(), ux = grid.x(cell) - grid.unitHalo(), uz = grid.z(cell) - grid.unitHalo();
        for (int i = 0; i < indices.length; i++) {
            int x = ux + indices[i] % unitSide - originX, z = uz + indices[i] / unitSide - originZ;
            if (x < 0 || z < 0 || x >= side || z >= side) continue;
            int p = z * side + x, w = levels[i];
            if (w > water[p]) {
                water[p] = w;
                lake[p] = lakes[i] != 0;
                if (flow != null) flow[p] = currents == null ? 0 : currents[i];
                if (depth != null) depth[p] = (byte) depthVariation;
            } else if (w == water[p]) {
                boolean wasLake = lake[p], isLake = lakes[i] != 0;
                lake[p] |= isLake;
                if (flow != null) {
                    if (isLake && !wasLake
                            || isLake == wasLake && flow[p] == 0 && currents != null && currents[i] != 0) {
                        flow[p] = currents == null ? 0 : currents[i];
                        if (depth != null) depth[p] = (byte) depthVariation;
                    }
                }
            }
        }
    }
}

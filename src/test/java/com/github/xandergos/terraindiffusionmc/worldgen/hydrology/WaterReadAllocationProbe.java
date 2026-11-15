package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;

/** The same probe runs against 2.6.2 and 2.6.3 named classes, without touching a world. */
public final class WaterReadAllocationProbe {
    private static long read(WorldWaterController water, int count) {
        long checksum = 0;
        for (int i = 0; i < count; i++) {
            int x = -64 + (i & 127), z = -64 + ((i >>> 7) & 63);
            checksum += water.waterY(x, z);
            checksum += water.carvedHeight(x, z, 0);
            checksum += water.bedY(x, z, 0);
        }
        return checksum;
    }

    public static void main(String[] args) throws Exception {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var session = WorldSession.activate(GenerationProfile.defaultsFor(2, "probe", "cpu", "cpu"), 7);
        var root = Path.of("unused-allocation-probe-root");
        session.attachRepository(
                new TerrainRepository(
                        new DiskTerrainStore(root.resolve("raw")),
                        key -> {
                            throw new AssertionError("Hot reads cannot load terrain");
                        },
                        256,
                        1048576,
                        1073741824L,
                        session));
        var grid = new WorldWaterGrid(64, 32, 128);
        try (var water = new WorldWaterController(session, root.resolve("water"), 300, grid, 2, 4)) {
            int[] air = new int[4096], levels = new int[4096];
            Arrays.fill(air, 103);
            Arrays.fill(levels, PreparedWaterArea.NONE);
            var tile = new WorldWaterTile(grid, new WorldWaterGrid.Cell(-1, -1), air, levels, List.of());
            var field = WorldWaterController.class.getDeclaredField("last");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var local = (ThreadLocal<WorldWaterTile>) field.get(water);
            local.set(tile);
            var cacheField = WorldWaterController.class.getDeclaredField("tiles");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cache = (WaterPlanCache<WorldWaterGrid.Cell, WorldWaterTile>) cacheField.get(water);
            cache.get(tile.cell, () -> tile);
            var neighbour = new WorldWaterTile(grid, new WorldWaterGrid.Cell(0, -1), air, levels, List.of());
            cache.get(neighbour.cell, () -> neighbour);
            read(water, 100_000);
            int count = 1_000_000;
            long thread = Thread.currentThread().threadId();
            long before = bean.getThreadAllocatedBytes(thread),
                    start = System.nanoTime(),
                    checksum = read(water, count);
            long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(thread) - before;
            System.out.println(
                    "{\"iterations\":"
                            + count
                            + ",\"water_queries\":"
                            + (count * 3)
                            + ",\"elapsed_ns\":"
                            + elapsed
                            + ",\"allocated_bytes\":"
                            + allocated
                            + ",\"checksum\":"
                            + checksum
                            + "}");
        } finally {
            WorldSession.deactivate(session);
        }
    }
}

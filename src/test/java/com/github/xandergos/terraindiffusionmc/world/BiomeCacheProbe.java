package com.github.xandergos.terraindiffusionmc.world;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.*;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;

/** Isolated warm-read cost only; does not measure total Chunky throughput. */
public final class BiomeCacheProbe {
    static volatile long sink;
    public static void main(String[] args) throws Exception {
        var store = new DiskTerrainStore(Path.of(args[0]));
        long seed = 42; String fingerprint = "biome-cost-probe";
        var repo = new TerrainRepository(store, key -> {
            short[] heights = new short[256], biomes = new short[256];
            for (int i = 0; i < biomes.length; i++) biomes[i] = (short)(208 + i % 3);
            return new TerrainTile(key, heights, biomes);
        }, 16, 1048576, 1073741824L);
        for (int i = 0; i < 4; i++) repo.get(TerrainTileKey.ofTile(seed, i, 0, 16, fingerprint));
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        var id = UUID.randomUUID(); var cache = new BiomeColumnCache();
        for (boolean cached : new boolean[]{false,true,false,true}) {
            int count = 2_000_000; long sum = 0; int requests = 0;
            long bytes = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                int qx = ((i >> 10) & 3) * 4 + (i & 3), qz = (i >> 2) & 3;
                int value = cached ? cache.get(id, qx, qz) : BiomeColumnCache.MISSING;
                if (value == BiomeColumnCache.MISSING) {
                    int x = qx * 4, z = qz * 4;
                    var key = TerrainTileKey.forBlock(x,z,16,seed,fingerprint);
                    value = repo.get(key).biomeAt(key.localX(x),key.localZ(z)); requests++;
                    if (cached) cache.put(id,qx,qz,(short)value);
                }
                sum += value;
            }
            long elapsed = System.nanoTime()-start;
            long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes;
            sink=sum;
            System.out.printf(Locale.ROOT,"cached=%s samples=%d repositoryReads=%d ms=%.3f allocatedBytes=%d checksum=%d%n",cached,count,requests,elapsed/1e6,allocated,sum);
        }
    }
}

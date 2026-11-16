package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import java.util.Arrays;
import java.util.UUID;

/** Four complete 16x16 raw windows per generation thread. Never retains a WorldSession. */
final class DensityWindowCache {
    private UUID session;
    private Object source;
    private int next;
    private final int[] xs = new int[4], zs = new int[4];
    private final HeightmapData[] data = new HeightmapData[4];

    private void context(UUID id, Object lookup) {
        if (!id.equals(session) || lookup != source) {
            session = id;
            source = lookup;
            next = 0;
            Arrays.fill(data, null);
        }
    }

    HeightmapData get(UUID id, Object lookup, int x, int z) {
        context(id, lookup);
        for (int i = 0; i < data.length; i++) if (data[i] != null && xs[i] == x && zs[i] == z) return data[i];
        return null;
    }

    void put(UUID id, Object lookup, int x, int z, HeightmapData value) {
        context(id, lookup);
        if (value == null || !value.complete() || value.heightmap == null || value.width != 16 || value.height != 16)
            return;
        for (int i = 0; i < data.length; i++)
            if (data[i] != null && xs[i] == x && zs[i] == z) {
                data[i] = value;
                return;
            }
        xs[next] = x;
        zs[next] = z;
        data[next] = value;
        next = (next + 1) % data.length;
    }

    int size() {
        int count = 0;
        for (var value : data) if (value != null) count++;
        return count;
    }
}

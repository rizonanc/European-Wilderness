package com.github.xandergos.terraindiffusionmc.world;

import java.util.Arrays;
import java.util.UUID;

/** Four chunks of 2D biome IDs per worker. Values never retain a world or tile. */
final class BiomeColumnCache {
    static final int MISSING = 65536;
    private UUID session;
    private int next;
    private final int[] xs = new int[4], zs = new int[4], valid = new int[4];
    private final short[] values = new short[64];

    private void context(UUID id) {
        if (!id.equals(session)) {
            session = id;
            next = 0;
            Arrays.fill(valid, 0);
        }
    }

    int get(UUID id, int quartX, int quartZ) {
        context(id);
        int cx = Math.floorDiv(quartX, 4), cz = Math.floorDiv(quartZ, 4);
        int column = Math.floorMod(quartX, 4) + 4 * Math.floorMod(quartZ, 4);
        for (int slot = 0; slot < 4; slot++) {
            if (xs[slot] == cx && zs[slot] == cz && (valid[slot] & (1 << column)) != 0)
                return values[slot * 16 + column];
        }
        return MISSING;
    }

    void put(UUID id, int quartX, int quartZ, short value) {
        context(id);
        int cx = Math.floorDiv(quartX, 4), cz = Math.floorDiv(quartZ, 4);
        int slot = -1;
        for (int i = 0; i < 4; i++) {
            if (valid[i] != 0 && xs[i] == cx && zs[i] == cz) {slot = i; break;}
        }
        if (slot == -1) {
            slot = next;
            next = (next + 1) % 4;
            xs[slot] = cx;
            zs[slot] = cz;
            valid[slot] = 0;
        }
        int column = Math.floorMod(quartX, 4) + 4 * Math.floorMod(quartZ, 4);
        values[slot * 16 + column] = value;
        valid[slot] |= 1 << column;
    }
}

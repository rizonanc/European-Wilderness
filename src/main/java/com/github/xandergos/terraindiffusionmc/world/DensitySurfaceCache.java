package com.github.xandergos.terraindiffusionmc.world;

import java.util.Arrays;
import java.util.UUID;

/** Four immutable automatic-water surface windows per worker, about 8 KiB.
 * Keys retain UUIDs and a raw lookup only, never a water controller or session.
 */
final class DensitySurfaceCache {
    static final long MISSING = Long.MIN_VALUE;
    private UUID session, water;
    private Object source;
    private int next;
    private final int[] xs = new int[4], zs = new int[4];
    private final boolean[] used = new boolean[4];
    private final long[][] surfaces = new long[4][256];

    private void context(UUID id, UUID waterId, Object lookup) {
        if (id != session || waterId != water || source != lookup) {
            session = id; water = waterId; source = lookup; next = 0;
            Arrays.fill(used, false);
        }
    }

    long get(UUID id, UUID waterId, Object lookup, int x, int z) {
        context(id, waterId, lookup);
        for (int i = 0; i < used.length; i++) {
            if (used[i] && xs[i] == (x >> 4) && zs[i] == (z >> 4))
                return surfaces[i][((z & 15) << 4) | (x & 15)];
        }
        return MISSING;
    }

    void put(UUID id, UUID waterId, Object lookup, int x, int z, int surface) {
        context(id, waterId, lookup);
        int slot = -1;
        for (int i = 0; i < used.length; i++) {
            if (used[i] && xs[i] == (x >> 4) && zs[i] == (z >> 4)) { slot = i; break; }
        }
        if (slot < 0) {
            slot = next; next = (next + 1) % used.length;
            used[slot] = true; xs[slot] = x >> 4; zs[slot] = z >> 4;
            Arrays.fill(surfaces[slot], MISSING);
        }
        surfaces[slot][((z & 15) << 4) | (x & 15)] = surface;
    }
}

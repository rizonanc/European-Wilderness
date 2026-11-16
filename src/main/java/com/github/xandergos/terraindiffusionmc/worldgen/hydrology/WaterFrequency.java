package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

/** Thin whole basins/outlets, preserving every previously rejected basin. */
final class WaterFrequency {
    static boolean keep(long seed, int x, int z, boolean lake) {
        return keep(seed, x, z, lake, 2);
    }

    static boolean keep(long seed, int x, int z, boolean lake, int revision) {
        long v = seed
                ^ ((long) x * 0x9e3779b97f4a7c15L)
                ^ ((long) z * 0xc2b2ae3d27d4eb4fL)
                ^ (lake ? 0x481eb790L : 0x73b954a1L);
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        v ^= v >>> 31;
        if (Long.remainderUnsigned(v, 10) == 0) return false;
        if (revision < 3) return true;
        v ^= 0x6ac198d43eb72951L;
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        v ^= v >>> 31;
        return Long.remainderUnsigned(v, 100) >= 7;
    }
}

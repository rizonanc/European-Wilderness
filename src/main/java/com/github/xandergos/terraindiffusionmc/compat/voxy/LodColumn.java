package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.tree.TreeSpecies;
import java.util.*;

/** Pure 32-square surface and optional forest approximation, without real game chunks. */
public final class LodColumn {
    public enum Kind {
        AIR,
        STONE,
        DIRT,
        GRASS,
        SAND,
        SNOW,
        WATER,
        OAK_LOG,
        BIRCH_LOG,
        SPRUCE_LOG,
        OAK_LEAVES,
        BIRCH_LEAVES,
        SPRUCE_LEAVES
    }

    private static final Kind[] KINDS = Kind.values();
    private final int[] air, water;
    private final short[] biomes;
    private final Map<Integer, byte[]> forest;
    public final int x, z, bottom, top, maxY;
    private boolean forestPending;

    public LodColumn(int x, int z, int bottom, int top, int[] air, int[] water, short[] biomes) {
        this(x, z, bottom, top, air, water, biomes, false);
    }

    static LodColumn owned(int x, int z, int bottom, int top, int[] air, int[] water, short[] biomes) {
        return new LodColumn(x, z, bottom, top, air, water, biomes, true);
    }

    private LodColumn(int x, int z, int bottom, int top, int[] air, int[] water, short[] biomes, boolean owned) {
        this(x, z, bottom, top, air, water, biomes, owned, Map.of(), bottom);
    }

    private LodColumn(
            int x,
            int z,
            int bottom,
            int top,
            int[] air,
            int[] water,
            short[] biomes,
            boolean owned,
            Map<Integer, byte[]> forest,
            int forestTop) {
        if (Math.floorMod(x, 32) != 0
                || Math.floorMod(z, 32) != 0
                || bottom > top
                || air.length != 1024
                || water.length != 1024
                || biomes.length != 1024) throw new IllegalArgumentException("Invalid LOD column");
        this.x = x;
        this.z = z;
        this.bottom = bottom;
        this.top = top;
        this.air = owned ? air : air.clone();
        this.water = owned ? water : water.clone();
        this.biomes = owned ? biomes : biomes.clone();
        this.forest = forest;
        int maximum = Math.max(bottom, forestTop);
        for (int i = 0; i < 1024; i++) maximum = Math.max(maximum, Math.max(air[i] - 1, water[i]));
        maxY = Math.min(top, maximum);
    }

    public short biome(int x, int z) {
        return biomes[z * 32 + x];
    }

    LodColumn awaitingForest() {
        forestPending = true;
        return this;
    }

    public boolean forestPending() {
        return forestPending;
    }

    public int visibleBottomCube() {
        int low = top;
        for (int value : air) low = Math.min(low, value - 1);
        return Math.max(Math.floorDiv(bottom, 32), Math.floorDiv(low - 32, 32));
    }

    public int firstAir(int x, int z) {
        return air[z * 32 + x];
    }

    public Kind kind(int x, int y, int z) {
        if (x < 0 || z < 0 || x >= 32 || z >= 32) throw new IllegalArgumentException("Column escaped");
        if (y < bottom || y > top) return Kind.AIR;
        var plants = forestSection(x / 16, Math.floorDiv(y, 16), z / 16);
        if (plants != null) {
            int value = Byte.toUnsignedInt(plants[Math.floorMod(y, 16) * 256 + (z & 15) * 16 + (x & 15)]);
            if (value != 0) return KINDS[value - 1];
        }
        return terrainKind(x, y, z);
    }

    Kind terrainKind(int x, int y, int z) {
        if (y < bottom || y > top) return Kind.AIR;
        int p = z * 32 + x, bed = air[p] - 1;
        if (y > bed) return y <= water[p] ? Kind.WATER : Kind.AIR;
        if (y < bed - 3) return Kind.STONE;
        if (water[p] >= air[p] || biomes[p] == 5 || biomes[p] == 18 || biomes[p] == 26) return Kind.SAND;
        if (biomes[p] == 35 || biomes[p] == 33 || air[p] > 500) return Kind.STONE;
        if (y < bed) return Kind.DIRT;
        return biomes[p] == 3 || biomes[p] == 16 || biomes[p] == 32 ? Kind.SNOW : Kind.GRASS;
    }

    byte[] forestSection(int bx, int sy, int bz) {
        return forest.get(sy * 4 + bz * 2 + bx);
    }

    public static final class ForestBuilder {
        private final LodColumn base;
        private final Map<Integer, byte[]> sections = new HashMap<>();
        private int maximum, lastKey = Integer.MIN_VALUE;
        private byte[] lastSection;
        private boolean built;

        public ForestBuilder(LodColumn base) {
            this.base = base;
            maximum = base.bottom;
        }

        public void set(int worldX, int y, int worldZ, TreeSpecies species, boolean log) {
            if (built) throw new IllegalStateException("Forest already frozen");
            int x = worldX - base.x, z = worldZ - base.z;
            if (x < 0 || z < 0 || x >= 32 || z >= 32 || y < base.bottom || y > base.top) return;
            if (base.terrainKind(x, y, z) != Kind.AIR) return;
            Kind kind =
                    switch (species) {
                        case OAK -> log ? Kind.OAK_LOG : Kind.OAK_LEAVES;
                        case BIRCH -> log ? Kind.BIRCH_LOG : Kind.BIRCH_LEAVES;
                        default -> log ? Kind.SPRUCE_LOG : Kind.SPRUCE_LEAVES;
                    };
            int key = Math.floorDiv(y, 16) * 4 + (z / 16) * 2 + x / 16,
                    index = Math.floorMod(y, 16) * 256 + (z & 15) * 16 + (x & 15);
            if (key != lastKey) {
                lastSection = sections.computeIfAbsent(key, k -> new byte[4096]);
                lastKey = key;
            }
            var section = lastSection;
            int previous = Byte.toUnsignedInt(section[index]);
            if (!log && previous >= Kind.OAK_LOG.ordinal() + 1 && previous <= Kind.SPRUCE_LOG.ordinal() + 1) return;
            section[index] = (byte) (kind.ordinal() + 1);
            maximum = Math.max(maximum, y);
        }

        public LodColumn build() {
            if (built) throw new IllegalStateException("Forest already frozen");
            built = true;
            return new LodColumn(
                    base.x,
                    base.z,
                    base.bottom,
                    base.top,
                    base.air,
                    base.water,
                    base.biomes,
                    true,
                    Map.copyOf(sections),
                    maximum);
        }
    }
}

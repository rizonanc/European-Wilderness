package com.github.xandergos.terraindiffusionmc.compat.voxy;

import it.unimi.dsi.fastutil.longs.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Smooth, bounded preview-only residual field around exact terrain.
 * Grid vertices are 16 blocks apart. Render workers only read immutable values. */
public final class CoarseLodCorrections {
    public static final int STEP = 16, RADIUS = 128;

    public record Anchor(int x, int z, float residual) {}

    private record Sum(double error, double weight, double nearest) {}

    private static final class Tile {
        final AtomicReferenceArray<Sum> cells = new AtomicReferenceArray<>(1024);
        int count;
    }

    // Hash tables are immutable after publication. Only atomic tile cells change.
    // Primitive keys avoid allocating four Long objects for every rendered column.
    private volatile Long2ObjectMap<Tile> tiles = Long2ObjectMaps.emptyMap();
    private final LongLinkedOpenHashSet order = new LongLinkedOpenHashSet();
    private volatile int size;
    private final int limit;

    public CoarseLodCorrections(int limit) {
        if (limit < 1) throw new IllegalArgumentException();
        this.limit = limit;
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public int size() {
        return size;
    }

    private static long tileKey(int x, int z) {
        return key(Math.floorDiv(x, 32), Math.floorDiv(z, 32));
    }

    private static int index(int x, int z) {
        return Math.floorMod(z, 32) * 32 + Math.floorMod(x, 32);
    }

    private Tile createTile(long key) {
        Tile tile = tiles.get(key);
        if (tile != null) return tile;
        tile = new Tile();
        var copy = new Long2ObjectOpenHashMap<>(tiles);
        copy.put(key, tile);
        tiles = copy;
        return tile;
    }

    private void removeTile(long tileKey) {
        var tile = tiles.get(tileKey);
        if (tile == null) return;
        int x = (int) (tileKey >> 32) * 32, z = (int) tileKey * 32;
        for (int dz = 0; dz < 32; dz++) for (int dx = 0; dx < 32; dx++) order.remove(key(x + dx, z + dz));
        var copy = new Long2ObjectOpenHashMap<>(tiles);
        copy.remove(tileKey);
        tiles = copy;
    }

    public synchronized void add(List<Anchor> anchors) {
        if (anchors.isEmpty()) return;
        for (var anchor : anchors)
            if (!Float.isFinite(anchor.residual)) throw new IllegalArgumentException("Non-finite coarse correction");
        for (var anchor : anchors) {
            for (int gz = Math.floorDiv(anchor.z - RADIUS, STEP); gz <= Math.floorDiv(anchor.z + RADIUS, STEP); gz++)
                for (int gx = Math.floorDiv(anchor.x - RADIUS, STEP);
                        gx <= Math.floorDiv(anchor.x + RADIUS, STEP);
                        gx++) {
                    double dx = gx * (double) STEP - anchor.x,
                            dz = gz * (double) STEP - anchor.z,
                            d2 = dx * dx + dz * dz;
                    if (d2 >= (double) RADIUS * RADIUS) continue;
                    double distance = Math.sqrt(d2), t = 1 - distance / RADIUS, weight = t * t / (d2 + 16);
                    var tile = createTile(tileKey(gx, gz));
                    int index = index(gx, gz);
                    var old = tile.cells.get(index);
                    tile.cells.set(
                            index,
                            old == null
                                    ? new Sum(anchor.residual * weight, weight, distance)
                                    : new Sum(
                                            old.error + anchor.residual * weight,
                                            old.weight + weight,
                                            Math.min(old.nearest, distance)));
                    if (old == null) tile.count++;
                    order.addAndMoveToLast(key(gx, gz));
                }
        }
        while (order.size() > limit) {
            long victim = order.removeFirstLong();
            int x = (int) (victim >> 32), z = (int) victim;
            long tk = tileKey(x, z);
            var tile = tiles.get(tk);
            if (tile != null && tile.cells.getAndSet(index(x, z), null) != null && --tile.count == 0) removeTile(tk);
        }
        while (tiles.size() > 2048) {
            long first = order.firstLong();
            removeTile(tileKey((int) (first >> 32), (int) first));
        }
        size = order.size();
    }

    public double adjustment(int x, int z) {
        int gx = Math.floorDiv(x, STEP), gz = Math.floorDiv(z, STEP);
        double tx = Math.floorMod(x, STEP) / (double) STEP, tz = Math.floorMod(z, STEP) / (double) STEP;
        return (1 - tz) * ((1 - tx) * at(gx, gz) + tx * at(gx + 1, gz))
                + tz * ((1 - tx) * at(gx, gz + 1) + tx * at(gx + 1, gz + 1));
    }

    private double at(int x, int z) {
        var tile = tiles.get(tileKey(x, z));
        if (tile == null) return 0;
        var sum = tile.cells.get(index(x, z));
        if (sum == null) return 0;
        double t = Math.max(0, 1 - sum.nearest / RADIUS), fade = t * t * (3 - 2 * t);
        return sum.error / sum.weight * fade;
    }
}

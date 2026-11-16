package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import java.util.*;

/** At most three complete water cores: current, then a small speed-dependent lead. */
public final class WaterPrefetchPolicy {
    private WaterPrefetchPolicy() {}

    public static List<TilePrefetchPolicy.TileCoord> candidates(int x, int z, int dx, int dz, int side) {
        if (side < 16 || side > 1024) throw new IllegalArgumentException("Invalid core size");
        var current = new TilePrefetchPolicy.TileCoord(Math.floorDiv(x, side), Math.floorDiv(z, side));
        LinkedHashSet<TilePrefetchPolicy.TileCoord> result = new LinkedHashSet<>();
        result.add(current);
        double speed = Math.hypot(dx, dz);
        if (speed >= 2 && speed <= 512) {
            double lead = Math.min(side * 2.0, Math.max(side, speed * 8));
            for (double distance : new double[] {side * .5, side, lead}) {
                if (result.size() == 3) break;
                long wx = (long) Math.floor(x + dx / speed * distance),
                        wz = (long) Math.floor(z + dz / speed * distance);
                if (Math.abs(wx) < 29_990_000 && Math.abs(wz) < 29_990_000)
                    result.add(new TilePrefetchPolicy.TileCoord(
                            Math.floorDiv((int) wx, side), Math.floorDiv((int) wz, side)));
            }
        } else if (speed < 2) {
            int lx = Math.floorMod(x, side), lz = Math.floorMod(z, side);
            List<int[]> edges = new ArrayList<>(
                    List.of(new int[] {lx, -1, 0}, new int[] {side - 1 - lx, 1, 0}, new int[] {lz, 0, -1}, new int[] {
                        side - 1 - lz, 0, 1
                    }));
            edges.sort(Comparator.comparingInt(a -> a[0]));
            for (int i = 0; i < 2; i++)
                result.add(
                        new TilePrefetchPolicy.TileCoord(current.x() + edges.get(i)[1], current.z() + edges.get(i)[2]));
        }
        return List.copyOf(result);
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class TilePrefetchPolicy {
    private TilePrefetchPolicy() {}

    public static List<TilePrefetchPolicy.TileCoord> candidates(
            int blockX, int blockZ, int deltaX, int deltaZ, int tileSize) {
        if (tileSize >= 16 && tileSize <= 1024) {
            double distance = Math.hypot(deltaX, deltaZ);
            if (!(distance < 2.0) && !(distance > 512.0)) {
                double ux = deltaX / distance;
                double uz = deltaZ / distance;
                TilePrefetchPolicy.TileCoord current =
                        new TilePrefetchPolicy.TileCoord(
                                Math.floorDiv(blockX, tileSize), Math.floorDiv(blockZ, tileSize));
                LinkedHashSet<TilePrefetchPolicy.TileCoord> selected = new LinkedHashSet<>();
                int steps = distance < 8.0 ? 2 : 3;

                for (int step = 1; step <= steps; step++) {
                    add(selected, current, blockX + ux * tileSize * step, blockZ + uz * tileSize * step, tileSize);
                }

                if (distance >= 8.0) {
                    double forwardX = blockX + ux * tileSize * 1.5;
                    double forwardZ = blockZ + uz * tileSize * 1.5;
                    add(selected, current, forwardX - uz * tileSize * 0.5, forwardZ + ux * tileSize * 0.5, tileSize);
                    add(selected, current, forwardX + uz * tileSize * 0.5, forwardZ - ux * tileSize * 0.5, tileSize);
                }

                ArrayList<TilePrefetchPolicy.TileCoord> result = new ArrayList<>(4);

                for (TilePrefetchPolicy.TileCoord candidate : selected) {
                    if (result.size() == 4) {
                        break;
                    }

                    result.add(candidate);
                }

                return List.copyOf(result);
            } else {
                return List.of();
            }
        } else {
            throw new IllegalArgumentException("tileSize out of range: " + tileSize);
        }
    }

    private static void add(
            LinkedHashSet<TilePrefetchPolicy.TileCoord> selected,
            TilePrefetchPolicy.TileCoord current,
            double x,
            double z,
            int tileSize) {
        if (!(x < -2.1474836E9F) && !(x > 2.147483647E9) && !(z < -2.1474836E9F) && !(z > 2.147483647E9)) {
            TilePrefetchPolicy.TileCoord candidate =
                    new TilePrefetchPolicy.TileCoord(
                            Math.floorDiv((int) Math.floor(x), tileSize), Math.floorDiv((int) Math.floor(z), tileSize));
            if (!candidate.equals(current)) {
                selected.add(candidate);
            }
        }
    }

    public static final class LoadGate {
        private boolean mayStart = true;
        private int healthyStreak;

        public void acceptTick(long elapsedMillis) {
            if (elapsedMillis >= 50L) {
                this.mayStart = false;
                this.healthyStreak = 0;
            } else if (!this.mayStart && elapsedMillis <= 35L) {
                if (++this.healthyStreak >= 3) {
                    this.mayStart = true;
                }
            } else if (!this.mayStart) {
                this.healthyStreak = 0;
            }
        }

        public boolean mayStart() {
            return this.mayStart;
        }
    }

    public record TileCoord(int x, int z) {}
}

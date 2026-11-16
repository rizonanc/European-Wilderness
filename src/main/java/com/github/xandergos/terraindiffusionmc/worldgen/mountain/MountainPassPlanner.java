package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

public final class MountainPassPlanner {
    private static final int MARGIN = 24;
    private static final int STEP = 8;
    private static final int MAX_GRADE_PER_BLOCK = 2;
    private static final int SADDLE_FLANK_DISTANCE = 48;
    private static final int SADDLE_PROMINENCE = 8;
    private static final int MAX_PATH_RAISE_BLOCKS = 3;
    private static final int MAX_RAISED_COLUMNS = 12;
    private static final long PASS_SALT = 5779335839369016114L;

    private MountainPassPlanner() {}

    public static Optional<MountainPassPlanner.PassPlan> plan(
            long seed, int tileX, int tileZ, int tileSize, int worldScale, int worldTopYInclusive, TerrainTile tile) {
        Objects.requireNonNull(tile, "tile");
        TerrainTileKey key = tile.key();
        if (key.seed() == seed && key.tileX() == tileX && key.tileZ() == tileZ && key.tileSize() == tileSize) {
            if (worldScale >= 1 && worldScale <= 6) {
                int surfaceCapY = WorldHeightProfile.surfaceCapY(worldTopYInclusive);
                if (tileSize < 128) {
                    return Optional.empty();
                }

                int startX;
                int startZ;
                try {
                    startX = Math.multiplyExact(tileX, tileSize);
                    startZ = Math.multiplyExact(tileZ, tileSize);
                    Math.addExact(startX, tileSize - 1);
                    Math.addExact(startZ, tileSize - 1);
                } catch (ArithmeticException outOfWorldCoordinates) {
                    return Optional.empty();
                }

                int selected = 0;

                for (int z = tileSize / 4; z <= tileSize * 3 / 4; z += Math.max(1, tileSize / 4)) {
                    for (int x = tileSize / 4; x <= tileSize * 3 / 4; x += Math.max(1, tileSize / 4)) {
                        short metres = tile.heightAt(x, z);
                        if (metres >= 4500) {
                            selected++;
                        }
                    }
                }

                if (selected < 3) {
                    return Optional.empty();
                }

                int gridSide = (tileSize - 1 - 48) / 8 + 1;
                if (gridSide < 10) {
                    return Optional.empty();
                }

                MountainPassPlanner.Grid grid = new MountainPassPlanner.Grid(
                        seed, startX, startZ, tileSize, worldScale, surfaceCapY, tile, gridSide);
                MountainPassPlanner.Candidate eastWest = search(grid, true);
                MountainPassPlanner.Candidate northSouth = search(grid, false);
                MountainPassPlanner.Candidate best = null;
                if (eastWest != null && hasNaturalSaddle(grid, eastWest)) {
                    best = eastWest;
                }

                if (northSouth != null
                        && hasNaturalSaddle(grid, northSouth)
                        && (best == null || northSouth.cost < best.cost)) {
                    best = northSouth;
                }

                if (best == null) {
                    return Optional.empty();
                } else {
                    List<MountainPassPlanner.RoutePoint> route = densify(grid, best);
                    if (route.size() < tileSize / 2 || route.size() > tileSize * 3) {
                        return Optional.empty();
                    } else {
                        return walkableRouteFor(route).isEmpty()
                                ? Optional.empty()
                                : Optional.of(new MountainPassPlanner.PassPlan(
                                        passId(seed, tileX, tileZ), tileX, tileZ, route));
                    }
                }
            } else {
                throw new IllegalArgumentException("Unsupported world scale: " + worldScale);
            }
        } else {
            throw new IllegalArgumentException("Terrain tile does not match pass identity");
        }
    }

    private static Optional<List<MountainPassPlanner.WalkablePoint>> walkableRouteFor(
            List<MountainPassPlanner.RoutePoint> route) {
        if (route.isEmpty()) {
            return Optional.empty();
        }

        int[] adjusted = new int[route.size()];

        for (int i = 0; i < adjusted.length; i++) {
            adjusted[i] = route.get(i).groundY();
        }

        for (int i = 1; i < adjusted.length; i++) {
            adjusted[i] = Math.max(adjusted[i], adjusted[i - 1] - 1);
        }

        for (int i = adjusted.length - 2; i >= 0; i--) {
            adjusted[i] = Math.max(adjusted[i], adjusted[i + 1] - 1);
        }

        List<MountainPassPlanner.WalkablePoint> result = new ArrayList<>(route.size());
        int raised = 0;

        for (int i = 0; i < route.size(); i++) {
            MountainPassPlanner.RoutePoint natural = route.get(i);
            int correction = adjusted[i] - natural.groundY();
            if (correction < 0 || correction > 3) {
                return Optional.empty();
            }

            if (correction > 0) {
                if (++raised > 12) {
                    return Optional.empty();
                }
            }

            result.add(new MountainPassPlanner.WalkablePoint(natural.x(), natural.z(), natural.groundY(), adjusted[i]));
        }

        return Optional.of(List.copyOf(result));
    }

    private static MountainPassPlanner.Candidate search(MountainPassPlanner.Grid grid, boolean eastWest) {
        int n = grid.side;
        int total = n * n;
        long[] cost = new long[total];
        int[] previous = new int[total];
        Arrays.fill(cost, Long.MAX_VALUE);
        Arrays.fill(previous, -1);
        PriorityQueue<MountainPassPlanner.Node> frontier =
                new PriorityQueue<>(Comparator.comparingLong(MountainPassPlanner.Node::cost)
                        .thenComparingInt(MountainPassPlanner.Node::index));
        int lower = n / 4;
        int upper = n - 1 - lower;

        for (int orth = lower; orth <= upper; orth++) {
            int source = eastWest ? orth * n : orth;
            cost[source] = 0L;
            frontier.add(new MountainPassPlanner.Node(source, 0L));
        }

        int[] deltaX = new int[] {1, 0, -1, 0};
        int[] deltaZ = new int[] {0, 1, 0, -1};

        while (!frontier.isEmpty()) {
            MountainPassPlanner.Node current = frontier.remove();
            if (current.cost == cost[current.index]) {
                int gx = current.index % n;
                int gz = current.index / n;
                boolean atGoal = eastWest
                        ? gx == n - 1 && gz >= lower && gz <= upper
                        : gz == n - 1 && gx >= lower && gx <= upper;
                if (atGoal) {
                    List<Integer> reverse = new ArrayList<>();

                    for (int index = current.index; index >= 0; index = previous[index]) {
                        reverse.add(index);
                    }

                    Collections.reverse(reverse);
                    return new MountainPassPlanner.Candidate(eastWest, current.cost, reverse);
                }

                for (int d = 0; d < 4; d++) {
                    int nx = gx + deltaX[d];
                    int nz = gz + deltaZ[d];
                    int northSouthCoordinate = eastWest ? nz : nx;
                    if (nx >= 0
                            && nz >= 0
                            && nx < n
                            && nz < n
                            && northSouthCoordinate >= lower
                            && northSouthCoordinate <= upper) {
                        int neighbour = nz * n + nx;
                        long edge = grid.edgeCost(gx, gz, nx, nz);
                        if (edge >= 0L) {
                            long candidate = current.cost + edge;
                            if (candidate < cost[neighbour]) {
                                cost[neighbour] = candidate;
                                previous[neighbour] = current.index;
                                frontier.add(new MountainPassPlanner.Node(neighbour, candidate));
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    private static boolean hasNaturalSaddle(MountainPassPlanner.Grid grid, MountainPassPlanner.Candidate candidate) {
        int middle = grid.side / 2;
        int bestNode = -1;
        int bestDistance = Integer.MAX_VALUE;

        for (int index : candidate.nodes) {
            int gx = index % grid.side;
            int gz = index / grid.side;
            int crossing = candidate.eastWest ? gx : gz;
            int orthogonal = candidate.eastWest ? gz : gx;
            int distance = Math.abs(crossing - middle) * grid.side + Math.abs(orthogonal - middle);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestNode = index;
            }
        }

        if (bestNode >= 0 && bestDistance < 4 * grid.side) {
            int x = grid.localX(bestNode % grid.side);
            int z = grid.localZ(bestNode / grid.side);
            int centerY = grid.heightAt(x, z);
            int leftX = candidate.eastWest ? x : x - 48;
            int leftZ = candidate.eastWest ? z - 48 : z;
            int rightX = candidate.eastWest ? x : x + 48;
            int rightZ = candidate.eastWest ? z + 48 : z;
            return grid.inside(leftX, leftZ)
                    && grid.inside(rightX, rightZ)
                    && grid.heightAt(leftX, leftZ) >= centerY + 8
                    && grid.heightAt(rightX, rightZ) >= centerY + 8;
        } else {
            return false;
        }
    }

    private static List<MountainPassPlanner.RoutePoint> densify(
            MountainPassPlanner.Grid grid, MountainPassPlanner.Candidate candidate) {
        List<MountainPassPlanner.RoutePoint> route = new ArrayList<>();
        int priorX = -1;
        int priorZ = -1;

        for (int index : candidate.nodes) {
            int x = grid.localX(index % grid.side);
            int z = grid.localZ(index / grid.side);
            if (priorX < 0) {
                route.add(new MountainPassPlanner.RoutePoint(grid.startX + x, grid.startZ + z, grid.heightAt(x, z)));
            } else {
                int dx = Integer.compare(x, priorX);
                int dz = Integer.compare(z, priorZ);

                for (int step = 1; step <= 8; step++) {
                    int px = priorX + step * dx;
                    int pz = priorZ + step * dz;
                    route.add(new MountainPassPlanner.RoutePoint(
                            grid.startX + px, grid.startZ + pz, grid.heightAt(px, pz)));
                }
            }

            priorX = x;
            priorZ = z;
        }

        return route;
    }

    private static long passId(long seed, int tileX, int tileZ) {
        long value = seed ^ 5779335839369016114L ^ tileX * -7046029254386353131L ^ tileZ * -3335678366873096957L;
        value = (value ^ value >>> 30) * -4658895280553007687L;
        value = (value ^ value >>> 27) * -7723592293110705685L;
        return value ^ value >>> 31;
    }

    private record Candidate(boolean eastWest, long cost, List<Integer> nodes) {}

    private static final class Grid {
        private final long seed;
        private final int startX;
        private final int startZ;
        private final int size;
        private final int scale;
        private final int cap;
        private final TerrainTile tile;
        private final int side;
        private final int[] gridHeights;

        private Grid(long seed, int startX, int startZ, int size, int scale, int cap, TerrainTile tile, int side) {
            this.seed = seed;
            this.startX = startX;
            this.startZ = startZ;
            this.size = size;
            this.scale = scale;
            this.cap = cap;
            this.tile = tile;
            this.side = side;
            this.gridHeights = new int[side * side];

            for (int gz = 0; gz < side; gz++) {
                for (int gx = 0; gx < side; gx++) {
                    this.gridHeights[gz * side + gx] = this.heightAt(this.localX(gx), this.localZ(gz));
                }
            }
        }

        private int localX(int gx) {
            return 24 + gx * 8;
        }

        private int localZ(int gz) {
            return 24 + gz * 8;
        }

        private boolean inside(int x, int z) {
            return x >= 24 && z >= 24 && x < this.size - 24 && z < this.size - 24;
        }

        private int heightAt(int localX, int localZ) {
            return MountainHeightField.sampleForWorld(
                            this.seed,
                            this.startX + localX,
                            this.startZ + localZ,
                            this.tile.heightAt(localX, localZ),
                            this.scale,
                            this.cap)
                    .surfaceY();
        }

        private long edgeCost(int gx, int gz, int nx, int nz) {
            int dx = Integer.compare(nx, gx);
            int dz = Integer.compare(nz, gz);
            int localX = this.localX(gx);
            int localZ = this.localZ(gz);
            int previousY = this.gridHeights[gz * this.side + gx];
            int destinationY = this.gridHeights[nz * this.side + nx];
            long cost = 8L + Math.max(0, destinationY) / 128L;

            for (int step = 1; step <= 8; step++) {
                int y = step == 8 ? destinationY : this.heightAt(localX + dx * step, localZ + dz * step);
                int rise = Math.abs(y - previousY);
                if (rise > 2) {
                    return -1L;
                }

                cost += 3L * rise;
                previousY = y;
            }

            return cost;
        }
    }

    private record Node(int index, long cost) {}

    public record PassPlan(long id, int tileX, int tileZ, List<MountainPassPlanner.RoutePoint> route) {
        public PassPlan {
            route = List.copyOf(route);
            if (route.isEmpty()) {
                throw new IllegalArgumentException("A pass must contain route points");
            }
        }

        public List<MountainPassPlanner.WalkablePoint> walkableRoute() {
            return MountainPassPlanner.walkableRouteFor(this.route).orElse(List.of());
        }
    }

    public record RoutePoint(int x, int z, int groundY) {}

    public record WalkablePoint(int x, int z, int naturalFirstAir, int plannedFirstAir) {
        public int raiseBlocks() {
            return this.plannedFirstAir - this.naturalFirstAir;
        }
    }
}

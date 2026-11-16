package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class CrossTileRiverTracer {
    private final WatershedPlan plan;
    private final SeamContract seam;
    private final CrossTileRiverTracer.ProfileIndex profiles;

    public CrossTileRiverTracer(WatershedPlan plan, SeamContract seam) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.seam = Objects.requireNonNull(seam, "seam");
        if (seam.plan() != plan) {
            throw new IllegalArgumentException("seam was frozen over a different plan");
        }

        this.profiles = seam.profiles();
    }

    public List<CrossTileRiverTracer.WorldCell> traceDownstream(long outletId, int maxCells) {
        BasinOutlet outlet = this.seam.find(outletId);
        int width = this.plan.coarseWidth();
        int ox = this.plan.localXOfWorld(outlet.worldX());
        int oz = this.plan.localZOfWorld(outlet.worldZ());
        int bestCode = this.profiles.headOf(oz * width + ox);
        return this.walkPath(bestCode % width, bestCode / width, maxCells);
    }

    public List<CrossTileRiverTracer.WorldCell> traceFromWorld(int worldX, int worldZ, int maxCells) {
        return this.walkPath(this.plan.localXOfWorld(worldX), this.plan.localZOfWorld(worldZ), maxCells);
    }

    public static int[] waterAlong(List<CrossTileRiverTracer.WorldCell> path, int upstreamWaterY) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }

        int[] grounds = new int[path.size()];

        for (int i = 0; i < grounds.length; i++) {
            grounds[i] = path.get(i).filledY();
        }

        int[] water = RiverRasterizer.waterProfile(grounds, upstreamWaterY, 63);
        BasinOutlet.verifyProfile(water, 63);
        return water;
    }

    public static int[] bedAlong(List<CrossTileRiverTracer.WorldCell> path, int[] water, int depthBlocks) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path must not be empty");
        }

        int[] grounds = new int[path.size()];

        for (int i = 0; i < grounds.length; i++) {
            grounds[i] = path.get(i).filledY();
        }

        return RiverRasterizer.carveBed(grounds, water, depthBlocks);
    }

    public void verifyCompleteDrainage(Set<Long> contractedOutletIds) {
        Objects.requireNonNull(contractedOutletIds, "contractedOutletIds");
        int width = this.plan.coarseWidth();
        int height = this.plan.coarseHeight();
        int count = Math.multiplyExact(width, height);
        int[] next = new int[count];
        int[] incoming = new int[count];

        for (int code = 0; code < count; code++) {
            int x = code % width;
            int z = code / width;
            int dir = this.plan.direction(x, z);
            if (dir == -1) {
                next[code] = -1;
            } else {
                if (dir < 0 || dir >= DrainagePlanner.DX.length) {
                    throw new IllegalStateException("invalid flow direction at (" + x + "," + z + ")");
                }

                int nx = x + DrainagePlanner.DX[dir];
                int nz = z + DrainagePlanner.DZ[dir];
                if (nx < 0 || nz < 0 || nx >= width || nz >= height) {
                    throw new IllegalStateException("broken edge at (" + x + "," + z + ")");
                }

                int downstream = nz * width + nx;
                next[code] = downstream;
                incoming[downstream]++;
            }
        }

        ArrayDeque<Integer> ready = new ArrayDeque<>();

        for (int code = 0; code < count; code++) {
            if (incoming[code] == 0) {
                ready.addLast(code);
            }
        }

        int[] topological = new int[count];
        int visited = 0;

        while (!ready.isEmpty()) {
            int code = ready.removeFirst();
            topological[visited++] = code;
            int downstream = next[code];
            if (downstream >= 0 && --incoming[downstream] == 0) {
                ready.addLast(downstream);
            }
        }

        if (visited != count) {
            throw new IllegalStateException("flow graph contains a cycle");
        }

        boolean[] covered = new boolean[count];

        for (int index = count - 1; index >= 0; index--) {
            int code = topological[index];
            int downstream = next[code];
            if (downstream < 0) {
                int x = code % width;
                int z = code / width;
                BasinOutlet outlet = this.plan.outletAt(x, z);
                covered[code] = outlet.terminatesAtSea() || contractedOutletIds.contains(outlet.canonId());
            } else {
                covered[code] = covered[downstream];
            }
        }

        for (int code = 0; code < count; code++) {
            if (!covered[code] && this.plan.isChannelCell(code % width, code / width)) {
                int x = code % width;
                int z = code / width;
                throw new IllegalStateException(
                        "channel at ("
                                + x
                                + ","
                                + z
                                + ") drains to an unvalidated outlet or dry boundary: resolve it as a contained natural lake or publish a shared contract before publication");
            }
        }
    }

    private List<CrossTileRiverTracer.WorldCell> walkPath(int startX, int startZ, int maxCells) {
        if (maxCells < 1) {
            throw new IllegalArgumentException("maxCells must be >= 1, got " + maxCells);
        }

        int width = this.plan.coarseWidth();
        int height = this.plan.coarseHeight();
        int cap = Math.min(maxCells, width * height);
        List<CrossTileRiverTracer.WorldCell> path = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        int cx = startX;
        int cz = startZ;

        for (int step = 0; step < cap; step++) {
            long code = (long) cz * width + cx;
            if (!visited.add(code)) {
                throw new IllegalStateException("cycle at (" + cx + "," + cz + ")");
            }

            path.add(new CrossTileRiverTracer.WorldCell(
                    this.plan.worldXOf(cx),
                    this.plan.worldZOf(cz),
                    this.plan.filledHeight(cx, cz),
                    this.plan.accumulation(cx, cz)));
            int dir = this.plan.direction(cx, cz);
            if (dir == -1) {
                this.plan.outletAt(cx, cz);
                return List.copyOf(path);
            }

            cx += DrainagePlanner.DX[dir];
            cz += DrainagePlanner.DZ[dir];
            if (cx < 0 || cz < 0 || cx >= width || cz >= height) {
                throw new IllegalStateException("broken edge at (" + cx + "," + cz + ")");
            }
        }

        throw new IllegalStateException("river path exceeds W*H steps from (" + startX + "," + startZ + ")");
    }

    static CrossTileRiverTracer.ProfileIndex index(WatershedPlan plan) {
        int width = plan.coarseWidth();
        int height = plan.coarseHeight();
        int count = Math.multiplyExact(width, height);
        int[] next = new int[count];
        int[] incoming = new int[count];
        int[] longest = new int[count];
        int[] head = new int[count];
        int[] water = new int[count];
        int[] required = new int[count];
        int[] topological = new int[count];

        for (int code = 0; code < count; code++) {
            head[code] = code;
            int x = code % width;
            int z = code / width;
            int dir = plan.direction(x, z);
            if (dir == -1) {
                next[code] = -1;
            } else {
                if (dir < 0 || dir >= DrainagePlanner.DX.length) {
                    throw new IllegalStateException("invalid flow direction at " + code);
                }

                int nx = x + DrainagePlanner.DX[dir];
                int nz = z + DrainagePlanner.DZ[dir];
                if (nx < 0 || nz < 0 || nx >= width || nz >= height) {
                    throw new IllegalStateException("broken edge at " + code);
                }

                int downstream = nz * width + nx;
                next[code] = downstream;
                incoming[downstream]++;
            }
        }

        ArrayDeque<Integer> ready = new ArrayDeque<>();

        for (int code = 0; code < count; code++) {
            if (incoming[code] == 0) {
                ready.addLast(code);
            }
        }

        int visited = 0;

        while (!ready.isEmpty()) {
            int code = ready.removeFirst();
            topological[visited++] = code;
            int x = code % width;
            int z = code / width;
            int minimum = 63;
            long accumulation = plan.accumulation(x, z);
            if (accumulation >= 9L) {
                minimum = Math.max(
                        minimum,
                        Math.subtractExact(
                                Math.addExact(
                                        plan.filledHeight(x, z), DrainagePlanner.channelDepthBlocks(accumulation)),
                                6));
            }

            required[code] = Math.max(required[code], minimum);
            int downstream = next[code];
            if (downstream >= 0) {
                required[downstream] = Math.max(required[downstream], required[code] - 2);
                int candidate = longest[code] + 1;
                if (candidate > longest[downstream]
                        || candidate == longest[downstream] && head[code] < head[downstream]) {
                    longest[downstream] = candidate;
                    head[downstream] = head[code];
                }

                if (--incoming[downstream] == 0) {
                    ready.addLast(downstream);
                }
            }
        }

        if (visited != count) {
            throw new IllegalStateException("flow graph contains a cycle");
        }

        for (BasinOutlet outlet : plan.outlets()) {
            int terminal = plan.localZOfWorld(outlet.worldZ()) * width + plan.localXOfWorld(outlet.worldX());
            int length = longest[terminal] + 1;
            if (length > 2048) {
                throw new IllegalStateException("profile exceeds rasterizer limit: " + length);
            }
        }

        for (int index = count - 1; index >= 0; index--) {
            int code = topological[index];
            int downstream = next[code];
            int x = code % width;
            int z = code / width;
            if (downstream < 0) {
                BasinOutlet outlet = plan.outletAt(x, z);
                if (outlet.waterY() < required[code]) {
                    throw new IllegalStateException("tributary cannot reach frozen outlet "
                            + outlet.canonId()
                            + " within the six-block carve limit");
                }

                water[code] = outlet.waterY();
            } else {
                int downstreamWater = water[downstream];
                int highest = Math.addExact(downstreamWater, 2);
                int preferred = Math.max(downstreamWater, Math.min(plan.filledHeight(x, z), highest));
                int assigned = Math.max(preferred, required[code]);
                if (assigned > highest) {
                    throw new IllegalStateException(
                            "tributary cannot join downstream cell " + downstream + " within the two-block water step");
                }

                water[code] = assigned;
            }

            validateChannelColumn(plan, x, z, water[code]);
        }

        return new CrossTileRiverTracer.ProfileIndex(head, water);
    }

    private static void validateChannelColumn(WatershedPlan plan, int x, int z, int waterY) {
        long accumulation = plan.accumulation(x, z);
        if (accumulation >= 9L) {
            int ground = plan.filledHeight(x, z);
            int bed = Math.min(
                    Math.subtractExact(ground, 1),
                    Math.subtractExact(waterY, DrainagePlanner.channelDepthBlocks(accumulation)));
            if ((long) ground - bed > 6L) {
                throw new IllegalStateException("tributary incision exceeds six blocks at (" + x + "," + z + ")");
            }

            if ((long) waterY - bed > 4L) {
                throw new IllegalStateException("water column exceeds four blocks at (" + x + "," + z + ")");
            }
        }
    }

    static final class ProfileIndex {
        private final int[] head;
        private final int[] water;

        private ProfileIndex(int[] head, int[] water) {
            this.head = head;
            this.water = water;
        }

        int headOf(int cellCode) {
            return this.head[cellCode];
        }

        int waterAt(int cellCode) {
            return this.water[cellCode];
        }
    }

    public record WorldCell(int worldX, int worldZ, int filledY, long accumulation) {}
}

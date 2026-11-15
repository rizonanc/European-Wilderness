package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WatershedPlan {
    public static final int GENERATOR_VERSION = 8;
    private static final long BASIN_SALT = -7046029254386353131L;
    private final long seed;
    private final int generatorVersion;
    private final int originX;
    private final int originZ;
    private final int width;
    private final int height;
    private final int[][] base;
    private final DrainagePlanner.DrainagePlan inner;
    private final List<BasinOutlet> outlets;
    private final Map<Long, BasinOutlet> byLocal;

    private WatershedPlan(
            long seed,
            int originX,
            int originZ,
            int[][] base,
            DrainagePlanner.DrainagePlan inner,
            List<BasinOutlet> outlets,
            Map<Long, BasinOutlet> byLocal) {
        this.seed = seed;
        this.generatorVersion = 8;
        this.originX = originX;
        this.originZ = originZ;
        this.height = base.length;
        this.width = base[0].length;
        this.base = base;
        this.inner = inner;
        this.outlets = outlets;
        this.byLocal = byLocal;
    }

    public static WatershedPlan planExtent(
            long seed, int coarseOriginX, int coarseOriginZ, int coarseW, int coarseH, int[][] coarseBase) {
        if (coarseW >= 1 && coarseW <= 2048 && coarseH >= 1 && coarseH <= 2048) {
            if (coarseBase != null && coarseBase.length == coarseH) {
                for (int z = 0; z < coarseH; z++) {
                    if (coarseBase[z] == null || coarseBase[z].length != coarseW) {
                        throw new IllegalArgumentException(
                                "coarseBase row " + z + " must have exactly " + coarseW + " columns");
                    }
                }

                Math.addExact(coarseOriginX, coarseW - 1);
                Math.addExact(coarseOriginZ, coarseH - 1);
                DrainagePlanner.DrainagePlan inner =
                        DrainagePlanner.planCoarse(seed, coarseOriginX, coarseOriginZ, coarseBase);
                int[][] baseCopy = new int[coarseH][coarseW];

                for (int z = 0; z < coarseH; z++) {
                    baseCopy[z] = (int[]) coarseBase[z].clone();
                }

                List<BasinOutlet> outlets = new ArrayList<>();
                Map<Long, BasinOutlet> byLocal = new LinkedHashMap<>();

                for (int z = 0; z < coarseH; z++) {
                    for (int x = 0; x < coarseW; x++) {
                        boolean border = x == 0 || z == 0 || x == coarseW - 1 || z == coarseH - 1;
                        int dir = inner.direction(x, z);
                        if (dir == -1) {
                            if (!border) {
                                throw new IllegalStateException(
                                        "interior outlet without a contract at (" + x + "," + z + ")");
                            }

                            int worldX = Math.addExact(coarseOriginX, Math.multiplyExact(x, 2));
                            int worldZ = Math.addExact(coarseOriginZ, Math.multiplyExact(z, 2));
                            BasinOutlet outlet = BasinOutlet.create(
                                    seed, worldX, worldZ, inner.filledHeight(x, z), inner.accumulation(x, z), 8);
                            outlets.add(outlet);
                            byLocal.put(code(x, z, coarseW), outlet);
                        }
                    }
                }

                if (outlets.isEmpty()) {
                    throw new IllegalStateException("extent has no outlet: hanging edge without a contract");
                }

                WatershedPlan built = new WatershedPlan(
                        seed,
                        coarseOriginX,
                        coarseOriginZ,
                        baseCopy,
                        inner,
                        Collections.unmodifiableList(outlets),
                        byLocal);
                verifyLongestProfiles(built);
                return built;
            } else {
                throw new IllegalArgumentException("coarseBase must have exactly " + coarseH + " rows");
            }
        } else {
            throw new IllegalArgumentException("extent must be 1..2048, got " + coarseW + "x" + coarseH);
        }
    }

    private static void verifyLongestProfiles(WatershedPlan plan) {
        int count = Math.multiplyExact(plan.width, plan.height);
        int[] incoming = new int[count];
        int[] downstream = new int[count];
        int[] longest = new int[count];
        int[] head = new int[count];

        for (int code = 0; code < count; code++) {
            head[code] = code;
            int x = code % plan.width;
            int z = code / plan.width;
            int direction = plan.inner.direction(x, z);
            if (direction == -1) {
                downstream[code] = -1;
            } else {
                if (direction < 0 || direction >= DrainagePlanner.DX.length) {
                    throw new IllegalStateException("invalid flow direction at (" + x + "," + z + ")");
                }

                int nx = x + DrainagePlanner.DX[direction];
                int nz = z + DrainagePlanner.DZ[direction];
                if (nx < 0 || nx >= plan.width || nz < 0 || nz >= plan.height) {
                    throw new IllegalStateException("flow leaves extent without an outlet at (" + x + "," + z + ")");
                }

                int next = nz * plan.width + nx;
                downstream[code] = next;
                incoming[next]++;
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
            visited++;
            int next = downstream[code];
            if (next >= 0) {
                int candidate = longest[code] + 1;
                if (candidate > longest[next] || candidate == longest[next] && head[code] < head[next]) {
                    longest[next] = candidate;
                    head[next] = head[code];
                }

                if (--incoming[next] == 0) {
                    ready.addLast(next);
                }
            }
        }

        if (visited != count) {
            throw new IllegalStateException("flow graph contains a cycle");
        }

        for (BasinOutlet outlet : plan.outlets) {
            int ox = plan.localXOfWorld(outlet.worldX());
            int oz = plan.localZOfWorld(outlet.worldZ());
            int terminal = oz * plan.width + ox;
            int length = longest[terminal] + 1;
            if (length > 2048) {
                throw new IllegalArgumentException("basin profile exceeds rasterizer limit: " + length);
            }

            int[] grounds = new int[length];
            int code = head[terminal];

            for (int i = 0; i < length; i++) {
                grounds[i] = plan.inner.filledHeight(code % plan.width, code / plan.width);
                if (i + 1 < length) {
                    code = downstream[code];
                }
            }

            if (code != terminal) {
                throw new IllegalStateException("longest profile misses frozen outlet " + outlet.canonId());
            }

            int[] water = RiverRasterizer.waterProfile(grounds, grounds[0], 63);
            water[length - 1] = outlet.waterY();
            BasinOutlet.verifyProfile(water, 63);
        }
    }

    public static long basinIdOf(long seed, int worldX, int worldZ, int generatorVersion) {
        long hash = seed ^ -7046029254386353131L;
        hash = mix(hash + worldX);
        hash = mix(hash + worldZ);
        return mix(hash + generatorVersion);
    }

    private static long mix(long value) {
        long mixed = (value ^ value >>> 30) * -4658895280553007687L;
        mixed = (mixed ^ mixed >>> 27) * -7723592293110705685L;
        return mixed ^ mixed >>> 31;
    }

    private static long code(int x, int z, int width) {
        return (long) z * width + x;
    }

    public long seed() {
        return this.seed;
    }

    public int generatorVersion() {
        return this.generatorVersion;
    }

    public int coarseOriginX() {
        return this.originX;
    }

    public int coarseOriginZ() {
        return this.originZ;
    }

    public int coarseWidth() {
        return this.width;
    }

    public int coarseHeight() {
        return this.height;
    }

    public int[][] coarseBase() {
        int[][] copy = new int[this.height][this.width];

        for (int z = 0; z < this.height; z++) {
            copy[z] = (int[]) this.base[z].clone();
        }

        return copy;
    }

    public int filledHeight(int localX, int localZ) {
        return this.inner.filledHeight(localX, localZ);
    }

    public DrainagePlanner.CellKind kind(int localX, int localZ) {
        return this.inner.kind(localX, localZ);
    }

    public int direction(int localX, int localZ) {
        return this.inner.direction(localX, localZ);
    }

    public long accumulation(int localX, int localZ) {
        return this.inner.accumulation(localX, localZ);
    }

    public boolean isChannelCell(int localX, int localZ) {
        this.checkCell(localX, localZ);
        return this.inner.accumulation(localX, localZ) >= 9L;
    }

    public List<BasinOutlet> outlets() {
        return this.outlets;
    }

    public BasinOutlet outletAt(int localX, int localZ) {
        this.checkCell(localX, localZ);
        if (this.inner.direction(localX, localZ) != -1) {
            throw new IllegalArgumentException(
                    "cell (" + localX + "," + localZ + ") is not an outlet: no frozen contract");
        } else {
            BasinOutlet outlet = this.byLocal.get(code(localX, localZ, this.width));
            if (outlet == null) {
                throw new IllegalStateException("hanging edge without a contract at (" + localX + "," + localZ + ")");
            } else {
                return outlet;
            }
        }
    }

    public BasinOutlet outletAtWorld(int worldX, int worldZ) {
        return this.outletAt(this.localXOfWorld(worldX), this.localZOfWorld(worldZ));
    }

    public boolean containsWorld(int worldX, int worldZ) {
        long lx = Math.floorDiv((long) worldX - this.originX, 2);
        long lz = Math.floorDiv((long) worldZ - this.originZ, 2);
        return lx >= 0L && lx < this.width && lz >= 0L && lz < this.height;
    }

    public int localXOfWorld(int worldX) {
        int local = Math.floorDiv(Math.subtractExact(worldX, this.originX), 2);
        if (local >= 0 && local < this.width) {
            return local;
        } else {
            throw new IllegalArgumentException("worldX " + worldX + " outside the planned extent");
        }
    }

    public int localZOfWorld(int worldZ) {
        int local = Math.floorDiv(Math.subtractExact(worldZ, this.originZ), 2);
        if (local >= 0 && local < this.height) {
            return local;
        } else {
            throw new IllegalArgumentException("worldZ " + worldZ + " outside the planned extent");
        }
    }

    public int worldXOf(int localX) {
        if (localX >= 0 && localX < this.width) {
            return Math.addExact(this.originX, Math.multiplyExact(localX, 2));
        } else {
            throw new IllegalArgumentException("localX out of range: " + localX);
        }
    }

    public int worldZOf(int localZ) {
        if (localZ >= 0 && localZ < this.height) {
            return Math.addExact(this.originZ, Math.multiplyExact(localZ, 2));
        } else {
            throw new IllegalArgumentException("localZ out of range: " + localZ);
        }
    }

    private void checkCell(int x, int z) {
        if (x < 0 || x >= this.width || z < 0 || z >= this.height) {
            throw new IllegalArgumentException("cell (" + x + "," + z + ") outside " + this.width + "x" + this.height);
        }
    }
}

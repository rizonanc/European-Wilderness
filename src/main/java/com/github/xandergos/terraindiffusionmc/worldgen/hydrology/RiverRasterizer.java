package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

public final class RiverRasterizer {
    public static final int RAPID_STEP_BLOCKS = 2;
    public static final int SEA_LEVEL_BLOCKS = 63;
    public static final int BANK_MIN_WIDTH_BLOCKS = 1;
    public static final int BANK_MAX_WIDTH_BLOCKS = 3;
    public static final int MAX_GRID_DIMENSION = 2048;
    public static final int NO_WATER = Integer.MIN_VALUE;
    private static final int BANK_NOISE_WAVELENGTH = 12;
    private static final long BANK_SALT = 6616326155794342593L;
    private static final long X_MIX = -7046029254386353131L;
    private static final long Z_MIX = -3335678366873096957L;

    private RiverRasterizer() {}

    public static int bankWidthBlocks(long accumulation) {
        if (accumulation < 9L) {
            return 0;
        }

        long ratio = accumulation / 9L;
        int doublings = 63 - Long.numberOfLeadingZeros(ratio);
        return Math.min(3, 1 + doublings);
    }

    public static RiverRasterizer.BankMaterial bankMaterial(long seed, int worldX, int worldZ, int slope) {
        if (slope < 0) {
            throw new IllegalArgumentException("slope must be >= 0, got " + slope);
        } else if (slope >= 3) {
            return RiverRasterizer.BankMaterial.STONE;
        } else {
            double patch = patchNoise01(seed, worldX, worldZ);
            if (slope == 2) {
                return patch < 0.6 ? RiverRasterizer.BankMaterial.GRAVEL : RiverRasterizer.BankMaterial.DIRT;
            } else if (patch < 0.45) {
                return RiverRasterizer.BankMaterial.GRASS_BLOCK;
            } else if (patch < 0.65) {
                return RiverRasterizer.BankMaterial.DIRT;
            } else if (patch < 0.8) {
                return RiverRasterizer.BankMaterial.SAND;
            } else {
                return patch < 0.9 ? RiverRasterizer.BankMaterial.CLAY : RiverRasterizer.BankMaterial.GRAVEL;
            }
        }
    }

    public static String bankBlockId(long seed, int worldX, int worldZ, int slope) {
        return bankMaterial(seed, worldX, worldZ, slope).blockId();
    }

    public static int[] waterProfile(int[] groundDownstream, int upstreamWaterY, int seaLevelY) {
        if (groundDownstream == null) {
            throw new IllegalArgumentException("groundDownstream must not be null");
        }

        if (groundDownstream.length != 0 && groundDownstream.length <= 2048) {
            int[] water = new int[groundDownstream.length];
            water[0] = Math.max(Math.min(upstreamWaterY, groundDownstream[0]), seaLevelY);

            for (int i = 1; i < groundDownstream.length; i++) {
                int prev = water[i - 1];
                int level;
                if (groundDownstream[i] >= prev) {
                    level = prev;
                } else {
                    level = Math.max(groundDownstream[i], Math.subtractExact(prev, 2));
                }

                water[i] = Math.max(level, seaLevelY);
            }

            return water;
        } else {
            throw new IllegalArgumentException(
                    "groundDownstream length must be 1..2048, got " + groundDownstream.length);
        }
    }

    public static int[] carveBed(int[] groundThalweg, int[] water, int depthBlocks) {
        if (groundThalweg != null && water != null) {
            if (groundThalweg.length == 0 || groundThalweg.length > 2048) {
                throw new IllegalArgumentException("groundThalweg length must be 1..2048, got " + groundThalweg.length);
            }

            if (water.length != groundThalweg.length) {
                throw new IllegalArgumentException(
                        "water length " + water.length + " must match ground length " + groundThalweg.length);
            }

            if (depthBlocks >= 1 && depthBlocks <= 4) {
                int[] bed = new int[groundThalweg.length];

                for (int i = 0; i < bed.length; i++) {
                    bed[i] =
                            Math.min(
                                    Math.subtractExact(groundThalweg[i], 1), Math.subtractExact(water[i], depthBlocks));
                }

                return bed;
            } else {
                throw new IllegalArgumentException("depthBlocks must be 1..4, got " + depthBlocks);
            }
        } else {
            throw new IllegalArgumentException("groundThalweg and water must not be null");
        }
    }

    public static boolean shouldRasterize(boolean sourceIsFallback) {
        return !sourceIsFallback;
    }

    public static boolean shouldRasterizeOrThrow(boolean sourceIsFallback) {
        if (sourceIsFallback) {
            throw new IllegalStateException(
                    "PREPARED_ONLY fallback must never be carved: return a clean fallback layer without caching it as authority");
        } else {
            return true;
        }
    }

    public static boolean isAboveSeaLevel(int y, int seaLevelY) {
        return y > seaLevelY;
    }

    public static boolean rimHoldsWater(int waterY, int rimMinY) {
        try {
            return rimMinY >= Math.addExact(waterY, 1);
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    public static RiverRasterizer.RasterResult rasterize(
            long seed,
            int originX,
            int originZ,
            int[][] sourceGround,
            int[] pathX,
            int[] pathZ,
            int[] water,
            long accumulation,
            int seaLevelY) {
        int width = validateGrid(sourceGround);
        int height = sourceGround.length;
        Math.addExact(originX, width - 1);
        Math.addExact(originZ, height - 1);
        if (pathX != null && pathZ != null && water != null) {
            if (pathX.length == 0 || pathX.length != pathZ.length) {
                throw new IllegalArgumentException("path must be non-empty with matching X/Z lengths");
            }

            if (water.length != pathX.length) {
                throw new IllegalArgumentException(
                        "water length " + water.length + " must match path length " + pathX.length);
            }

            if (pathX.length > (long) width * height) {
                throw new IllegalArgumentException("path longer than the grid");
            }

            if (accumulation < 9L) {
                throw new IllegalArgumentException("accumulation " + accumulation + " is below the channel threshold");
            }

            for (int i = 0; i < pathX.length; i++) {
                if (pathX[i] < 0 || pathX[i] >= width || pathZ[i] < 0 || pathZ[i] >= height) {
                    throw new IllegalArgumentException(
                            "path cell " + i + " (" + pathX[i] + "," + pathZ[i] + ") outside " + width + "x" + height);
                }
            }

            for (int w : water) {
                if (w < seaLevelY) {
                    throw new IllegalArgumentException("water " + w + " below sea level " + seaLevelY);
                }
            }

            for (int i = 1; i < water.length; i++) {
                if (water[i] > water[i - 1]) {
                    throw new IllegalArgumentException(
                            "water must not rise at step " + i + ": " + water[i - 1] + " -> " + water[i]);
                }

                if (Math.subtractExact(water[i - 1], water[i]) > 2) {
                    throw new IllegalArgumentException(
                            "water drop exceeds the fixed rapid step at "
                                    + i
                                    + ": "
                                    + water[i - 1]
                                    + " -> "
                                    + water[i]);
                }
            }

            int channelWidth = DrainagePlanner.channelWidthBlocks(accumulation);
            int depth = DrainagePlanner.channelDepthBlocks(accumulation);
            int bankWidth = bankWidthBlocks(accumulation);
            int halfWidth = channelWidth / 2;
            int reach = halfWidth + bankWidth;
            int[] thalweg = new int[pathX.length];

            for (int i = 0; i < thalweg.length; i++) {
                thalweg[i] = sourceGround[pathZ[i]][pathX[i]];
            }

            int[] bed = carveBed(thalweg, water, depth);
            boolean[][] channel = new boolean[height][width];

            for (int i = 0; i < pathX.length; i++) {
                for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                    for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) <= halfWidth) {
                            int nx = pathX[i] + dx;
                            int nz = pathZ[i] + dz;
                            if (nx >= 0 && nz >= 0 && nx < width && nz < height) {
                                channel[nz][nx] = true;
                            }
                        }
                    }
                }
            }

            int[][] carved = new int[height][width];
            int[][] waterSurface = new int[height][width];
            RiverRasterizer.BankMaterial[][] materials = new RiverRasterizer.BankMaterial[height][width];
            boolean[][] bank = new boolean[height][width];

            for (int z = 0; z < height; z++) {
                carved[z] = (int[]) sourceGround[z].clone();

                for (int x = 0; x < width; x++) {
                    waterSurface[z][x] = Integer.MIN_VALUE;
                }
            }

            for (int i = 0; i < pathX.length; i++) {
                for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                    for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) <= halfWidth) {
                            int nx = pathX[i] + dx;
                            int nz = pathZ[i] + dz;
                            if (nx >= 0 && nz >= 0 && nx < width && nz < height) {
                                carved[nz][nx] = Math.min(carved[nz][nx], bed[i]);
                                if (waterSurface[nz][nx] == Integer.MIN_VALUE) {
                                    waterSurface[nz][nx] = water[i];
                                } else {
                                    waterSurface[nz][nx] = Math.min(waterSurface[nz][nx], water[i]);
                                }
                            }
                        }
                    }
                }
            }

            for (int i = 0; i < pathX.length; i++) {
                int rim = Math.addExact(water[i], 1);

                for (int dz = -reach; dz <= reach; dz++) {
                    for (int dx = -reach; dx <= reach; dx++) {
                        int dist = Math.max(Math.abs(dx), Math.abs(dz));
                        if (dist > halfWidth && dist <= reach) {
                            int nx = pathX[i] + dx;
                            int nz = pathZ[i] + dz;
                            if (nx >= 0 && nz >= 0 && nx < width && nz < height && !channel[nz][nx]) {
                                carved[nz][nx] = Math.max(carved[nz][nx], rim);
                                bank[nz][nx] = true;
                            }
                        }
                    }
                }
            }

            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    if (bank[z][x]) {
                        int slope = sourceSlope(sourceGround, width, height, x, z);
                        int worldX = Math.addExact(originX, x);
                        int worldZ = Math.addExact(originZ, z);
                        materials[z][x] = bankMaterial(seed, worldX, worldZ, slope);
                    }
                }
            }

            return new RiverRasterizer.RasterResult(
                    seed,
                    originX,
                    originZ,
                    accumulation,
                    seaLevelY,
                    channelWidth,
                    bankWidth,
                    carved,
                    waterSurface,
                    channel,
                    bank,
                    materials);
        } else {
            throw new IllegalArgumentException("pathX, pathZ and water must not be null");
        }
    }

    private static int sourceSlope(int[][] ground, int width, int height, int x, int z) {
        int centre = ground[z][x];
        long slope = 0L;
        if (x > 0) {
            slope = Math.max(slope, Math.abs((long) ground[z][x - 1] - centre));
        }

        if (x + 1 < width) {
            slope = Math.max(slope, Math.abs((long) ground[z][x + 1] - centre));
        }

        if (z > 0) {
            slope = Math.max(slope, Math.abs((long) ground[z - 1][x] - centre));
        }

        if (z + 1 < height) {
            slope = Math.max(slope, Math.abs((long) ground[z + 1][x] - centre));
        }

        return slope > 2147483647L ? Integer.MAX_VALUE : (int) slope;
    }

    private static double patchNoise01(long seed, int x, int z) {
        int cellX = Math.floorDiv(x, 12);
        int cellZ = Math.floorDiv(z, 12);
        double fractionX = smooth(Math.floorMod(x, 12) / 12.0);
        double fractionZ = smooth(Math.floorMod(z, 12) / 12.0);
        double north = lerp(hash01(seed, cellX, cellZ), hash01(seed, cellX + 1, cellZ), fractionX);
        double south = lerp(hash01(seed, cellX, cellZ + 1), hash01(seed, cellX + 1, cellZ + 1), fractionX);
        return lerp(north, south, fractionZ);
    }

    private static double hash01(long seed, int x, int z) {
        long hash = seed ^ 6616326155794342593L ^ x * -7046029254386353131L ^ z * -3335678366873096957L;
        hash = (hash ^ hash >>> 30) * -4658895280553007687L;
        hash = (hash ^ hash >>> 27) * -7723592293110705685L;
        hash ^= hash >>> 31;
        return (hash >>> 11) * 1.1102230246251565E-16D;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double start, double end, double fraction) {
        return start + (end - start) * fraction;
    }

    private static int validateGrid(int[][] ground) {
        if (ground == null) {
            throw new IllegalArgumentException("sourceGround must not be null");
        }

        if (ground.length == 0 || ground.length > 2048) {
            throw new IllegalArgumentException("sourceGround height must be 1..2048, got " + ground.length);
        }

        if (ground[0] == null) {
            throw new IllegalArgumentException("sourceGround row 0 must not be null");
        }

        int width = ground[0].length;
        if (width != 0 && width <= 2048) {
            for (int z = 1; z < ground.length; z++) {
                if (ground[z] == null) {
                    throw new IllegalArgumentException("sourceGround row " + z + " must not be null");
                }

                if (ground[z].length != width) {
                    throw new IllegalArgumentException(
                            "sourceGround must be rectangular: row "
                                    + z
                                    + " has width "
                                    + ground[z].length
                                    + " instead of "
                                    + width);
                }
            }

            return width;
        } else {
            throw new IllegalArgumentException("sourceGround width must be 1..2048, got " + width);
        }
    }

    public enum BankMaterial {
        GRASS_BLOCK("minecraft:grass_block"),
        DIRT("minecraft:dirt"),
        SAND("minecraft:sand"),
        GRAVEL("minecraft:gravel"),
        STONE("minecraft:stone"),
        CLAY("minecraft:clay");

        private final String blockId;

        BankMaterial(String blockId) {
            this.blockId = blockId;
        }

        public String blockId() {
            return this.blockId;
        }
    }

    public static final class RasterResult {
        private final long seed;
        private final int originX;
        private final int originZ;
        private final int width;
        private final int height;
        private final long accumulation;
        private final int seaLevel;
        private final int channelWidth;
        private final int bankWidth;
        private final int[][] carved;
        private final int[][] waterSurface;
        private final boolean[][] channel;
        private final boolean[][] bank;
        private final RiverRasterizer.BankMaterial[][] materials;

        private RasterResult(
                long seed,
                int originX,
                int originZ,
                long accumulation,
                int seaLevel,
                int channelWidth,
                int bankWidth,
                int[][] carved,
                int[][] waterSurface,
                boolean[][] channel,
                boolean[][] bank,
                RiverRasterizer.BankMaterial[][] materials) {
            this.seed = seed;
            this.originX = originX;
            this.originZ = originZ;
            this.height = carved.length;
            this.width = carved[0].length;
            this.accumulation = accumulation;
            this.seaLevel = seaLevel;
            this.channelWidth = channelWidth;
            this.bankWidth = bankWidth;
            this.carved = new int[this.height][this.width];
            this.waterSurface = new int[this.height][this.width];
            this.channel = new boolean[this.height][this.width];
            this.bank = new boolean[this.height][this.width];
            this.materials = new RiverRasterizer.BankMaterial[this.height][this.width];

            for (int z = 0; z < this.height; z++) {
                this.carved[z] = (int[]) carved[z].clone();
                this.waterSurface[z] = (int[]) waterSurface[z].clone();
                this.channel[z] = (boolean[]) channel[z].clone();
                this.bank[z] = (boolean[]) bank[z].clone();
                this.materials[z] = (RiverRasterizer.BankMaterial[]) materials[z].clone();
            }
        }

        public int width() {
            return this.width;
        }

        public int height() {
            return this.height;
        }

        public int originX() {
            return this.originX;
        }

        public int originZ() {
            return this.originZ;
        }

        public long accumulation() {
            return this.accumulation;
        }

        public int seaLevel() {
            return this.seaLevel;
        }

        public int channelWidthBlocks() {
            return this.channelWidth;
        }

        public int bankWidthBlocks() {
            return this.bankWidth;
        }

        public int carvedHeight(int x, int z) {
            this.checkCell(x, z);
            return this.carved[z][x];
        }

        public int waterY(int x, int z) {
            this.checkCell(x, z);
            return this.waterSurface[z][x];
        }

        public boolean isChannel(int x, int z) {
            this.checkCell(x, z);
            return this.channel[z][x];
        }

        public boolean isBank(int x, int z) {
            this.checkCell(x, z);
            return this.bank[z][x];
        }

        public RiverRasterizer.BankMaterial bankMaterialAt(int x, int z) {
            this.checkCell(x, z);
            return this.materials[z][x];
        }

        public String bankBlockIdAt(int x, int z) {
            RiverRasterizer.BankMaterial material = this.bankMaterialAt(x, z);
            return material == null ? null : material.blockId();
        }

        public int[][] carvedGround() {
            int[][] copy = new int[this.height][this.width];

            for (int z = 0; z < this.height; z++) {
                copy[z] = (int[]) this.carved[z].clone();
            }

            return copy;
        }

        public int[][] waterSurface() {
            int[][] copy = new int[this.height][this.width];

            for (int z = 0; z < this.height; z++) {
                copy[z] = (int[]) this.waterSurface[z].clone();
            }

            return copy;
        }

        public int worldXOf(int localX) {
            if (localX >= 0 && localX < this.width) {
                return Math.addExact(this.originX, localX);
            } else {
                throw new IllegalArgumentException("localX out of range: " + localX);
            }
        }

        public int worldZOf(int localZ) {
            if (localZ >= 0 && localZ < this.height) {
                return Math.addExact(this.originZ, localZ);
            } else {
                throw new IllegalArgumentException("localZ out of range: " + localZ);
            }
        }

        public int localXOf(int worldX) {
            int local = Math.subtractExact(worldX, this.originX);
            if (local >= 0 && local < this.width) {
                return local;
            } else {
                throw new IllegalArgumentException("worldX outside the raster: " + worldX);
            }
        }

        public int localZOf(int worldZ) {
            int local = Math.subtractExact(worldZ, this.originZ);
            if (local >= 0 && local < this.height) {
                return local;
            } else {
                throw new IllegalArgumentException("worldZ outside the raster: " + worldZ);
            }
        }

        private void checkCell(int x, int z) {
            if (x < 0 || x >= this.width || z < 0 || z >= this.height) {
                throw new IllegalArgumentException(
                        "cell (" + x + "," + z + ") outside " + this.width + "x" + this.height);
            }
        }
    }
}

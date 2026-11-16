package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

public final class MountainSurfaceRegion {
    private final int[][] surfaceY;
    private final int width;
    private final int height;
    private final long payloadBytes;

    private MountainSurfaceRegion(int[][] surfaceY, int width, int height) {
        this.surfaceY = surfaceY;
        this.width = width;
        this.height = height;
        this.payloadBytes = payloadBytes(width, height);
    }

    public static MountainSurfaceRegion copyOf(int[][] surfaceY, int width, int height) {
        payloadBytes(width, height);
        validateShape(surfaceY, width, height);
        int[][] owned = new int[height][];

        for (int row = 0; row < height; row++) {
            owned[row] = (int[]) surfaceY[row].clone();
        }

        return new MountainSurfaceRegion(owned, width, height);
    }

    public static MountainSurfaceRegion transform(
            long seed, int startX, int startZ, short[][] rawHeights, int scale, int surfaceCapY) {
        return transform(seed, startX, startZ, rawHeights, scale, surfaceCapY, 100);
    }

    public static MountainSurfaceRegion transform(
            long seed, int startX, int startZ, short[][] rawHeights, int scale, int surfaceCapY, int relief) {
        int[][] transformed =
                MountainHeightField.transformRegion(seed, startX, startZ, rawHeights, scale, surfaceCapY, relief);
        return takeOwnership(transformed, transformed[0].length, transformed.length);
    }

    static MountainSurfaceRegion takeOwnership(int[][] surfaceY, int width, int height) {
        validateShape(surfaceY, width, height);
        return new MountainSurfaceRegion(surfaceY, width, height);
    }

    static void validateShape(int[][] surfaceY, int width, int height) {
        if (width > 0 && height > 0) {
            if (surfaceY != null && surfaceY.length == height) {
                for (int row = 0; row < height; row++) {
                    if (surfaceY[row] == null || surfaceY[row].length != width) {
                        throw new IllegalArgumentException(
                                "surfaceY row " + row + " must have exactly " + width + " columns");
                    }
                }
            } else {
                throw new IllegalArgumentException("surfaceY must have exactly " + height + " rows");
            }
        } else {
            throw new IllegalArgumentException("region dimensions must be positive, got " + width + "x" + height);
        }
    }

    static long payloadBytes(int width, int height) {
        try {
            return Math.multiplyExact(4L, Math.multiplyExact((long) width, (long) height));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("region payload size overflows long", overflow);
        }
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public long payloadBytes() {
        return this.payloadBytes;
    }

    public int surfaceYAt(int localX, int localZ) {
        return this.surfaceY[localZ][localX];
    }
}

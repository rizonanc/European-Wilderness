package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;

public final class MountainHeightField {
    public static final short SOURCE_GATE_START_METRES = 900;
    public static final short SOURCE_GATE_FULL_METRES = 2600;
    public static final int RANGE_WAVELENGTH_BLOCKS = 8192;
    public static final int SHOULDER_WAVELENGTH_BLOCKS = 2048;
    public static final double RANGE_SELECT_START = 0.84;
    public static final double RANGE_SELECT_FULL = 0.94;
    public static final double RELIEF_GAIN = 0.45;
    public static final int PEAK_BONUS_BLOCKS = 520;
    public static final int MAX_ADDED_BLOCKS = 720;
    private static final long RANGE_SALT = 99055890951435L;
    private static final long SHOULDER_SALT = 49005279514701L;
    private static final long SEED_MIX = -6752110988234923001L;
    private static final long X_MIX = -7046029254386353131L;
    private static final long Z_MIX = -3335678366873096957L;

    private MountainHeightField() {}

    public static MountainHeightField.Sample sample(
            long seed, int blockX, int blockZ, short sourceMetres, int scale, int surfaceCapY) {
        return sample(seed, blockX, blockZ, sourceMetres, scale, surfaceCapY, 100);
    }

    public static Sample sampleForWorld(long seed, int x, int z, short metres, int scale, int cap) {
        var session = WorldSession.current();
        int relief = session != null
                        && session.isActive()
                        && session.worldSeed() == seed
                        && session.profile().worldScale() == scale
                ? session.profile().mountainRelief()
                : 100;
        return sample(seed, x, z, metres, scale, cap, relief);
    }

    public static Sample sample(
            long seed, int blockX, int blockZ, short sourceMetres, int scale, int surfaceCapY, int relief) {
        validateScale(scale);
        int evaluatedSurfaceY = surfaceY(seed, blockX, blockZ, sourceMetres, scale, surfaceCapY, relief);
        int sourceY = HeightConverter.convertToMinecraftHeight(sourceMetres, scale);
        if (sourceMetres <= 900) {
            return new MountainHeightField.Sample(sourceY, evaluatedSurfaceY, 0, 0.0, evaluatedSurfaceY < sourceY);
        }

        double elevation = smoothstep(900.0, 2600.0, sourceMetres);
        double mask = systemMask(seed, blockX, blockZ, elevation);
        int added = requestedAddedBlocks(sourceY, scale, elevation, mask, relief);
        long uncapped = (long) sourceY + added;
        int effectiveSourceY = Math.min(sourceY, surfaceCapY);
        int reportedAddedBlocks = Math.max(0, evaluatedSurfaceY - effectiveSourceY);
        return new MountainHeightField.Sample(
                sourceY, evaluatedSurfaceY, reportedAddedBlocks, mask, uncapped > surfaceCapY);
    }

    public static int[][] transformRegion(
            long seed, int startX, int startZ, short[][] sourceMetres, int scale, int surfaceCapY) {
        return transformRegion(seed, startX, startZ, sourceMetres, scale, surfaceCapY, 100);
    }

    static int[][] transformRegion(
            long seed, int startX, int startZ, short[][] sourceMetres, int scale, int surfaceCapY, int relief) {
        validateScale(scale);
        int width = validateRegion(sourceMetres);
        int[][] transformed = new int[sourceMetres.length][width];

        for (int z = 0; z < sourceMetres.length; z++) {
            int blockZ = Math.addExact(startZ, z);

            for (int x = 0; x < width; x++) {
                int blockX = Math.addExact(startX, x);
                transformed[z][x] = surfaceY(seed, blockX, blockZ, sourceMetres[z][x], scale, surfaceCapY, relief);
            }
        }

        return transformed;
    }

    private static int surfaceY(
            long seed, int blockX, int blockZ, short sourceMetres, int scale, int surfaceCapY, int relief) {
        int sourceY = HeightConverter.convertToMinecraftHeight(sourceMetres, scale);
        if (sourceMetres <= 900) {
            return Math.min(sourceY, surfaceCapY);
        }

        double elevation = smoothstep(900.0, 2600.0, sourceMetres);
        double mask = systemMask(seed, blockX, blockZ, elevation);
        int added = requestedAddedBlocks(sourceY, scale, elevation, mask, relief);
        long uncapped = (long) sourceY + added;
        return (int) Math.min(surfaceCapY, uncapped);
    }

    private static double systemMask(long seed, int blockX, int blockZ, double elevation) {
        double range = smoothstep(0.84, 0.94, valueNoise01(seed ^ 99055890951435L, blockX, blockZ, 8192));
        double shoulder = 0.85 + 0.15 * valueNoise01(seed ^ 49005279514701L, blockX, blockZ, 2048);
        return clamp01(elevation * range * shoulder);
    }

    private static int requestedAddedBlocks(int sourceY, int scale, double elevation, double mask, int relief) {
        int foothillY = HeightConverter.convertToMinecraftHeight((short) 900, scale);
        int sourceRelief = Math.max(0, sourceY - foothillY);
        long requested = Math.round(mask * (0.45 * sourceRelief + 520.0 * elevation * elevation));
        return (int) ((Math.clamp(requested, 0, MAX_ADDED_BLOCKS) * relief + 50) / 100);
    }

    private static double valueNoise01(long seed, int blockX, int blockZ, int wavelength) {
        int latticeX = Math.floorDiv(blockX, wavelength);
        int latticeZ = Math.floorDiv(blockZ, wavelength);
        double fractionX = (double) Math.floorMod(blockX, wavelength) / wavelength;
        double fractionZ = (double) Math.floorMod(blockZ, wavelength) / wavelength;
        double smoothX = smoothFraction(fractionX);
        double smoothZ = smoothFraction(fractionZ);
        double northWest = hashNoise01(seed, latticeX, latticeZ);
        double northEast = hashNoise01(seed, latticeX + 1, latticeZ);
        double southWest = hashNoise01(seed, latticeX, latticeZ + 1);
        double southEast = hashNoise01(seed, latticeX + 1, latticeZ + 1);
        double north = lerp(northWest, northEast, smoothX);
        double south = lerp(southWest, southEast, smoothX);
        return lerp(north, south, smoothZ);
    }

    private static double hashNoise01(long seed, int latticeX, int latticeZ) {
        long hash = seed * -6752110988234923001L ^ latticeX * -7046029254386353131L ^ latticeZ * -3335678366873096957L;
        hash = (hash ^ hash >>> 30) * -4658895280553007687L;
        hash = (hash ^ hash >>> 27) * -7723592293110705685L;
        hash ^= hash >>> 31;
        return (hash >>> 11) * 1.1102230246251565E-16D;
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        return smoothFraction(clamp01((value - edge0) / (edge1 - edge0)));
    }

    private static double smoothFraction(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double start, double end, double fraction) {
        return start + (end - start) * fraction;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static void validateScale(int scale) {
        if (scale < 1 || scale > 6) {
            throw new IllegalArgumentException("Unsupported world scale: " + scale);
        }
    }

    private static int validateRegion(short[][] sourceMetres) {
        if (sourceMetres == null) {
            throw new IllegalArgumentException("sourceMetres must not be null");
        }

        if (sourceMetres.length == 0) {
            throw new IllegalArgumentException("sourceMetres must contain at least one row");
        }

        if (sourceMetres[0] == null) {
            throw new IllegalArgumentException("sourceMetres row 0 must not be null");
        }

        int width = sourceMetres[0].length;
        if (width == 0) {
            throw new IllegalArgumentException("sourceMetres rows must contain at least one value");
        }

        for (int z = 1; z < sourceMetres.length; z++) {
            if (sourceMetres[z] == null) {
                throw new IllegalArgumentException("sourceMetres row " + z + " must not be null");
            }

            if (sourceMetres[z].length != width) {
                throw new IllegalArgumentException("sourceMetres must be rectangular: row "
                        + z
                        + " has width "
                        + sourceMetres[z].length
                        + " instead of "
                        + width);
            }
        }

        return width;
    }

    public record Sample(int sourceY, int surfaceY, int addedBlocks, double systemMask, boolean capped) {}
}

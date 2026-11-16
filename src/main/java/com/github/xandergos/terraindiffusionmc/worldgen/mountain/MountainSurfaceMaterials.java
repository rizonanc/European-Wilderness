package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

public final class MountainSurfaceMaterials {
    private static final long ROCK_SALT = 8892408665399891877L;
    private static final long SNOW_SALT = 4726600117519211303L;
    private static final long X_MIX = -7046029254386353131L;
    private static final long Z_MIX = -3335678366873096957L;

    private MountainSurfaceMaterials() {}

    public static MountainSurfaceMaterials.Material classify(
            long seed, int x, int z, int surfaceY, float modelTempC, int northY, int southY, int westY, int eastY) {
        long northDelta = (long) northY - surfaceY;
        long southDelta = (long) southY - surfaceY;
        long westDelta = (long) westY - surfaceY;
        long eastDelta = (long) eastY - surfaceY;
        long slope = Math.max(
                Math.max(Math.abs(northDelta), Math.abs(southDelta)),
                Math.max(Math.abs(westDelta), Math.abs(eastDelta)));
        if (surfaceY < 300 && slope <= 1L) {
            return MountainSurfaceMaterials.Material.NONE;
        }

        if (surfaceY >= 240 && slope >= 9L) {
            return MountainSurfaceMaterials.Material.ROCK;
        }

        double convexity = -0.25 * (northDelta + southDelta + westDelta + eastDelta);
        double shelter = Math.min(6.0, Math.max(0.0, -convexity));
        double exposure = Math.min(6.0, Math.max(0.0, convexity));
        double rockPatch = valueNoise01(seed ^ 8892408665399891877L, x, z, 40);
        double rockScore = 0.3 * Math.min(9L, slope)
                + 0.0015 * Math.min(1200, Math.max(0, surfaceY - 280))
                + 0.15 * Math.max(-5.0, Math.min(5.0, convexity))
                + 1.8 * (rockPatch - 0.5);
        double windScore = rockPatch
                + 5.0E-4 * Math.min(400, Math.max(0, surfaceY - 360))
                + 0.02 * (Math.min(4L, slope) - 1L)
                + 0.02 * Math.max(-2.0, Math.min(2.0, convexity));
        double severeCold = Math.min(1.0, Math.max(0.0, (-modelTempC - 12.0) / 20.0));
        windScore -= 0.18 * severeCold;
        if (slope == 1L) {
            windScore -= 0.35 * severeCold;
        }

        if (surfaceY >= 360 && slope >= 1L && convexity > -2.0 && windScore >= 0.71) {
            return windScore >= 0.735
                    ? MountainSurfaceMaterials.Material.ROCK
                    : MountainSurfaceMaterials.Material.SCREE;
        }

        double adjustedTempC = modelTempC - Math.max(0, surfaceY - 256) * 0.0065;
        if (slope <= 4L) {
            double snowPatch = valueNoise01(seed ^ 4726600117519211303L, x, z, 48);
            double snowScore =
                    -adjustedTempC + 0.5 * shelter - 0.55 * exposure - 0.55 * slope + 3.0 * (snowPatch - 0.5);
            if (snowScore >= 5.0) {
                return MountainSurfaceMaterials.Material.SNOW;
            }

            if (snowScore >= 1.5) {
                return MountainSurfaceMaterials.Material.THIN_SNOW;
            }
        }

        if (surfaceY < 300) {
            return MountainSurfaceMaterials.Material.NONE;
        } else if (rockScore >= 1.65) {
            return MountainSurfaceMaterials.Material.ROCK;
        } else {
            return slope >= 2L && rockScore >= 1.2
                    ? MountainSurfaceMaterials.Material.SCREE
                    : MountainSurfaceMaterials.Material.NONE;
        }
    }

    static double valueNoise01(long seed, int x, int z, int wavelength) {
        int cellX = Math.floorDiv(x, wavelength);
        int cellZ = Math.floorDiv(z, wavelength);
        double fractionX = smooth((double) Math.floorMod(x, wavelength) / wavelength);
        double fractionZ = smooth((double) Math.floorMod(z, wavelength) / wavelength);
        double north = lerp(hash01(seed, cellX, cellZ), hash01(seed, cellX + 1, cellZ), fractionX);
        double south = lerp(hash01(seed, cellX, cellZ + 1), hash01(seed, cellX + 1, cellZ + 1), fractionX);
        return lerp(north, south, fractionZ);
    }

    private static double hash01(long seed, int x, int z) {
        long hash = seed ^ x * -7046029254386353131L ^ z * -3335678366873096957L;
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

    public enum Material {
        NONE,
        ROCK,
        SCREE,
        SNOW,
        THIN_SNOW,
        MORAINE;
    }
}

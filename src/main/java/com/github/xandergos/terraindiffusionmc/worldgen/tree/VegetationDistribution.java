package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanEcology;
import java.util.Set;

public final class VegetationDistribution {
    private static final Set<Short> TREE_BIOMES =
            Set.of((short) 208, (short) 209, (short) 229, (short) 215, (short) 216);
    private static final double TREE_DENSITY = TerrainDiffusionConfig.percent("nature.tree_density") / 100.0;

    public static boolean acceptsBiome(short biomeId) {
        return TREE_BIOMES.contains(biomeId);
    }

    private VegetationDistribution() {}

    public static double adultAcceptance(EuropeanEcology.ForestCondition condition) {
        return condition.forbidsTrees() ? 0.0 : clamp(0.2 + 0.85 * condition.forestDensity(), 0.0, 1.0);
    }

    public static double adultAcceptance(
            EuropeanEcology.ForestCondition condition, VegetationDistribution.Context context) {
        if (condition.forbidsTrees()) {
            return 0.0;
        } else {
            return condition.zone() != EuropeanEcology.Zone.MEADOW
                            && condition.zone() != EuropeanEcology.Zone.MOUNTAIN_MEADOW
                    ? clamp(context.forestFloor + context.forestDensitySlope * condition.forestDensity(), 0.0, 1.0)
                    : context.meadowAcceptance;
        }
    }

    public static double adultAcceptance(long seed, EuropeanEcology.ForestCondition condition, int x, int z) {
        return adultAcceptance(condition, context(seed, x, z));
    }

    public static double youngAcceptance(long seed, EuropeanEcology.ForestCondition condition, int x, int z) {
        return !condition.forbidsTrees() && EuropeanEcology.youngPatch(seed, x, z)
                ? clamp(0.15 + 0.55 * condition.forestDensity(), 0.0, 0.7)
                : 0.0;
    }

    public static boolean acceptsAdult(
            TreeCandidates.Candidate candidate,
            EuropeanEcology.ForestCondition condition,
            VegetationDistribution.Context context) {
        return candidate.age() != TreeAge.YOUNG
                && candidate.acceptance() < adultAcceptance(condition, context) * TREE_DENSITY;
    }

    public static boolean acceptsAdult(
            TreeCandidates.Candidate candidate, long seed, EuropeanEcology.ForestCondition condition) {
        return acceptsAdult(candidate, condition, context(seed, candidate.x(), candidate.z()));
    }

    public static boolean acceptsYoung(
            TreeCandidates.Candidate candidate, long seed, EuropeanEcology.ForestCondition condition) {
        return candidate.age() == TreeAge.YOUNG
                && candidate.acceptance()
                        < youngAcceptance(seed, condition, candidate.x(), candidate.z()) * TREE_DENSITY;
    }

    public static VegetationDistribution.Context context(long seed, int x, int z) {
        double large = valueNoise(seed, x, z, 0, 448);
        double medium = valueNoise(seed, x, z, 1, 144);
        double field = 0.74 * large + 0.26 * medium;
        if (field < 0.35) {
            return VegetationDistribution.Context.CLEARING;
        } else if (field < 0.46) {
            return VegetationDistribution.Context.EDGE;
        } else {
            return field < 0.61 ? VegetationDistribution.Context.ORDINARY : VegetationDistribution.Context.CORE;
        }
    }

    static double valueNoise(long seed, int x, int z, int channel, int wavelength) {
        int gridX = Math.floorDiv(x, wavelength);
        int gridZ = Math.floorDiv(z, wavelength);
        double tx = smoothstep((double) Math.floorMod(x, wavelength) / wavelength);
        double tz = smoothstep((double) Math.floorMod(z, wavelength) / wavelength);
        double a = hashUnit(seed, gridX, gridZ, channel);
        double b = hashUnit(seed, gridX + 1, gridZ, channel);
        double c = hashUnit(seed, gridX, gridZ + 1, channel);
        double d = hashUnit(seed, gridX + 1, gridZ + 1, channel);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), tz);
    }

    private static double hashUnit(long seed, int x, int z, int channel) {
        long value = seed ^ x * -7046029254386353131L ^ z * -3335678366873096957L ^ channel * -7723592293110705685L;
        value = (value ^ value >>> 30) * -4658895280553007687L;
        value = (value ^ value >>> 27) * -7723592293110705685L;
        value ^= value >>> 31;
        return (value >>> 11) * 1.1102230246251565E-16D;
    }

    private static double smoothstep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double a, double b, double amount) {
        return a + (b - a) * amount;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public enum Context {
        CORE(0.95, 0.08, 0.17),
        ORDINARY(0.68, 0.18, 0.155),
        EDGE(0.45, 0.16, 0.14),
        CLEARING(0.23, 0.1, 0.12);

        private final double forestFloor;
        private final double forestDensitySlope;
        private final double meadowAcceptance;

        Context(double forestFloor, double forestDensitySlope, double meadowAcceptance) {
            this.forestFloor = forestFloor;
            this.forestDensitySlope = forestDensitySlope;
            this.meadowAcceptance = meadowAcceptance;
        }
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.BiomeBalance;

public final class EuropeanBiomeClassifier {
    public static final short DECIDUOUS = 208;
    public static final short MIXED = 209;
    public static final short EUROPEAN_MEADOW = 229;

    private EuropeanBiomeClassifier() {}

    public static short[] classify(
            long seed,
            float[] elevation,
            float[] climate,
            short[] upstream,
            float[] halo,
            int height,
            int width,
            float pixelSizeM,
            int x0,
            int z0,
            boolean largerLandscapes,
            BiomeBalance balance) {
        int cells = Math.multiplyExact(height, width);
        if (height > 0
                && width > 0
                && elevation != null
                && elevation.length == cells
                && upstream != null
                && upstream.length == cells
                && climate != null
                && climate.length == 4 * cells
                && halo != null
                && halo.length == (height + 2) * (width + 2)
                && Float.isFinite(pixelSizeM)
                && !(pixelSizeM <= 0.0F)) {
            short[] result = (short[]) upstream.clone();

            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) {
                    int i = z * width + x;
                    double temp = climate[i];
                    double precip = climate[2 * cells + i];
                    double elev = elevation[i];
                    if (!Double.isFinite(temp) || !Double.isFinite(precip) || !Double.isFinite(elev)) {
                        throw new IllegalArgumentException("Non-finite ecology input");
                    }

                    short old = upstream[i];
                    int blockX = x0 + x, blockZ = z0 + z;
                    boolean temperate = temp >= -5.0 && temp <= 20.0 && precip >= 250.0;
                    boolean dryLowland = largerLandscapes
                            && elev >= 0
                            && elev < 1400
                            && temp >= 11
                            && ((balance.deserts() != 100 && old == 5)
                                    || EuropeanEcology.aridity(temp, precip) < balance.desertAridityLimit());
                    if (old != 41 && old != 44 && old != 46 && old != 48 && (temperate || dryLowland)) {
                        int center = (z + 1) * (width + 2) + x + 1;
                        double dx = (halo[center + 1] - halo[center - 1]) / (2.0 * pixelSizeM);
                        double dz = (halo[center + width + 2] - halo[center - width - 2]) / (2.0 * pixelSizeM);
                        if (!Double.isFinite(dx) || !Double.isFinite(dz)) {
                            throw new IllegalArgumentException("Non-finite ecology halo");
                        }

                        EuropeanEcology.ForestCondition condition = EuropeanEcology.evaluate(
                                seed,
                                temp,
                                precip,
                                elev,
                                Math.hypot(dx, dz),
                                Double.POSITIVE_INFINITY,
                                blockX,
                                blockZ,
                                largerLandscapes,
                                balance);

                        if (!temperate && condition.zone() != EuropeanEcology.Zone.DESERT) {
                            if (old == 5) result[i] = 17;
                            continue;
                        }
                        result[i] = (short)
                                (switch (condition.zone()) {
                                    case DECIDUOUS_FOREST -> condition.forestDensity() < 0.18 ? 229 : 208;
                                    case MIXED_FOREST -> condition.forestDensity() < 0.18 ? 229 : 209;
                                    case CONIFEROUS_FOREST -> (short) (condition.forestDensity() < 0.18 ? 216 : 215);
                                    case MEADOW, FLOODPLAIN -> 229;
                                    case MOUNTAIN_MEADOW -> 29;
                                    case ROCKY_SLOPE -> 35;
                                    case ALPINE_SNOW -> 32;
                                    case DESERT -> 5;
                                });
                    }
                    if (largerLandscapes) result[i] = withOpenFields(seed, blockX, blockZ, result[i], balance);
                }
            }

            return result;
        } else {
            throw new IllegalArgumentException("Ecology requires complete elevation, climate and one-cell halo");
        }
    }

    public static short withOpenFields(long seed, int x, int z, short biome, BiomeBalance balance) {
        return isForest(biome) && EuropeanEcology.openField(seed, x, z, balance) ? EUROPEAN_MEADOW : biome;
    }

    private static boolean isForest(short biome) {
        return switch (biome) {
            case 8, 15, 16, 108, 115, 116, 208, 209, 215, 216 -> true;
            default -> false;
        };
    }
}

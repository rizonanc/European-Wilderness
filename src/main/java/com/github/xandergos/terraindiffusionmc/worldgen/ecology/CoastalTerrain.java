package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

public final class CoastalTerrain {
    private CoastalTerrain() {}

    public static float height(long seed, int x, int z, float metres) {
        if (Math.abs(metres) >= 220) return metres;
        double coast = EuropeanEcology.valueNoise(seed, x, z, 30, 1300);
        double weight = 1 - EuropeanEcology.smoothstep(90, 220, Math.abs(metres));
        if (coast < 0.39) {
            float terrace = metres < 0 ? metres * 0.50F : metres * 0.48F;
            return (float)(metres + (terrace - metres) * weight * (1 - EuropeanEcology.smoothstep(0.27, 0.39, coast)));
        }
        if (coast > 0.62 && metres > 0) {
            return (float)(metres + 100 * weight * EuropeanEcology.smoothstep(0.62, 0.79, coast)
                * EuropeanEcology.smoothstep(0, 60, metres));
        }
        return metres;
    }

    public static short biome(long seed, int x, int z, float metres, int scale, short original) {
        if (metres < 0 || metres > 180) return original;
        double coast = EuropeanEcology.valueNoise(seed, x, z, 30, 1300);
        if (metres < 100F / scale && coast < 0.37) return TropicalLandforms.ISLAND_BEACH;
        if (metres < 120F / scale && coast > 0.79) return 35;
        return original;
    }
}

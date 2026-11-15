package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

public final class CoastalTerrain {
    private CoastalTerrain() {}

    public static float height(long seed, int x, int z, float metres) {
        if (Math.abs(metres) >= 220) return metres;
        double coast = EuropeanEcology.valueNoise(seed, x, z, 30, 1300);
        double weight = 1 - EuropeanEcology.smoothstep(90, 220, Math.abs(metres));
        if (coast < 0.39) {
            float terrace = metres < 0 ? metres * 0.50F : metres * 0.48F;
            return (float) (metres + (terrace - metres) * weight * (1 - EuropeanEcology.smoothstep(0.27, 0.39, coast)));
        }
        if (coast > 0.62 && metres > 0) {
            return (float) (metres
                    + 100
                            * weight
                            * EuropeanEcology.smoothstep(0.62, 0.79, coast)
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

    public static float fjordsAndSkerries(long seed, int x, int z, float metres, int scale) {
        if (metres <= -240 || metres >= 520) return metres;
        int wavelength = 150 * scale;
        double province = EuropeanEcology.valueNoise(seed, x, z, 71, 640 * scale);
        double weight = EuropeanEcology.smoothstep(0.62, 0.82, province)
                * EuropeanEcology.smoothstep(-240, -50, metres)
                * (1 - EuropeanEcology.smoothstep(200, 520, metres));
        if (weight == 0) return metres;

        double bend = EuropeanEcology.valueNoise(seed, x, z, 72, wavelength);
        double across = (x + 0.44 * z) / wavelength + 0.55 * bend;
        double channel = 1 - EuropeanEcology.smoothstep(0.04, 0.26, Math.abs(across - Math.rint(across)));
        double shelf = (1 - EuropeanEcology.smoothstep(-28, 45, metres))
                * EuropeanEcology.smoothstep(0.65, 0.9, EuropeanEcology.valueNoise(seed, x, z, 73, 34 * scale));
        return (float) (metres + weight * (165 * shelf * (1 - channel) - 570 * channel));
    }
}

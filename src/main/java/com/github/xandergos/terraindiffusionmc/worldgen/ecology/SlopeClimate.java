package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

public final class SlopeClimate {
    private SlopeClimate() {}

    public static float exposure(double north, double south, double east, double west, double step) {
        double northGradient = (north - south) / (2 * step);
        double eastGradient = (east - west) / (2 * step);
        return (float)Math.clamp(northGradient / Math.max(0.6, Math.hypot(northGradient, eastGradient)), -1, 1);
    }

    public static float temperature(float exposure) { return exposure * 2.6F; }
    public static float precipitation(float exposure) { return 1 - exposure * 0.18F; }
}

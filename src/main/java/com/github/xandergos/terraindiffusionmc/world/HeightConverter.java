package com.github.xandergos.terraindiffusionmc.world;

public class HeightConverter {
    public static final float GENERATION_NATIVE_RESOLUTION_METRES = 30.0F;
    private static final int SEA_LEVEL = 63;
    private static final short MAX_PIPELINE_METERS = 10000;

    private static float getResolutionForScale(int configuredScale) {
        return 30.0F / WorldScaleManager.clampScale(configuredScale);
    }

    public static int convertToMinecraftHeight(short meters) {
        return convertToMinecraftHeight(meters, WorldScaleManager.getCurrentScale());
    }

    public static int convertToMinecraftHeight(short meters, int configuredScale) {
        float resolution = getResolutionForScale(configuredScale);
        int baseY;
        if (meters >= 0) {
            baseY = (int) (meters / resolution);
        } else {
            baseY = (int) (-Math.sqrt(Math.abs(meters) + 10) + Math.sqrt(10.0)) - 1;
        }

        return baseY + 63;
    }

    public static int getMaxGeneratedYForScale(int configuredScale) {
        return convertToMinecraftHeight((short) 10000, configuredScale);
    }
}

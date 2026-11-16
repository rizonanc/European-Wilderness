package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

public final class ForestTrail {
    static final int CHANNEL = 7;
    static final int WAVELENGTH = 48;
    static final double PATH_HALF_WIDTH = 0.015;
    static final double EDGE_HALF_WIDTH = 0.035;

    private ForestTrail() {}

    public static ForestTrail.Kind kind(long seed, int x, int z) {
        double distance = Math.abs(EuropeanEcology.valueNoise(seed, x, z, 7, 48) - 0.5);
        if (distance < 0.015) {
            return ForestTrail.Kind.PATH;
        } else {
            return distance < 0.035 ? ForestTrail.Kind.EDGE : ForestTrail.Kind.NONE;
        }
    }

    public enum Kind {
        NONE,
        EDGE,
        PATH;
    }
}

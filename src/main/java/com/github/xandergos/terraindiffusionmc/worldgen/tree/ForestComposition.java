package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanEcology;

/** Continuous world-coordinate species probabilities, shared by real trees and prepared previews. */
public final class ForestComposition {
    public record Mix(double oak, double birch, double spruce, double pine) {
        public TreeSpecies choose(double roll) {
            if (roll < oak) return TreeSpecies.OAK;
            roll -= oak;
            if (roll < birch) return TreeSpecies.BIRCH;
            return roll - birch < spruce ? TreeSpecies.SPRUCE : TreeSpecies.PINE;
        }
    }

    private static double gain(long seed, int x, int z, int channel) {
        double field =
                .75 * VegetationDistribution.valueNoise(seed, x, z, channel, 256)
                        + .25 * VegetationDistribution.valueNoise(seed, x, z, channel + 7, 96);
        return .35 + 2.2 * field * field;
    }

    public static Mix at(long seed, int x, int z, EuropeanEcology.ForestCondition ecology) {
        double oak = ecology.oakShare() * gain(seed, x, z, 31),
                birch = ecology.birchShare() * gain(seed, x, z, 32),
                spruce = ecology.spruceShare() * gain(seed, x, z, 33),
                pine = ecology.pineShare() * gain(seed, x, z, 34);
        double sum = oak + birch + spruce + pine;
        if (sum <= 0) return new Mix(0, 0, 0, 1);
        return new Mix(oak / sum, birch / sum, spruce / sum, pine / sum);
    }

    private ForestComposition() {}
}

package com.github.xandergos.terraindiffusionmc.worldgen.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record BiomeBalance(int mountains, int oceans, int deserts, int forests, int fields) {
    public static final BiomeBalance DEFAULT = new BiomeBalance(100, 100, 100, 100, 100);
    private static final Codec<Integer> AMOUNT = Codec.intRange(0, 200);

    public static final Codec<BiomeBalance> CODEC =
            RecordCodecBuilder.create(instance ->
                    instance.group(
                                    AMOUNT.optionalFieldOf("mountains", 100).forGetter(BiomeBalance::mountains),
                                    AMOUNT.optionalFieldOf("oceans", 100).forGetter(BiomeBalance::oceans),
                                    AMOUNT.optionalFieldOf("deserts", 100).forGetter(BiomeBalance::deserts),
                                    AMOUNT.optionalFieldOf("forests", 100).forGetter(BiomeBalance::forests),
                                    AMOUNT.optionalFieldOf("fields", 100).forGetter(BiomeBalance::fields))
                            .apply(instance, BiomeBalance::new));

    public float adjustElevation(float metres) {
        if (oceans != 100) {
            // Shift the coastline smoothly while preserving the deep sea and inland highlands.
            float coast = Math.max(0, 1 - Math.abs(metres) / 300);
            metres -= (oceans - 100) * 0.9F * coast * coast * (3 - 2 * coast);
        }
        if (mountains != 100 && metres > 200) {
            float upland = Math.clamp((metres - 200) / 1200, 0, 1);
            metres += (mountains - 100) * 5.0F * upland * upland * (3 - 2 * upland);
        }
        return metres;
    }

    public double desertAridityLimit() {
        return 0.32 + (deserts - 100) * 0.0018;
    }

    public double desertMaskThreshold() {
        return 0.55 - (deserts - 100) * 0.004;
    }

    public double fieldMaskThreshold() {
        return Math.clamp(0.148 + (fields - forests) * 0.00148, 0.0, 0.444);
    }
}

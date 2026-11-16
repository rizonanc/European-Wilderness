package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record SpawnChoice(int x, int z, String habitat, long seed) {
    private static final Codec<Integer> COORDINATE = Codec.intRange(-29_999_872, 29_999_872);
    public static final Codec<SpawnChoice> CODEC = RecordCodecBuilder.create(i -> i.group(
                    COORDINATE.fieldOf("x").forGetter(SpawnChoice::x),
                    COORDINATE.fieldOf("z").forGetter(SpawnChoice::z),
                    Codec.STRING.fieldOf("habitat").forGetter(SpawnChoice::habitat),
                    Codec.LONG.fieldOf("seed").forGetter(SpawnChoice::seed))
            .apply(i, SpawnChoice::new));

    public SpawnChoice {
        if (Math.abs((long) x) > 29_999_872 || Math.abs((long) z) > 29_999_872 || !validHabitat(habitat))
            throw new IllegalArgumentException("Invalid spawn choice");
    }

    private static boolean validHabitat(String habitat) {
        return switch (habitat) {
            case "valley", "coast", "forest", "foothills" -> true;
            default -> false;
        };
    }

    public boolean matches(int height, int west, int east, int north, int south, short biome) {
        int high = Math.max(Math.max(west, east), Math.max(north, south));
        int low = Math.min(Math.min(west, east), Math.min(north, south));
        boolean valley = Math.min(west, east) >= height + 5 || Math.min(north, south) >= height + 5;
        return switch (habitat) {
            case "coast" -> low <= 62 && height <= 95;
            case "forest" ->
                switch (biome) {
                    case 8, 15, 16, 108, 115, 116, 208, 209, 215, 216 -> true;
                    default -> false;
                };
            case "foothills" -> height >= 100 && high >= height + 7 && low <= height - 4;
            case "valley" -> height < 160 && valley;
            default -> false;
        };
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.spawn;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.entity.EntityType;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.biome.Biome;

public final class AnimalHabitat {
    private AnimalHabitat() {}

    public static boolean allows(EntityType<?> type, ServerWorldAccess world, BlockPos pos, Random random) {
        if (!(world.toServerWorld().getChunkManager().getChunkGenerator().getBiomeSource()
                instanceof TerrainDiffusionBiomeSource)) return true;
        if (!usesHabitat(type)) return true;
        var biomeEntry = world.getBiome(pos);
        String biome = biomeEntry.getKey().map(key -> key.getValue().getPath()).orElse("");
        int altitude = pos.getY() - world.getSeaLevel();
        boolean cold = biomeEntry.value().getPrecipitation(pos, world.getSeaLevel()) == Biome.Precipitation.SNOW;
        boolean nearWater = false;
        if (type == EntityType.FROG || type == EntityType.TURTLE) {
            for (var candidate : BlockPos.iterateOutwards(pos.down(), 4, 2, 4)) {
                if (world.isChunkLoaded(candidate.getX() >> 4, candidate.getZ() >> 4)
                        && world.getFluidState(candidate).isIn(FluidTags.WATER)) {
                    nearWater = true;
                    break;
                }
            }
        }
        return random.nextFloat() < chance(type, biome, altitude, cold, nearWater);
    }

    private static boolean usesHabitat(EntityType<?> type) {
        return type == EntityType.GOAT
                || type == EntityType.FROG
                || type == EntityType.TURTLE
                || type == EntityType.WOLF
                || type == EntityType.FOX
                || type == EntityType.COW
                || type == EntityType.HORSE
                || type == EntityType.DONKEY
                || type == EntityType.PIG
                || type == EntityType.CHICKEN
                || type == EntityType.SHEEP
                || type == EntityType.RABBIT;
    }

    static float chance(EntityType<?> type, String biome, int altitude, boolean cold, boolean water) {
        boolean forest = biome.contains("forest")
                || biome.contains("taiga")
                || biome.startsWith("european_") && !biome.endsWith("meadow");
        if (type == EntityType.GOAT) return altitude >= 55 ? 1 : 0.05F;
        if (type == EntityType.FROG || type == EntityType.TURTLE) return water && !cold && altitude < 20 ? 1 : 0;
        if (type == EntityType.WOLF) return forest ? 1 : 0.14F;
        if (type == EntityType.FOX) return forest && (cold || biome.contains("coniferous")) ? 1 : 0.3F;
        if (type == EntityType.COW || type == EntityType.HORSE || type == EntityType.DONKEY)
            return altitude > 85 ? 0.12F : forest ? 0.35F : 1;
        if (type == EntityType.PIG || type == EntityType.CHICKEN)
            return cold || altitude > 110 ? 0.15F : forest ? 1 : 0.65F;
        if (type == EntityType.SHEEP || type == EntityType.RABBIT) return forest ? 0.55F : 1;
        return 1;
    }
}

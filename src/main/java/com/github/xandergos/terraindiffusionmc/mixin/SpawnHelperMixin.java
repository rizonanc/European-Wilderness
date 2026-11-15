package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.worldgen.spawn.DaylightHostileSpawnFilter;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.Mutable;
import net.minecraft.world.Heightmap.Type;
import net.minecraft.world.SpawnHelper;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.SpawnSettings.SpawnEntry;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpawnHelper.class)
public abstract class SpawnHelperMixin {
    @Inject(
            method =
                    "canSpawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/SpawnGroup;Lnet/minecraft/world/gen/StructureAccessor;Lnet/minecraft/world/gen/chunk/ChunkGenerator;Lnet/minecraft/world/biome/SpawnSettings$SpawnEntry;Lnet/minecraft/util/math/BlockPos$Mutable;D)Z",
            at = @At("HEAD"),
            cancellable = true)
    private static void terrainDiffusion$vetoDaylightSurfaceHostile(
            ServerWorld world,
            SpawnGroup group,
            StructureAccessor structures,
            ChunkGenerator generator,
            SpawnEntry entry,
            Mutable pos,
            double distance,
            CallbackInfoReturnable<Boolean> cir) {
        if (group == SpawnGroup.MONSTER && world.isDay()) {
            int top = world.getTopY(Type.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
            boolean surface = DaylightHostileSpawnFilter.isSurfaceSpawn(pos.getY(), top);
            if (!surface) {
                surface = DaylightHostileSpawnFilter.isSurfaceSpawn(
                        pos.getY(), resolveGroundTop(world, pos.getX(), top, pos.getZ()));
            }

            boolean ourForest = surface && isOurForest(world, pos);
            if (DaylightHostileSpawnFilter.shouldBlock(true, true, true, surface, ourForest)) {
                cir.setReturnValue(false);
            }
        }
    }

    private static boolean isOurForest(ServerWorld world, BlockPos pos) {
        Optional<RegistryKey<Biome>> key = world.getBiome(pos).getKey();
        if (key.isEmpty()) {
            return false;
        }

        Identifier id = key.get().getValue();
        return DaylightHostileSpawnFilter.isOurForestBiome(id.getNamespace(), id.getPath());
    }

    private static int resolveGroundTop(ServerWorld world, int x, int top, int z) {
        int bottom = world.getBottomY();
        int y = top - 1;
        Mutable cursor = new Mutable(x, y, z);

        while (y > bottom && isCanopyVoxel(world.getBlockState(cursor))) {
            cursor.setY(--y);
        }

        return y + 1;
    }

    private static boolean isCanopyVoxel(BlockState state) {
        return state.isAir() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS);
    }
}

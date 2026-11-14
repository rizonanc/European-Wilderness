package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.pipeline.SpawnSelector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProperties.SpawnPoint;
import net.minecraft.world.chunk.ChunkLoadProgress;
import net.minecraft.world.level.ServerWorldProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {
   @Inject(
      method = "setupSpawn(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/world/level/ServerWorldProperties;ZZLnet/minecraft/world/chunk/ChunkLoadProgress;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private static void overrideWorldSpawn(
      ServerWorld world, ServerWorldProperties worldProperties, boolean bonusChest, boolean debugWorld, ChunkLoadProgress loadProgress, CallbackInfo ci
   ) {
      if (world.getRegistryKey().equals(World.OVERWORLD)
          && world.getChunkManager().getChunkGenerator().getBiomeSource() instanceof
             com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource source) {
         BlockPos spawnPos = source.spawnChoice().filter(choice -> choice.seed() == world.getSeed())
            .map(SpawnSelector::findSelectedSpawn).orElseGet(SpawnSelector::findSpawnBlockPos);
         worldProperties.setSpawnPoint(SpawnPoint.create(World.OVERWORLD, spawnPos, 0.0F, 0.0F));
         ci.cancel();
      }
   }
}

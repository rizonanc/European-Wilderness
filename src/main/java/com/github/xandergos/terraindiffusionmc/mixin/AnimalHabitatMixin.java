package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.worldgen.spawn.AnimalHabitat;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.SpawnRestriction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.ServerWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpawnRestriction.class)
abstract class AnimalHabitatMixin {
    @Inject(method = "canSpawn", at = @At("RETURN"), cancellable = true)
    private static void td$preferHabitat(EntityType<?> type, ServerWorldAccess world, SpawnReason reason,
                                        BlockPos pos, Random random, CallbackInfoReturnable<Boolean> result) {
        if (result.getReturnValueZ() && type.getSpawnGroup() == SpawnGroup.CREATURE
            && (reason == SpawnReason.NATURAL || reason == SpawnReason.CHUNK_GENERATION)
            && !AnimalHabitat.allows(type, world, pos, random)) result.setReturnValue(false);
    }
}

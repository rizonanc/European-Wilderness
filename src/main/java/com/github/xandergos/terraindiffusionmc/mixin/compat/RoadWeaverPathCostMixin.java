package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.roadweaver.RoadWeaverTerrain;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.shiroha233.roadweaver.pathfinding.impl.PathfindingHelper", remap = false)
abstract class RoadWeaverPathCostMixin {
    @Inject(method = "oceanFloorSampler", at = @At("RETURN"), cancellable = true)
    private static void td$localDepth(@Coerce Object cache, int x, int z, ServerWorld world,
            CallbackInfoReturnable<Integer> result) {
        var session = RoadWeaverTerrain.session(world);
        if (session == null) return;
        int sea = world.getSeaLevel();
        int water = RoadWeaverTerrain.waterFirstAir(session, x, z, sea);
        if (water > sea) {
            // Road Weaver's route costs subtract this value from sea level.
            result.setReturnValue(RoadWeaverTerrain.floorRelativeToSea(result.getReturnValueI(), water, sea));
        }
    }
}

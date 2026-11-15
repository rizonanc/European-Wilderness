package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.github.xandergos.terraindiffusionmc.compat.roadweaver.RoadWeaverTerrain;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.shiroha233.roadweaver.pathfinding.impl.PathPostProcessor", remap = false)
abstract class RoadWeaverSmoothingMixin {
    @ModifyExpressionValue(
            method = "detectBridgeMask",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/shiroha233/roadweaver/pathfinding/cache/AccurateHeightSampler;oceanFloorWg(II)I"))
    private static int td$compareBridgeDepth(
            int original, @Local(argsOnly = true) ServerWorld world, @Local(index = 10) BlockPos pos) {
        return RoadWeaverTerrain.bridgeComparisonFloor(world, pos.getX(), pos.getZ(), original);
    }
}

package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.roadweaver.RoadWeaverTerrain;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.shiroha233.roadweaver.features.path.pathlogic.pathfinding.RoadPathCalculator", remap = false)
abstract class RoadWeaverSpansMixin {
    @ModifyExpressionValue(
            method = "extractSpans",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/shiroha233/roadweaver/pathfinding/cache/AccurateHeightSampler;oceanFloorWg(II)I"))
    private static int td$compareBridgeDepth(
            int original, @Local(argsOnly = true) ServerWorld world, @Local(index = 12) BlockPos pos) {
        return RoadWeaverTerrain.bridgeComparisonFloor(world, pos.getX(), pos.getZ(), original);
    }
}

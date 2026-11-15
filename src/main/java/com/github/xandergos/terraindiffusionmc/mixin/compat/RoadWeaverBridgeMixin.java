package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.roadweaver.RoadWeaverTerrain;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.PreparedWaterArea;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = "net.shiroha233.roadweaver.features.path.pathlogic.bridge.BridgeSegmentPlanner", remap = false)
abstract class RoadWeaverBridgeMixin {
    @ModifyVariable(method = "processSegment", at = @At("HEAD"), argsOnly = true, index = 7)
    private static int td$localDeckTarget(
            int target,
            @Local(argsOnly = true) StructureWorldAccess access,
            @Local(argsOnly = true, ordinal = 0) BlockPos center) {
        var world = access.toServerWorld();
        var session = RoadWeaverTerrain.session(world);
        if (session == null) return target;
        int sea = world.getSeaLevel();
        int water = RoadWeaverTerrain.riverFirstAir(session, center.getX(), center.getZ());
        return water > sea ? target + (water - sea) : Math.max(target, center.getY());
    }

    @ModifyExpressionValue(
            method = "processSegment",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/shiroha233/roadweaver/features/path/pathlogic/bridge/BridgeSegmentPlanner;clampDeckY(ILjava/lang/Integer;ILnet/shiroha233/roadweaver/config/ModConfig;ZZ)I"))
    private static int td$keepDeckAboveRiver(
            int deck,
            @Local(argsOnly = true) StructureWorldAccess access,
            @Local(argsOnly = true, ordinal = 0) BlockPos center) {
        var world = access.toServerWorld();
        var session = RoadWeaverTerrain.session(world);
        if (session == null) return deck;
        int water = RoadWeaverTerrain.riverFirstAir(session, center.getX(), center.getZ());
        return Math.max(deck, water == PreparedWaterArea.NONE ? center.getY() : water);
    }
}

package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.*;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper", remap = false)
public abstract class VoxyWaterMipMixin {
    // One post-pass per section; no per-voxel injection callbacks.
    @Inject(method = "mipSection", at = @At("TAIL"), remap = false)
    private static void ewh$preserveWater(VoxelizedSection section, Mapper mapper, CallbackInfo ci) {
        var session = WorldSession.current();
        if (session == null || !session.isActive()) return;
        if ((Object) mapper instanceof WaterMipMapping mapping) WaterSurfaceMip.correct(section.section, mapping);
    }
}

package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.VoxyWaterFrameDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer", remap = false)
public abstract class VoxyWaterFrameMixin {
    @Inject(
            method = "renderTranslucent(Lme/cortex/voxy/client/core/rendering/section/backend/mdic/MDICViewport;)V",
            at = @At("TAIL"),
            remap = false)
    private void td$inspectWater(@Coerce Object viewport, CallbackInfo ci) {
        VoxyWaterFrameDiagnostics.capture(this, viewport);
    }
}

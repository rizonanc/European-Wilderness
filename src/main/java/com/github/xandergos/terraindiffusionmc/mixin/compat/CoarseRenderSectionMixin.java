package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.*;
import me.cortex.voxy.common.world.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderGenerationService", remap = false)
public abstract class CoarseRenderSectionMixin implements VoxyRenderAccess {
    @Shadow(remap = false)
    @Final
    private WorldEngine world;

    public Object ewh$engine() {
        return world;
    }

    @Inject(method = "acquireSection", at = @At("RETURN"), cancellable = true, remap = false)
    private void ewh$coarse(long pos, CallbackInfoReturnable<WorldSection> cir) {
        CoarseRenderHooks.observeService(world, this);
        var original = cir.getReturnValue();
        var preview = (WorldSection) CoarseRenderHooks.render(world, pos, original);
        if (preview != original && original != null) original.release();
        cir.setReturnValue(preview);
    }
}

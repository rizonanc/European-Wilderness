package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.*;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager",remap=false)
public abstract class CoarseChildMixin {
    @Unique private Object ewh$engine;
    @Inject(method="<init>",at=@At("TAIL"),remap=false)
    private void ewh$bind(int max,IGeometryData geometry,RenderGenerationService renderer,CallbackInfo ci){ewh$engine=((VoxyRenderAccess)renderer).ewh$engine();}
    @Redirect(method="run",at=@At(value="INVOKE",target="Lme/cortex/voxy/common/world/WorldSection;getNonEmptyChildren()B"),remap=false)
    private byte ewh$children(WorldSection section){return CoarseRenderHooks.children(ewh$engine,section,section.getNonEmptyChildren());}
}

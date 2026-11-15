package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseRenderHooks;
import me.cortex.voxy.common.world.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderDataFactory", remap = false)
public abstract class CoarseNeighborMixin {
    @Redirect(
            method = "acquireNeighborData",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lme/cortex/voxy/common/world/WorldEngine;acquire(IIII)Lme/cortex/voxy/common/world/WorldSection;"),
            require = 6,
            remap = false)
    private WorldSection ewh$neighbor(WorldEngine engine, int level, int x, int y, int z) {
        var original = engine.acquire(level, x, y, z);
        var preview = (WorldSection)
                CoarseRenderHooks.neighbor(engine, WorldEngine.getWorldSectionId(level, x, y, z), original);
        if (preview != original) original.release();
        return preview;
    }
}

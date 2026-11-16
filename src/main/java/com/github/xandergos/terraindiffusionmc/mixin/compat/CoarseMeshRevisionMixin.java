package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseRenderHooks;
import me.cortex.voxy.client.core.rendering.building.*;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

/** Reject only TD virtual snapshots retired while Voxy was baking their models/mesh. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderGenerationService", remap = false)
public abstract class CoarseMeshRevisionMixin {
    @Shadow(remap = false)
    public abstract void enqueueTask(long position);

    @Redirect(
            method = "processJob",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lme/cortex/voxy/client/core/rendering/building/RenderDataFactory;generateMesh(Lme/cortex/voxy/common/world/WorldSection;)Lme/cortex/voxy/client/core/rendering/building/BuiltSection;"),
            remap = false)
    private BuiltSection td$currentMesh(RenderDataFactory factory, WorldSection section) {
        if (!CoarseRenderHooks.meshCurrent(section)) {
            CoarseRenderHooks.meshFinished(section);
            enqueueTask(section.key);
            return null;
        }
        // On IdNotYetComputedException Voxy keeps this snapshot; keep its stamp as well.
        BuiltSection mesh = factory.generateMesh(section);
        boolean current = CoarseRenderHooks.meshCurrent(section);
        if (current) CoarseRenderHooks.copyMeshStamp(section, mesh);
        CoarseRenderHooks.meshFinished(section);
        if (!current) {
            if (mesh != null) mesh.free();
            enqueueTask(section.key);
            return null;
        }
        return mesh;
    }

    @Redirect(
            method = "processJob",
            at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"),
            require = 2,
            remap = false)
    @SuppressWarnings("unchecked")
    private void td$publishCurrent(java.util.function.Consumer<Object> consumer, Object result) {
        if (CoarseRenderHooks.meshCurrent(result)) {
            CoarseRenderHooks.meshFinished(result);
            consumer.accept(result);
        } else {
            CoarseRenderHooks.meshFinished(result);
            var mesh = (BuiltSection) result;
            long position = mesh.position;
            mesh.free();
            enqueueTask(position);
        }
    }
}

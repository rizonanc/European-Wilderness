package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.registry.tag.FluidTags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** The ingest worker must not read a section that keeps changing after enqueue. */
@Pseudo
@Mixin(targets="me.cortex.voxy.common.world.service.VoxelIngestService$IngestSection",remap=false)
public abstract class VoxyIngestSnapshotMixin {
    @ModifyVariable(method="<init>",at=@At("HEAD"),argsOnly=true,remap=false)
    private static ChunkSection td$snapshotSection(ChunkSection section) {
        var session=WorldSession.current();
        return session!=null&&session.isActive()&&section.hasAny(state->state.getFluidState().isIn(FluidTags.WATER))
            ?section.copy():section;
    }
}

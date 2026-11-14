package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.WorldGenCompat;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A solo integrated client already ingests its real chunks; do not serialize them a second time. */
@Pseudo
@Mixin(targets="com.ethan.voxyworldgenv2.event.ServerEventHandler",remap=false)
public abstract class WorldGenLoadedChunkMixin {
    @Inject(method="onChunkLoad",at=@At("HEAD"),cancellable=true,remap=false)
    private static void ewh$skipDuplicate(ServerWorld world,WorldChunk chunk,CallbackInfo ci){if(WorldGenCompat.skipDuplicateBroadcast(world)) ci.cancel();}
}

package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.WorldGenCompat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.MinecraftServer;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.ethan.voxyworldgenv2.core.ChunkGenerationManager", remap = false)
public abstract class WorldGenPauseMixin {
    @Shadow(remap = false)
    public abstract void setPauseCheck(BooleanSupplier check);

    @ModifyVariable(method = "setPauseCheck", at = @At("HEAD"), argsOnly = true, remap = false)
    private BooleanSupplier ewh$combinePause(BooleanSupplier original) {
        return () -> original.getAsBoolean() || WorldGenCompat.pauseNative();
    }

    @Inject(method = "initialize", at = @At("TAIL"), remap = false)
    private void ewh$worldReady(MinecraftServer server, CallbackInfo ci) {
        WorldGenCompat.serverStarted(server);
        setPauseCheck(() -> MinecraftClient.getInstance().isPaused());
    }
}

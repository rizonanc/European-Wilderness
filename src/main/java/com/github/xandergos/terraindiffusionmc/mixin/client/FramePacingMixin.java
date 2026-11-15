package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.client.ClientPerformance;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class FramePacingMixin {
    @Unique
    private long ewh$frameStart;

    @Inject(method = "render", at = @At("HEAD"))
    private void ewh$begin(boolean tick, CallbackInfo ci) {
        ewh$frameStart = System.nanoTime();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void ewh$finish(boolean tick, CallbackInfo ci) {
        ClientPerformance.frame(ewh$frameStart, System.nanoTime());
    }
}

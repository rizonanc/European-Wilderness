package com.github.xandergos.terraindiffusionmc.mixin.client;

import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.chunkypregen.ChunkyPregenClient", remap = false)
abstract class ChunkyRenderDistanceMixin {
    @Inject(method = "triggerRdCycle()V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void terrainDiffusion$preserveRenderDistance(CallbackInfo ci) {
        FabricLoader.getInstance()
                .getModContainer("chunkypregen")
                .filter(mod -> mod.getMetadata().getVersion().getFriendlyString().equals("2.2.0"))
                .ifPresent(mod -> ci.cancel());
    }
}

package com.github.xandergos.terraindiffusionmc.mixin.decoration;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.impl.resource.pack.ModNioPackResources;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.resource.ResourceType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ModNioPackResources.class, remap = false)
abstract class GeophilicResourcePackMixin {
    @Shadow
    @Final
    private ModContainer mod;

    @Shadow
    @Final
    private Map<ResourceType, Set<String>> namespaces;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void terrainDiffusion$keepGeophilicFeatures(CallbackInfo ci) {
        if (!mod.getMetadata().getId().equals("geophilic")) return;
        // Geophilic puts its vanilla biome replacements in minecraft, including in overlay packs.
        namespaces.computeIfPresent(
                ResourceType.SERVER_DATA,
                (type, names) -> names.stream()
                        .filter(name -> !name.equals("minecraft"))
                        .collect(Collectors.toUnmodifiableSet()));
    }
}

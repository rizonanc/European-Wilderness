package com.github.xandergos.terraindiffusionmc.mixin.decoration;

import net.fabricmc.fabric.impl.biome.modification.BiomeSelectionContextImpl;
import net.minecraft.registry.DynamicRegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BiomeSelectionContextImpl.class, remap = false)
public interface BiomeSelectionRegistries {
    @Accessor("dynamicRegistries")
    DynamicRegistryManager terrainDiffusion$getRegistries();
}

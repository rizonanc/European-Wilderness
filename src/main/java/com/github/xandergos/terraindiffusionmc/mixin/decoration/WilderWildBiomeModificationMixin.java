package com.github.xandergos.terraindiffusionmc.mixin.decoration;

import com.github.xandergos.terraindiffusionmc.compat.decoration.OptionalBiomeDecorations;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.biome.v1.BiomeModification;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = BiomeModification.class, remap = false)
abstract class WilderWildBiomeModificationMixin {
    @Shadow @Final private Identifier id;

    @ModifyVariable(method = {
        "add(Lnet/fabricmc/fabric/api/biome/v1/ModificationPhase;Ljava/util/function/Predicate;Ljava/util/function/Consumer;)Lnet/fabricmc/fabric/api/biome/v1/BiomeModification;",
        "add(Lnet/fabricmc/fabric/api/biome/v1/ModificationPhase;Ljava/util/function/Predicate;Ljava/util/function/BiConsumer;)Lnet/fabricmc/fabric/api/biome/v1/BiomeModification;"
    }, at = @At("HEAD"), argsOnly = true, require = 2)
    private Predicate<BiomeSelectionContext> terrainDiffusion$keepSelectedDecorations(Predicate<BiomeSelectionContext> selector) {
        if (!id.getNamespace().equals("wilderwild")) return selector;
        return context -> !OptionalBiomeDecorations.isTerrainDiffusion(context) && selector.test(context);
    }
}

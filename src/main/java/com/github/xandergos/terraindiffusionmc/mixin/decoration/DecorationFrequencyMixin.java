package com.github.xandergos.terraindiffusionmc.mixin.decoration;

import com.github.xandergos.terraindiffusionmc.compat.decoration.OptionalBiomeDecorations;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.feature.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlacedFeature.class)
abstract class DecorationFrequencyMixin {
    @Inject(method = "generate(Lnet/minecraft/world/StructureWorldAccess;Lnet/minecraft/world/gen/chunk/ChunkGenerator;Lnet/minecraft/util/math/random/Random;Lnet/minecraft/util/math/BlockPos;)Z",
        at = @At("HEAD"), cancellable = true)
    private void terrainDiffusion$limitDecorations(StructureWorldAccess world, ChunkGenerator generator, Random random,
                                                   BlockPos origin, CallbackInfoReturnable<Boolean> result) {
        if (!OptionalBiomeDecorations.hasFrequencyOverride()
                || !(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return;
        var id = world.getRegistryManager().getOrThrow(RegistryKeys.PLACED_FEATURE).getId((PlacedFeature)(Object)this);
        if (id == null) return;
        int frequency = OptionalBiomeDecorations.frequencyFor(id);
        if (frequency < 100 && Math.floorMod(HashCommon.mix(world.getSeed() ^ origin.asLong() ^ id.hashCode()), 100) >= frequency)
            result.setReturnValue(false);
    }
}

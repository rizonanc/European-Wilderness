package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biome.Precipitation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class BiomeMixin {
    @Shadow
    public abstract float getTemperature();

    @Shadow
    public abstract boolean hasPrecipitation();

    @Inject(
            method =
                    "getPrecipitation(Lnet/minecraft/util/math/BlockPos;I)Lnet/minecraft/world/biome/Biome$Precipitation;",
            at = @At("HEAD"),
            cancellable = true)
    private void preventHighAltitudeSnow(BlockPos pos, int seaLevel, CallbackInfoReturnable<Precipitation> cir) {
        if (!this.hasPrecipitation()) {
            cir.setReturnValue(Precipitation.NONE);
        } else {
            if (this.getTemperature() >= 0.15F) {
                cir.setReturnValue(Precipitation.RAIN);
            }
        }
    }
}

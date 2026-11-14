package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.client.TerrainWeather;
import net.minecraft.client.render.WeatherRendering;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WeatherRendering.class)
abstract class MountainWeatherMixin {
    @Inject(method = "getPrecipitationAt", at = @At("RETURN"), cancellable = true)
    private void td$snowAtAltitude(World world, BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> result) {
        if (result.getReturnValue() == Biome.Precipitation.RAIN && TerrainWeather.applies(world)
            && TerrainWeather.snow(pos.getY() - world.getSeaLevel(), world.getBiome(pos).value().getTemperature())) {
            result.setReturnValue(Biome.Precipitation.SNOW);
        }
    }

    @org.spongepowered.asm.mixin.injection.ModifyArg(method = "createSnowPiece",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WeatherRendering$Piece;<init>(IIIIFFI)V"), index = 4)
    private float td$windDrift(float offset) {
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (!TerrainWeather.applies(client.world) || client.player == null) return offset;
        float height = Math.clamp(((float)client.player.getY() - client.world.getSeaLevel() - 150) / 220, 0, 1);
        return offset + height * ((client.world.getTime() % 1600) / 40F);
    }
}

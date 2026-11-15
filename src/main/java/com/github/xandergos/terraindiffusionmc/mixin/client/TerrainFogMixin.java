package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.client.TerrainWeather;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(FogRenderer.class)
abstract class TerrainFogMixin {
    @ModifyArgs(
            method =
                    "applyFog(Lnet/minecraft/client/render/Camera;ILnet/minecraft/client/render/RenderTickCounter;FLnet/minecraft/client/world/ClientWorld;)Lorg/joml/Vector4f;",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/client/render/fog/FogRenderer;applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void td$valleyFog(
            Args args, Camera camera, int distance, RenderTickCounter ticks, float sky, ClientWorld world) {
        if (!TerrainWeather.applies(world) || camera.getSubmersionType() != CameraSubmersionType.NONE) return;
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris")) return;
        var pos = camera.getBlockPos();
        if (pos.getY() < world.getSeaLevel() || pos.getY() > world.getSeaLevel() + 95) return;
        int ground = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ());
        int clearance = pos.getY() - ground;
        if (clearance < -2 || clearance > 24) return;
        float relief = 0;
        int samples = 0;
        for (int dx = -32; dx <= 32; dx += 32)
            for (int dz = -32; dz <= 32; dz += 32) {
                if (dx == 0 && dz == 0 || !world.isChunkLoaded((pos.getX() + dx) >> 4, (pos.getZ() + dz) >> 4))
                    continue;
                relief +=
                        Math.max(
                                0,
                                world.getTopY(
                                                Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                                                pos.getX() + dx,
                                                pos.getZ() + dz)
                                        - ground);
                samples++;
            }
        if (samples < 4) return;
        float strength = Math.clamp((relief / samples - 4) / 24, 0, 1) * (1 - Math.max(0, clearance) / 24F);
        strength *= 0.65F + 0.35F * world.getRainGradient(1);
        float end = args.get(4);
        if (end <= 72 || strength < 0.12F) return;
        float shortened = Math.min(end, 72 + (1 - strength) * 220);
        args.set(4, shortened);
        args.set(3, Math.min((float) args.get(3), shortened * 0.27F));
    }
}

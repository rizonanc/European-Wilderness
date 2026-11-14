package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.client.WorldScaleSettingsScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.WorldCreator;
import net.minecraft.client.gui.screen.world.WorldCreator.WorldType;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.WorldPreset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(targets = "net/minecraft/client/gui/screen/world/CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenWorldTabMixin {
   @Shadow
   @Final
   CreateWorldScreen field_42182;
   @Shadow
   private ButtonWidget customizeButton;
   private static final RegistryKey<WorldPreset> TERRAIN_DIFFUSION_PRESET_KEY = RegistryKey.of(
      RegistryKeys.WORLD_PRESET, Identifier.of("terrain-diffusion-mc", "terrain_diffusion")
   );

   @Inject(method = "method_48676(Lnet/minecraft/client/gui/screen/world/WorldCreator;)V", at = @At("TAIL"))
   private void terrainDiffusionMc$enableCustomizeButtonForTerrainDiffusion(WorldCreator worldCreator, CallbackInfo callbackInfo) {
      if (this.isTerrainDiffusionWorldTypeSelected()) {
         this.customizeButton.active = true;
      }
   }

   @Inject(method = "method_48680()Z", at = @At("HEAD"), cancellable = true)
   private void terrainDiffusionMc$forceCustomizeAvailable(CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
      if (this.isTerrainDiffusionWorldTypeSelected()) {
         callbackInfoReturnable.setReturnValue(true);
      }
   }

   @Inject(method = "method_48681()Z", at = @At("HEAD"), cancellable = true)
   private void terrainDiffusionMc$forceCustomizeVisible(CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
      if (this.isTerrainDiffusionWorldTypeSelected()) {
         callbackInfoReturnable.setReturnValue(true);
      }
   }

   @Inject(method = "openCustomizeScreen()V", at = @At("HEAD"), cancellable = true)
   private void terrainDiffusionMc$openTerrainScaleScreen(CallbackInfo callbackInfo) {
      if (this.isTerrainDiffusionWorldTypeSelected()) {
         MinecraftClient minecraftClient = MinecraftClient.getInstance();
         if (minecraftClient != null) {
            minecraftClient.setScreen(new WorldScaleSettingsScreen(this.field_42182));
            callbackInfo.cancel();
         }
      }
   }

   private boolean isTerrainDiffusionWorldTypeSelected() {
      WorldCreator worldCreator = this.field_42182.getWorldCreator();
      if (worldCreator == null) {
         return false;
      } else {
         WorldType worldType = worldCreator.getWorldType();
         if (worldType == null) {
            return false;
         } else {
            return TERRAIN_DIFFUSION_PRESET_KEY.equals(worldType.preset()) ? true : "terrain diffusion".equalsIgnoreCase(worldType.getName().getString());
         }
      }
   }
}

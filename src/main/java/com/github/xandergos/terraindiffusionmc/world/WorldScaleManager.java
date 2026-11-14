package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfigEditor;
import java.io.IOException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;
import com.github.xandergos.terraindiffusionmc.pipeline.ModelAssetManager;
import com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.GenerationProfileState;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.ProfileCompatibility;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldGenerationOptions;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WaterGenerationOptions;
import net.minecraft.server.world.ServerWorld;

public final class WorldScaleManager {
   public static final int DEFAULT_SCALE = 2;
   private static final int MIN_SCALE = 1;
   public static final int MAX_SCALE = 6;
   private static volatile int currentScale = 2;

   private WorldScaleManager() {
   }

   public static void initializeForWorld(ServerWorld serverWorld) {
      WorldScaleSettingsState worldScaleSettingsState = serverWorld.getPersistentStateManager().getOrCreate(WorldScaleSettingsState.TYPE);
      if (!worldScaleSettingsState.hasExplicitScale()) {
         Integer pendingScale = WorldScaleSelectionState.consumePendingScale();
         int resolvedScale = pendingScale != null ? pendingScale : 2;
         worldScaleSettingsState.setScale(resolvedScale);
      }

      currentScale = clampScale(worldScaleSettingsState.getScale());
      var states = serverWorld.getPersistentStateManager();
      if (states.get(GenerationProfileState.TYPE) == null) {
         var source = serverWorld.getChunkManager().getChunkGenerator().getBiomeSource();
         var options = source instanceof TerrainDiffusionBiomeSource terrain
            ? terrain.creationOptions().orElseGet(WorldScaleManager::currentWorldOptions) : currentWorldOptions();
         GenerationProfileState.offerDefaults(currentScale, ModelAssetManager.getModelRevision(),
            TerrainDiffusionConfig.inferenceDevice(), OnnxModel.getResolvedInferenceProvider(), options);
      }
      states.getOrCreate(GenerationProfileState.TYPE).bindLegacyWaterOptions(WaterGenerationOptions.configured());
   }

   private static WorldGenerationOptions currentWorldOptions() {
      var config = FabricLoader.getInstance().getConfigDir().resolve("terrain-diffusion-mc.properties");
      try {
         return WorldGenerationOptions.fromSettings(TerrainDiffusionConfigEditor.load(config));
      } catch (IOException | IllegalArgumentException failure) {
         LoggerFactory.getLogger(WorldScaleManager.class).warn("Could not read world generation settings from {}", config, failure);
         return WorldGenerationOptions.configured();
      }
   }

   public static int getCurrentScale() {
      return currentScale;
   }

   public static void setCurrentScale(ServerWorld serverWorld, int configuredScale) {
      int clampedScale = clampScale(configuredScale);
      GenerationProfileState stored = serverWorld.getPersistentStateManager().get(GenerationProfileState.TYPE);
      if (stored != null) {
         ProfileCompatibility.checkScaleChangeAllowed(stored.toProfile(), clampedScale);
      }

      WorldScaleSettingsState worldScaleSettingsState = serverWorld.getPersistentStateManager().getOrCreate(WorldScaleSettingsState.TYPE);
      worldScaleSettingsState.setScale(clampedScale);
      currentScale = clampedScale;
   }

   public static int clampScale(int configuredScale) {
      return Math.max(1, Math.min(6, configuredScale));
   }
}

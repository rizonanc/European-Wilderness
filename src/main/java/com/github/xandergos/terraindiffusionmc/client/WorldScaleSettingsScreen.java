package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.config.SettingsPreferences;
import com.github.xandergos.terraindiffusionmc.explorer.ExplorerServer;
import com.github.xandergos.terraindiffusionmc.world.SpawnChoice;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfigEditor;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleSelectionState;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldGenerationOptions;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry.Reference;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

@Environment(EnvType.CLIENT)
public final class WorldScaleSettingsScreen extends Screen {
   private static final String MOD_ID = "terrain-diffusion-mc";
   private static final int TEXT_FIELD_WIDTH = 80;
   private static final int TEXT_FIELD_HEIGHT = 20;
   private static final int BUTTON_WIDTH = 80;
   private static final int BUTTON_HEIGHT = 20;
   private static final Text LABEL_TEXT = Text.literal("World Scale");
   private static final Text DESCRIPTION_TEXT = Text.literal("Enter an integer value (1-6)");
   private static final Text ERROR_TEXT = Text.literal("Scale must be an integer between 1 and 6").formatted(Formatting.RED);
   private final Screen parentScreen;
   private TextFieldWidget scaleTextField;
   private TextWidget validationTextWidget;
   private String rawScale = String.valueOf(WorldScaleSelectionState.getPendingScaleOrDefault());
   private WorldGenerationOptions generationOptions;
   private ExplorerServer.SpawnPicker spawnPicker;
   private SpawnChoice spawnChoice;
   private Text profileName = Text.translatable("terrain-diffusion-mc.settings.profile_current");

   public WorldScaleSettingsScreen(Screen parentScreen) {
      super(Text.translatable("terrain-diffusion-mc.world_settings.title"));
      this.parentScreen = parentScreen;
      if (parentScreen instanceof CreateWorldScreen create) {
         var overworld = create.getWorldCreator().getGeneratorOptionsHolder().selectedDimensions().getOrEmpty(DimensionOptions.OVERWORLD);
         if (overworld.isPresent() && overworld.get().chunkGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource source) {
            generationOptions = source.creationOptions().orElse(null);
            spawnChoice = source.spawnChoice().orElse(null);
            if (generationOptions != null) profileName = Text.translatable("terrain-diffusion-mc.settings.profile_configured");
         }
      }
   }

   @Override
   protected void init() {
      int centerX = this.width / 2;
      int centerY = this.height / 2 - 14;
      this.addCenteredTextWidget(this.title, centerX, Math.max(4, centerY - 50), 16777215);
      this.addCenteredTextWidget(DESCRIPTION_TEXT, centerX, centerY - 34, 11184810);
      this.addCenteredTextWidget(LABEL_TEXT, centerX, centerY - 22, 16777215);
      this.scaleTextField = new TextFieldWidget(this.textRenderer, centerX - 40, centerY - 10, 80, 20, LABEL_TEXT);
      this.scaleTextField.setText(rawScale);
      this.scaleTextField.setChangedListener(value -> {
         rawScale = value;
         this.validationTextWidget.setMessage(Text.empty());
      });
      this.addDrawableChild(this.scaleTextField);
      this.setInitialFocus(this.scaleTextField);
      this.addDrawableChild(ButtonWidget.builder(
         Text.translatable("terrain-diffusion-mc.settings.world_profile", profileName), b -> openProfiles()
      ).dimensions(centerX - 120, centerY + 14, 240, 20).build());
      addDrawableChild(ButtonWidget.builder(spawnText("open"), b -> openSpawnPicker())
         .dimensions(centerX - 120, centerY + 38, 240, 20).build());
      this.addDrawableChild(
         ButtonWidget.builder(Text.translatable("gui.done"), b -> this.onDonePressed()).dimensions(centerX - 85, centerY + 62, 80, 20).build()
      );
      this.addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> this.close()).dimensions(centerX + 5, centerY + 62, 80, 20).build());
      this.validationTextWidget = new TextWidget(0, centerY + 86, this.width, 9, Text.empty(), this.textRenderer);
      this.addDrawableChild(this.validationTextWidget);
   }

   private static MutableText spawnText(String key, Object... args) {
      return Text.translatable("terrain-diffusion-mc.world_settings.spawn." + key, args);
   }

   private void openSpawnPicker() {
      if (!(parentScreen instanceof CreateWorldScreen create)) return;
      try {
         int scale = Integer.parseInt(rawScale.trim());
         if (scale < 1 || scale > 6) { validationTextWidget.setMessage(ERROR_TEXT); return; }
         var creator = create.getWorldCreator();
         long seed = net.minecraft.world.gen.GeneratorOptions.parseSeed(creator.getSeed())
            .orElseGet(() -> creator.getGeneratorOptionsHolder().generatorOptions().getSeed());
         int port = ExplorerServer.startIfNotRunning();
         spawnPicker = ExplorerServer.beginSpawnPicker(seed, scale);
         net.minecraft.util.Util.getOperatingSystem().open("http://127.0.0.1:" + port + "/?picker=" + spawnPicker.token);
         validationTextWidget.setMessage(spawnText("opened"));
      } catch (NumberFormatException invalid) {
         validationTextWidget.setMessage(ERROR_TEXT);
      } catch (IOException failure) {
         validationTextWidget.setMessage(spawnText("error").formatted(Formatting.RED));
      }
   }

   @Override public void tick() {
      if (spawnPicker != null && spawnPicker.choice() != null && spawnPicker.choice() != spawnChoice) {
         spawnChoice = spawnPicker.choice();
         validationTextWidget.setMessage(spawnText("selected", spawnChoice.x(), spawnChoice.z()));
      }
   }

   private void openProfiles() {
      try {
         var settings = TerrainDiffusionConfigEditor.load(TerrainDiffusionConfig.configPath());
         var prefs = SettingsPreferences.load(TerrainDiffusionConfig.menuPath());
         client.setScreen(new SettingsProfileScreen(this, prefs, null, (label, values) -> {
            var merged = new HashMap<>(settings);
            merged.putAll(values);
            generationOptions = WorldGenerationOptions.fromSettings(merged);
            profileName = label;
         }));
      } catch (IOException failure) {
         validationTextWidget.setMessage(Text.translatable("terrain-diffusion-mc.settings.preferences_error").formatted(Formatting.RED));
      }
   }

   private void addCenteredTextWidget(Text text, int centerX, int y, int color) {
      int textWidth = this.textRenderer.getWidth(text);
      MutableText coloredText = text.copy().styled(style -> style.withColor(color));
      TextWidget widget = new TextWidget(centerX - textWidth / 2, y, textWidth, 9, coloredText, this.textRenderer);
      this.addDrawableChild(widget);
   }

   @Override
   public void close() {
      if (this.client != null) {
         if (spawnPicker != null) ExplorerServer.endSpawnPicker(spawnPicker);
         this.client.setScreen(this.parentScreen);
      }
   }

   private void onDonePressed() {
      String rawScaleValue = this.scaleTextField.getText().trim();
      if (rawScaleValue.isEmpty()) {
         this.validationTextWidget.setMessage(ERROR_TEXT);
      } else {
         try {
            int selectedScale = Integer.parseInt(rawScaleValue);
            if (selectedScale < 1 || selectedScale > 6) {
               this.validationTextWidget.setMessage(ERROR_TEXT);
               return;
            }

            if (spawnPicker != null && spawnPicker.choice() != null) spawnChoice = spawnPicker.choice();
            if (spawnChoice != null && parentScreen instanceof CreateWorldScreen create) {
               var creator = create.getWorldCreator();
               var explicit = net.minecraft.world.gen.GeneratorOptions.parseSeed(creator.getSeed());
               if (explicit.isEmpty()) creator.setSeed(Long.toString(spawnChoice.seed()));
               else if (explicit.getAsLong() != spawnChoice.seed()) spawnChoice = null;
            }
            this.applyWorldHeightForScale(selectedScale);
            WorldScaleSelectionState.setPendingScale(selectedScale);
            this.close();
         } catch (NumberFormatException exception) {
            this.validationTextWidget.setMessage(ERROR_TEXT);
         }
      }
   }

   private void applyWorldHeightForScale(int selectedScale) {
      if (this.parentScreen instanceof CreateWorldScreen createWorldScreen) {
         createWorldScreen.getWorldCreator()
            .applyModifier(
               (registryManager, selectedDimensions) -> {
                  DimensionOptionsRegistryHolder updatedDimensions = this.updateOverworldDimensionType(
                     registryManager.getOrThrow(RegistryKeys.DIMENSION_TYPE), selectedDimensions, selectedScale
                  );
                  return updatedDimensions == null ? selectedDimensions : updatedDimensions;
               }
            );
      }
   }

   private DimensionOptionsRegistryHolder updateOverworldDimensionType(
      Registry<DimensionType> dimensionTypeRegistry, DimensionOptionsRegistryHolder selectedDimensions, int selectedScale
   ) {
      DimensionOptions overworldOptions = selectedDimensions.getOrEmpty(DimensionOptions.OVERWORLD).orElse(null);
      if (overworldOptions == null) {
         return null;
      }

      Identifier dimensionTypeId = Identifier.of("terrain-diffusion-mc", "terrain_diffusion_scale_" + selectedScale);
      Reference<DimensionType> selectedDimensionTypeEntry = dimensionTypeRegistry.getEntry(dimensionTypeId).orElse(null);
      if (selectedDimensionTypeEntry == null) {
         return null;
      }

      var generator = overworldOptions.chunkGenerator();
      if (generator instanceof NoiseChunkGenerator noise && noise.getBiomeSource() instanceof TerrainDiffusionBiomeSource source) {
         if (generationOptions != null) source = source.withCreationOptions(generationOptions);
         generator = new NoiseChunkGenerator(source.withSpawnChoice(spawnChoice), noise.getSettings());
      }
      DimensionOptions updatedOverworldOptions = new DimensionOptions(selectedDimensionTypeEntry, generator);
      Map<RegistryKey<DimensionOptions>, DimensionOptions> updatedDimensionMap = new HashMap<>(selectedDimensions.dimensions());
      updatedDimensionMap.put(DimensionOptions.OVERWORLD, updatedOverworldOptions);
      return new DimensionOptionsRegistryHolder(updatedDimensionMap);
   }
}

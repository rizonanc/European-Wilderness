package com.github.xandergos.terraindiffusionmc.worldgen.settings;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

public final class GenerationProfileState extends PersistentState {
   private record RegionalOptions(boolean largeIslands, Optional<Integer> climateShift, boolean slopeClimate, boolean newLandforms) {
      static final com.mojang.serialization.MapCodec<RegionalOptions> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
         Codec.BOOL.optionalFieldOf("large_islands", false).forGetter(RegionalOptions::largeIslands),
         Codec.intRange(-8, 8).optionalFieldOf("climate_shift").forGetter(RegionalOptions::climateShift),
         Codec.BOOL.optionalFieldOf("slope_climate", false).forGetter(RegionalOptions::slopeClimate),
         Codec.BOOL.optionalFieldOf("new_landforms", false).forGetter(RegionalOptions::newLandforms)
      ).apply(instance, RegionalOptions::new));
   }

   static final Codec<GenerationProfileState> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
            Codec.INT.fieldOf("format_version").forGetter(s -> s.formatVersion),
            Codec.INT.fieldOf("generator_version").forGetter(s -> s.generatorVersion),
            Codec.INT.fieldOf("realism_level").forGetter(s -> s.realismLevel),
            Codec.INT.fieldOf("world_scale").forGetter(s -> s.worldScale),
            Codec.STRING.fieldOf("region").forGetter(s -> s.region),
            Codec.STRING.fieldOf("model_fingerprint").forGetter(s -> s.modelFingerprint),
            Codec.STRING.optionalFieldOf("inference_device", "unknown").forGetter(s -> s.inferenceDevice),
            Codec.STRING.optionalFieldOf("created_backend", "unknown").forGetter(s -> s.createdBackend),
            Codec.BOOL.optionalFieldOf("automatic_water", false).forGetter(s -> s.automaticWater),
            Codec.intRange(0,200).optionalFieldOf("mountain_relief",100).forGetter(s -> s.mountainRelief),
            Codec.intRange(0,100).optionalFieldOf("biome_variety",0).forGetter(s -> s.biomeVariety),
            WaterGenerationOptions.CODEC.optionalFieldOf("water_generation").forGetter(s -> s.water),
            Codec.BOOL.optionalFieldOf("larger_landscapes", false).forGetter(s -> s.largerLandscapes),
            BiomeBalance.CODEC.optionalFieldOf("biome_balance", BiomeBalance.DEFAULT).forGetter(s -> s.biomeBalance),
            Codec.BOOL.optionalFieldOf("tropical_landforms", false).forGetter(s -> s.tropicalLandforms),
            RegionalOptions.CODEC.forGetter(s -> s.regional)
         )
         .apply(instance, GenerationProfileState::new)
   );
   private static volatile GenerationProfileState.PendingDefaults pendingDefaults;
   public static final PersistentStateType<GenerationProfileState> TYPE = new PersistentStateType<>(
      "terrain_diffusion_generation_profile", GenerationProfileState::createDefault, CODEC, null
   );
   private final int formatVersion;
   private final int generatorVersion;
   private final int realismLevel;
   private final int worldScale;
   private final String region;
   private final String modelFingerprint;
   private final String inferenceDevice;
   private final String createdBackend;
   private final boolean automaticWater;
   private final int mountainRelief;
   private final int biomeVariety;
   private Optional<WaterGenerationOptions> water;
   private final boolean largerLandscapes;
   private final BiomeBalance biomeBalance;
   private final boolean tropicalLandforms;
   private final RegionalOptions regional;

   public boolean automaticWater() { return automaticWater; }

   public static void offerDefaults(int worldScale, String modelFingerprint, String inferenceDevice, String createdBackend,
                                    WorldGenerationOptions options) {
      pendingDefaults = new GenerationProfileState.PendingDefaults(worldScale, modelFingerprint, inferenceDevice, createdBackend, options);
   }

   public static GenerationProfileState createDefault() {
      GenerationProfileState.PendingDefaults offered = pendingDefaults;
      pendingDefaults = null;
      int scale = offered != null ? offered.worldScale() : 2;
      String model = offered != null ? offered.modelFingerprint() : "unknown";
      String device = offered != null ? offered.inferenceDevice() : "unknown";
      String backend = offered != null ? offered.createdBackend() : "unknown";
      var options = offered != null ? offered.options() : WorldGenerationOptions.configured();
      return new GenerationProfileState(2, 8, 7, scale, "europe", model, device, backend, true,
         options.mountainRelief(), options.biomeVariety(), Optional.of(options.water()), true, options.biomeBalance(), true, new RegionalOptions(true, Optional.of(options.climateShift()), true, true));
   }

   private GenerationProfileState(
      int formatVersion,
      int generatorVersion,
      int realismLevel,
      int worldScale,
      String region,
      String modelFingerprint,
      String inferenceDevice,
      String createdBackend,
      boolean automaticWater,
      int mountainRelief,
      int biomeVariety,
      Optional<WaterGenerationOptions> water,
      boolean largerLandscapes,
      BiomeBalance biomeBalance,
      boolean tropicalLandforms,
      RegionalOptions regional
   ) {
      this.formatVersion = formatVersion;
      this.generatorVersion = generatorVersion;
      this.realismLevel = realismLevel;
      this.worldScale = worldScale;
      this.region = region;
      this.modelFingerprint = modelFingerprint;
      this.inferenceDevice = inferenceDevice;
      this.createdBackend = createdBackend;
      this.automaticWater = automaticWater;
      this.mountainRelief = mountainRelief;
      this.biomeVariety = biomeVariety;
      this.water = water;
      this.largerLandscapes = largerLandscapes;
      this.biomeBalance = biomeBalance;
      this.tropicalLandforms = tropicalLandforms;
      this.regional = regional;
   }

   public void bindLegacyWaterOptions(WaterGenerationOptions options) {
      if (water.isEmpty()) {
         // Existing water plans predate the highland limit.
         water = Optional.of(new WaterGenerationOptions(options.widthVariation(), options.depthVariation(), options.lakeThreshold()));
         markDirty();
      }
   }

   public GenerationProfile toProfile() {
      if (this.formatVersion != 2) {
         throw new IllegalStateException(
            "World was saved with an unsupported generation profile format version "
               + this.formatVersion
               + " (this build reads 2); create a new world instead of migrating silently"
         );
      } else {
         return GenerationProfile.of(
            this.realismLevel,
            this.worldScale,
            this.region,
            this.formatVersion,
            this.generatorVersion,
            this.modelFingerprint,
            this.inferenceDevice,
            this.createdBackend
         ).withTerrainPreferences(mountainRelief, biomeVariety, water.orElse(WaterGenerationOptions.DEFAULT), largerLandscapes,
            biomeBalance, tropicalLandforms, regional.largeIslands(), regional.climateShift(), regional.slopeClimate(), regional.newLandforms());
      }
   }

   public int storedFormatVersion() {
      return this.formatVersion;
   }

   public int storedWorldScale() {
      return this.worldScale;
   }

   public String storedModelFingerprint() {
      return this.modelFingerprint;
   }

   private record PendingDefaults(int worldScale, String modelFingerprint, String inferenceDevice, String createdBackend,
                                  WorldGenerationOptions options) {
   }
}

package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldGenerationOptions;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.PerformanceProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.PreparedFallback;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.WorldView;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil.MultiNoiseSampler;

public class TerrainDiffusionBiomeSource extends BiomeSource {
   public static final RegistryKey<Biome> PALM_ISLAND = RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "palm_island"));
   private static final RegistryKey<Biome> FOREST_SPARSE = RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "forest_sparse"));
   private static final RegistryKey<Biome> TAIGA_SPARSE = RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "taiga_sparse"));
   private static final RegistryKey<Biome> SNOWY_TAIGA_SPARSE = RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "snowy_taiga_sparse"));
   public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec(
      instance -> instance.group(RegistryOps.getEntryLookupCodec(RegistryKeys.BIOME),
          WorldGenerationOptions.CODEC.optionalFieldOf("creation_options").forGetter(source -> source.creationOptions),
          SpawnChoice.CODEC.optionalFieldOf("spawn_choice").forGetter(source -> source.spawnChoice))
          .apply(instance, instance.stable(TerrainDiffusionBiomeSource::new))
   );
   private final RegistryEntryLookup<Biome> biomeLookup;
   private final Optional<WorldGenerationOptions> creationOptions;
   private final Optional<SpawnChoice> spawnChoice;
   private Map<Short, RegistryEntry<Biome>> biomeIdMap = null;
   private final ThreadLocal<BiomeColumnCache> biomeColumns = ThreadLocal.withInitial(BiomeColumnCache::new);

   public TerrainDiffusionBiomeSource(RegistryEntryLookup<Biome> biomeLookup) {
      this(biomeLookup, Optional.empty(), Optional.empty());
   }

   private TerrainDiffusionBiomeSource(RegistryEntryLookup<Biome> biomeLookup, Optional<WorldGenerationOptions> options, Optional<SpawnChoice> spawnChoice) {
      this.biomeLookup = biomeLookup;
      this.creationOptions = options;
      this.spawnChoice = spawnChoice;
   }

   public TerrainDiffusionBiomeSource withCreationOptions(WorldGenerationOptions options) {
      return new TerrainDiffusionBiomeSource(biomeLookup, Optional.of(options), spawnChoice);
   }

   public TerrainDiffusionBiomeSource withSpawnChoice(SpawnChoice choice) {
      return new TerrainDiffusionBiomeSource(biomeLookup, creationOptions, Optional.ofNullable(choice));
   }

   public Optional<SpawnChoice> spawnChoice() { return spawnChoice; }
   public Optional<WorldGenerationOptions> creationOptions() { return creationOptions; }

   @Override
   protected MapCodec<? extends BiomeSource> getCodec() {
      return CODEC;
   }

   private void requireBiomeIdMap() {
      if (this.biomeIdMap == null) {
         this.biomeIdMap = Map.ofEntries(
            Map.entry((short)1, this.biomeLookup.getOrThrow(BiomeKeys.PLAINS)),
            Map.entry((short)3, this.biomeLookup.getOrThrow(BiomeKeys.SNOWY_PLAINS)),
            Map.entry((short)5, this.biomeLookup.getOrThrow(BiomeKeys.DESERT)),
            Map.entry((short)6, this.biomeLookup.getOrThrow(BiomeKeys.SWAMP)),
            Map.entry((short)8, this.biomeLookup.getOrThrow(BiomeKeys.FOREST)),
            Map.entry((short)15, this.biomeLookup.getOrThrow(BiomeKeys.TAIGA)),
            Map.entry((short)16, this.biomeLookup.getOrThrow(BiomeKeys.SNOWY_TAIGA)),
            Map.entry((short)17, this.biomeLookup.getOrThrow(BiomeKeys.SAVANNA)),
            Map.entry((short)18, this.biomeLookup.getOrThrow(BiomeKeys.BEACH)),
            Map.entry((short)19, this.biomeLookup.getOrThrow(BiomeKeys.WINDSWEPT_HILLS)),
            Map.entry((short)23, this.biomeLookup.getOrThrow(BiomeKeys.JUNGLE)),
            Map.entry((short)26, this.biomeLookup.getOrThrow(BiomeKeys.BADLANDS)),
            Map.entry((short)29, this.biomeLookup.getOrThrow(BiomeKeys.MEADOW)),
            Map.entry((short)31, this.biomeLookup.getOrThrow(RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "grove")))),
            Map.entry((short)32, this.biomeLookup.getOrThrow(BiomeKeys.SNOWY_SLOPES)),
            Map.entry((short)33, this.biomeLookup.getOrThrow(BiomeKeys.FROZEN_PEAKS)),
            Map.entry((short)35, this.biomeLookup.getOrThrow(BiomeKeys.STONY_PEAKS)),
            Map.entry((short)41, this.biomeLookup.getOrThrow(BiomeKeys.WARM_OCEAN)),
            Map.entry((short)44, this.biomeLookup.getOrThrow(BiomeKeys.OCEAN)),
            Map.entry((short)46, this.biomeLookup.getOrThrow(BiomeKeys.COLD_OCEAN)),
            Map.entry((short)48, this.biomeLookup.getOrThrow(BiomeKeys.FROZEN_OCEAN)),
            Map.entry((short)215, this.biomeLookup.getOrThrow(RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "european_coniferous")))),
            Map.entry((short)216, this.biomeLookup.getOrThrow(RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "european_coniferous")))),
            Map.entry((short)208, this.biomeLookup.getOrThrow(RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "european_deciduous")))),
            Map.entry((short)209, this.biomeLookup.getOrThrow(RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "european_mixed")))),
            Map.entry((short)230, this.biomeLookup.getOrThrow(PALM_ISLAND)),
            Map.entry((short)229, this.biomeLookup.getOrThrow(RegistryKey.of(RegistryKeys.BIOME, Identifier.of("terrain-diffusion-mc", "european_meadow")))),
            Map.entry((short)108, this.biomeLookup.getOrThrow(FOREST_SPARSE)),
            Map.entry((short)115, this.biomeLookup.getOrThrow(TAIGA_SPARSE)),
            Map.entry((short)116, this.biomeLookup.getOrThrow(SNOWY_TAIGA_SPARSE))
         );
      }
   }

   @Override
   public Stream<RegistryEntry<Biome>> biomeStream() {
      this.requireBiomeIdMap();
      return this.biomeIdMap.values().stream();
   }

   @Override
   public RegistryEntry<Biome> getBiome(int x, int y, int z, MultiNoiseSampler noise) {
      this.requireBiomeIdMap();
      RegistryEntry<Biome> defaultEntry = this.biomeIdMap.get(1);
      int blockX = BiomeCoords.toBlock(x);
      int blockZ = BiomeCoords.toBlock(z);
      WorldSession session = WorldSession.current();
      WorldSession.requireActive(session);
      // This generator's biomes depend on x/z only. Minecraft asks for the same
      // columns again at every vertical biome cell, including in C2ME workers.
      // Never retain prepared-only fallback IDs: terrain may become ready later.
      short biomeId;
      if (PreparedFallback.mode() == PerformanceProfile.PreparationMode.ON_DEMAND) {
         var cache = biomeColumns.get();
         int cached = cache.get(session.id(), x, z);
         if (cached == BiomeColumnCache.MISSING) {
            biomeId = LocalTerrainProvider.getInstance().fetchBiomeId(blockX, blockZ);
            WorldSession.requireActive(session);
            cache.put(session.id(), x, z, biomeId);
         } else biomeId = (short)cached;
      } else {
         biomeId = LocalTerrainProvider.getInstance().fetchBiomeId(blockX, blockZ);
      }
      WorldSession.requireActive(session);
      RegistryEntry<Biome> entry = this.biomeIdMap.get(biomeId);
      return entry != null ? entry : defaultEntry;
   }
   /** Resolve a recorded biome without entering inference or chunk generation. */
   public RegistryEntry<Biome> recordedBiome(short id) {
      requireBiomeIdMap();return biomeIdMap.getOrDefault(id,biomeIdMap.get((short)1));
   }

   @Override
   public Pair<BlockPos, RegistryEntry<Biome>> locateBiome(
      BlockPos origin,
      int radius,
      int horizontalBlockCheckInterval,
      int verticalBlockCheckInterval,
      Predicate<RegistryEntry<Biome>> predicate,
      MultiNoiseSampler noiseSampler,
      WorldView world
   ) {
      return null;
   }

   @Override
   public Pair<BlockPos, RegistryEntry<Biome>> locateBiome(
      int x,
      int y,
      int z,
      int radius,
      int blockCheckInterval,
      Predicate<RegistryEntry<Biome>> predicate,
      Random random,
      boolean bl,
      MultiNoiseSampler noiseSampler
   ) {
      return null;
   }
}

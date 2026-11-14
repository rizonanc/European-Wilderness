package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.BiomeBalance;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;

public final class EuropeanEcology {
   public static final double DESERT_ARIDITY_MAX = 0.25;
   public static final double DESERT_MASK_THRESHOLD = 0.55;
   public static final int DESERT_WAVELENGTH_BLOCKS = 4096;
   public static final int DESERT_NOISE_CHANNEL = 5;
   public static final double OASIS_PROVINCE_THRESHOLD = 0.72;
   public static final int OASIS_WAVELENGTH_BLOCKS = 2048;
   public static final int OASIS_NOISE_CHANNEL = 6;
   public static final double OASIS_BOWL_THRESHOLD = 0.8;
   public static final int OASIS_BOWL_WAVELENGTH_BLOCKS = 192;
   public static final int OASIS_BOWL_NOISE_CHANNEL = 8;
   public static final double OASIS_MAX_DEPTH_BLOCKS = 4.0;
   public static final int OASIS_WATER_DEPTH_BLOCKS = 2;
   public static final int OASIS_NO_WATER = Integer.MIN_VALUE;
   public static final int OASIS_SEA_LEVEL_BLOCKS = 63;
   public static final double OASIS_REED_BELT_LO = 0.7;
   public static final double DUNE_MAX_AMPLITUDE_BLOCKS = 3.0;
   public static final int DUNE_WAVELENGTH_BLOCKS = 96;
   public static final int DUNE_NOISE_CHANNEL = 9;
   public static final double DESERT_EDGE_MASK_HALF_WIDTH = 0.05;
   public static final int DESERT_EDGE_BLEND_MIN_BLOCKS = 64;
   private static final int GLADE_WAVELENGTH_BLOCKS = 192;
   private static final int LANDSCAPE_SIZE_MULTIPLIER = 3;
   private static final double DRY_ARIDITY_MAX = 0.32;
   private static final int[][] OFFSETS = new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

   public static boolean youngPatch(long seed, int x, int z) {
      return valueNoise(seed, x, z, 4, 96) > 0.78;
   }

   public static short varyBiome(long seed,int x,int z,short biome,int percent) {
      if (percent == 0) return biome;
      short variant = (short)switch (biome) {
         case 8, 208 -> 108;
         case 209 -> 208;
         case 15, 215, 216 -> 115;
         case 16 -> 116;
         default -> biome;
      };
      if (variant == biome) return biome;
      return valueNoise(seed, x & ~3, z & ~3, 17, 320) > 1.0 - percent * 0.005 ? variant : biome;
   }

   static double desertMask(long seed, int blockX, int blockZ) {
      return desertMask(seed, blockX, blockZ, false);
   }

   static double desertMask(long seed, int x, int z, boolean largerLandscapes) {
      int wavelength = DESERT_WAVELENGTH_BLOCKS * (largerLandscapes ? LANDSCAPE_SIZE_MULTIPLIER : 1);
      return valueNoise(seed, x, z, DESERT_NOISE_CHANNEL, wavelength);
   }

   static boolean isDesert(long seed, double aridity, int x, int z, boolean largerLandscapes, BiomeBalance balance) {
      return aridity < (largerLandscapes ? balance.desertAridityLimit() : DESERT_ARIDITY_MAX)
         && desertMask(seed, x, z, largerLandscapes) > (largerLandscapes ? balance.desertMaskThreshold() : DESERT_MASK_THRESHOLD);
   }

   static double aridity(double tempC, double precipMm) {
      return precipMm / Math.max(300.0, 250.0 + 30.0 * tempC + tempC * tempC);
   }

   public static boolean openField(long seed, int x, int z, BiomeBalance balance) {
      // At the default balance, the lowest 5% of the smoothed noise forms clearings.
      return valueNoise(seed, x & ~3, z & ~3, 13, GLADE_WAVELENGTH_BLOCKS * LANDSCAPE_SIZE_MULTIPLIER) < balance.fieldMaskThreshold();
   }

   static double oasisProvinceMask(long seed, int blockX, int blockZ) {
      return valueNoise(seed, blockX, blockZ, 6, 2048);
   }

   static double oasisBowlMask(long seed, int blockX, int blockZ) {
      return valueNoise(seed, blockX, blockZ, 8, 192);
   }

   public static boolean isOasis(long seed, int blockX, int blockZ) {
      return desertMask(seed, blockX, blockZ) > 0.55 && oasisProvinceMask(seed, blockX, blockZ) > 0.72 && oasisBowlMask(seed, blockX, blockZ) > 0.8;
   }

   public static double oasisBowlDepth(long seed, int blockX, int blockZ) {
      if (!isOasis(seed, blockX, blockZ)) {
         return 0.0;
      }

      double t = (oasisBowlMask(seed, blockX, blockZ) - 0.8) / 0.19999999999999996;
      return 4.0 * smoothstep(0.0, 1.0, t);
   }

   public static double desertEdgeBlend(long seed, int blockX, int blockZ) {
      return smoothstep(0.5, 0.6000000000000001, desertMask(seed, blockX, blockZ));
   }

   public static double duneRelief(long seed, int blockX, int blockZ) {
      double field = valueNoise(seed, blockX, blockZ, 9, 96);
      return (field - 0.5) * 2.0 * 3.0 * desertEdgeBlend(seed, blockX, blockZ);
   }

   public static double desertRelief(long seed, int blockX, int blockZ) {
      return duneRelief(seed, blockX, blockZ) - oasisBowlDepth(seed, blockX, blockZ);
   }

   public static int oasisWaterY(long seed, int baseGroundY, int blockX, int blockZ) {
      if (isOasis(seed, blockX, blockZ) && baseGroundY > 63) {
         double depth = oasisBowlDepth(seed, blockX, blockZ);
         return depth < 3.0 ? Integer.MIN_VALUE : baseGroundY - (int)Math.round(depth) + 2;
      } else {
         return Integer.MIN_VALUE;
      }
   }

   public static boolean oasisWaterHolds(long seed, int baseGroundY, int blockX, int blockZ) {
      int waterY = oasisWaterY(seed, baseGroundY, blockX, blockZ);
      if (waterY == Integer.MIN_VALUE) {
         return false;
      }

      for (int[] offset : OFFSETS) {
         int nx = Math.addExact(blockX, offset[0]);
         int nz = Math.addExact(blockZ, offset[1]);
         double ground = baseGroundY - oasisBowlDepth(seed, nx, nz);
         if (!(ground > waterY) && oasisWaterY(seed, baseGroundY, nx, nz) != waterY) {
            return false;
         }
      }

      return true;
   }

   public static boolean isOasisReedBelt(long seed, int blockX, int blockZ) {
      return isOasis(seed, blockX, blockZ)
         ? false
         : desertMask(seed, blockX, blockZ) > 0.55 && oasisProvinceMask(seed, blockX, blockZ) > 0.72 && oasisBowlMask(seed, blockX, blockZ) > 0.7;
   }

   private EuropeanEcology() {
   }

   public static EuropeanEcology.ForestCondition evaluate(
      double tempC, double precipMm, double elevM, double slopeRatio, double waterDistanceM, int blockX, int blockZ
   ) {
      return evaluate(0L, tempC, precipMm, elevM, slopeRatio, waterDistanceM, blockX, blockZ);
   }

   public static EuropeanEcology.ForestCondition evaluate(
      long seed, double tempC, double precipMm, double elevM, double slopeRatio, double waterDistanceM, int blockX, int blockZ
   ) {
      return evaluate(seed, tempC, precipMm, elevM, slopeRatio, waterDistanceM, blockX, blockZ, false, BiomeBalance.DEFAULT);
   }

   public static ForestCondition evaluate(
      long seed, double tempC, double precipMm, double elevM, double slopeRatio, double waterDistanceM,
      int blockX, int blockZ, boolean largerLandscapes, BiomeBalance balance
   ) {
      if (!Double.isFinite(tempC)
         || !Double.isFinite(precipMm)
         || !Double.isFinite(elevM)
         || !Double.isFinite(slopeRatio)
         || Double.isNaN(waterDistanceM)
         || precipMm < 0.0
         || slopeRatio < 0.0
         || waterDistanceM < 0.0) {
         throw new IllegalArgumentException("Invalid ecology inputs");
      }

      if (elevM < 0.0) {
         return new EuropeanEcology.ForestCondition(EuropeanEcology.Zone.MEADOW, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, true);
      }

      if (!(elevM > 2600.0) && (!(tempC < -3.0) || !(elevM > 1700.0))) {
         if (slopeRatio >= 1.0) {
            return new EuropeanEcology.ForestCondition(EuropeanEcology.Zone.ROCKY_SLOPE, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, true);
         }

         double aridity = aridity(tempC, precipMm);
         boolean dry = aridity < DRY_ARIDITY_MAX;
         EuropeanEcology.Zone zone;
         if (waterDistanceM < 40.0 && elevM < 400.0) {
            zone = EuropeanEcology.Zone.FLOODPLAIN;
         } else {
            if (isDesert(seed, aridity, blockX, blockZ, largerLandscapes, balance)) {
               return new EuropeanEcology.ForestCondition(EuropeanEcology.Zone.DESERT, 0.02F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, true);
            }

            if (dry) {
               zone = EuropeanEcology.Zone.MEADOW;
            } else if (elevM >= 1400.0 && elevM < 2400.0 && tempC < 8.0) {
               zone = EuropeanEcology.Zone.MOUNTAIN_MEADOW;
            } else if (tempC >= 11.0) {
               zone = EuropeanEcology.Zone.DECIDUOUS_FOREST;
            } else if (tempC >= 5.0) {
               zone = EuropeanEcology.Zone.MIXED_FOREST;
            } else {
               zone = EuropeanEcology.Zone.CONIFEROUS_FOREST;
            }
         }

         int gladeWavelength = GLADE_WAVELENGTH_BLOCKS * (largerLandscapes ? LANDSCAPE_SIZE_MULTIPLIER : 1);
         double glade = smoothstep(0.65, 0.85, valueNoise(seed, blockX, blockZ, 0, gladeWavelength));
         double texture = 0.85 + 0.3 * valueNoise(seed, blockX, blockZ, 1, 48);
         double slopeFactor = 1.0 - smoothstep(0.5, 1.0, slopeRatio);
         double moistureFactor = 0.35 + 0.65 * smoothstep(0.15, 0.35, aridity);
         double baseDensity = 0.65 + 0.05 * smoothstep(3.5, 6.5, tempC) + 0.1 * smoothstep(9.5, 12.5, tempC);
         double meadowBlend = 1.0 - smoothstep(0.25, 0.4, aridity);
         baseDensity = baseDensity * (1.0 - meadowBlend) + 0.06 * meadowBlend;
         double mountainBlend = smoothstep(1200.0, 1600.0, elevM) * (1.0 - smoothstep(6.0, 10.0, tempC));
         baseDensity = baseDensity * (1.0 - mountainBlend) + 0.12 * mountainBlend;
         baseDensity *= 1.0 - smoothstep(2300.0, 2600.0, elevM);
         if (Double.isFinite(waterDistanceM)) {
            double floodBlend = (1.0 - smoothstep(20.0, 60.0, waterDistanceM)) * (1.0 - smoothstep(300.0, 500.0, elevM));
            baseDensity = baseDensity * (1.0 - floodBlend) + 0.5 * floodBlend;
         }

         double density = clamp01(baseDensity * (1.0 - 0.85 * glade) * texture * slopeFactor * moistureFactor);
         double openness = clamp01(1.0 - density * 0.9 + 0.2 * (valueNoise(seed, blockX, blockZ, 2, 96) - 0.5));
         double oak = clamp01((tempC - 6.0) / 10.0);
         double birch = 0.25 + 0.2 * valueNoise(seed, blockX, blockZ, 3, 128);
         double spruce = clamp01((8.0 - tempC) / 10.0) + clamp01((elevM - 600.0) / 2000.0) * 0.3;
         double pine = clamp01(0.5 - aridity) + 0.15;
         double coniferDamp = 1.0 - 0.7 * (1.0 - smoothstep(3.5, 6.5, tempC));
         double decidDamp = 1.0 - 0.6 * smoothstep(9.5, 12.5, tempC);
         oak *= coniferDamp;
         birch *= coniferDamp;
         spruce *= decidDamp;
         pine *= decidDamp;
         double total = oak + birch + spruce + pine;
         if (total <= 0.0) {
            pine = 0.25;
            spruce = 0.25;
            birch = 0.25;
            oak = 0.25;
            total = 1.0;
         }

         return new EuropeanEcology.ForestCondition(
            zone, (float)density, (float)openness, (float)(oak / total), (float)(birch / total), (float)(spruce / total), (float)(pine / total), false
         );
      } else {
         return new EuropeanEcology.ForestCondition(EuropeanEcology.Zone.ALPINE_SNOW, 0.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, true);
      }
   }

   public static EuropeanEcology.ForestCondition sample(TerrainTile tile, int localX, int localZ, float pixelSizeM) {
      int size = tile.key().tileSize();
      if (localX < 0 || localZ < 0 || localX >= size || localZ >= size) {
         throw new IllegalArgumentException("local coords out of tile");
      }

      if (!tile.hasClimate()) {
         throw new IllegalStateException("Tile has no climate layer (stored before climate support?)");
      }

      double tempC = tile.climateAt(0, localX, localZ);
      double precipMm = tile.climateAt(2, localX, localZ);
      double elevM = tile.heightAt(localX, localZ);
      double slope = slopeRatio(tile, localX, localZ, pixelSizeM);
      int blockX = tile.key().blockStartX() + localX;
      int blockZ = tile.key().blockStartZ() + localZ;
      return evaluate(tile.key().seed(), tempC, precipMm, elevM, slope, Double.POSITIVE_INFINITY, blockX, blockZ);
   }

   static double slopeRatio(TerrainTile tile, int localX, int localZ, float pixelSizeM) {
      int size = tile.key().tileSize();
      int x0 = Math.max(0, localX - 1);
      int x1 = Math.min(size - 1, localX + 1);
      int z0 = Math.max(0, localZ - 1);
      int z1 = Math.min(size - 1, localZ + 1);
      double dx = (tile.heightAt(x1, localZ) - tile.heightAt(x0, localZ)) / Math.max(1, x1 - x0);
      double dz = (tile.heightAt(localX, z1) - tile.heightAt(localX, z0)) / Math.max(1, z1 - z0);
      double risePerCell = Math.sqrt(dx * dx + dz * dz);
      return risePerCell / Math.max(0.001F, pixelSizeM);
   }

   static double valueNoise(long seed, int x, int z, int channel, int wavelength) {
      int gx = Math.floorDiv(x, wavelength);
      int gz = Math.floorDiv(z, wavelength);
      double tx = smoothstep(0.0, 1.0, (double)Math.floorMod(x, wavelength) / wavelength);
      double tz = smoothstep(0.0, 1.0, (double)Math.floorMod(z, wavelength) / wavelength);
      int sx = (int)seed;
      int sz = (int)(seed >>> 32);
      double a = hashNoise01(gx ^ sx, gz ^ sz, channel);
      double b = hashNoise01(gx + 1 ^ sx, gz ^ sz, channel);
      double c = hashNoise01(gx ^ sx, gz + 1 ^ sz, channel);
      double d = hashNoise01(gx + 1 ^ sx, gz + 1 ^ sz, channel);
      return (a + (b - a) * tx) * (1.0 - tz) + (c + (d - c) * tx) * tz;
   }

   static double smoothstep(double edge0, double edge1, double x) {
      double t = clamp01((x - edge0) / (edge1 - edge0));
      return t * t * (3.0 - 2.0 * t);
   }

   static double clamp01(double x) {
      return Math.min(1.0, Math.max(0.0, x));
   }

   static double hashNoise01(int x, int z, int channel) {
      long h = x * -7046029254386353131L ^ z * -4658895280553007671L ^ channel * -7723592293110705685L;
      h += -7046029254386353131L;
      h = (h ^ h >>> 30) * -4658895280553007671L;
      h = (h ^ h >>> 27) * -7723592293110705685L;
      h ^= h >>> 31;
      return (h >>> 11) * 1.1102230246251565E-16D;
   }

   public record ForestCondition(
      EuropeanEcology.Zone zone,
      float forestDensity,
      float canopyOpenness,
      float oakShare,
      float birchShare,
      float spruceShare,
      float pineShare,
      boolean forbidsTrees
   ) {
   }

   public enum Zone {
      DECIDUOUS_FOREST,
      MIXED_FOREST,
      CONIFEROUS_FOREST,
      MEADOW,
      FLOODPLAIN,
      MOUNTAIN_MEADOW,
      ROCKY_SLOPE,
      ALPINE_SNOW,
      DESERT;
   }
}

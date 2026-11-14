package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.PerformanceProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.PreparedFallback;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class MountainSurfacePlanning {
   private MountainSurfacePlanning() {
   }

   static MountainSurfacePlanning.WriteDecision decideWrite(
      MountainSurfaceMaterials.Material material,
      boolean natural,
      boolean groundDry,
      boolean aboveDry,
      boolean aboveAir,
      boolean singleSnowCap,
      boolean sameGround
   ) {
      if (!natural || !groundDry || !aboveDry) {
         return null;
      }

      if (material == MountainSurfaceMaterials.Material.THIN_SNOW) {
         return aboveAir ? new MountainSurfacePlanning.WriteDecision(false, true, false) : null;
      }

      if (material == MountainSurfaceMaterials.Material.NONE) {
         return null;
      }

      if (!aboveAir && !singleSnowCap) {
         return null;
      }

      boolean clearCap = singleSnowCap
         && (
            material == MountainSurfaceMaterials.Material.ROCK
               || material == MountainSurfaceMaterials.Material.SCREE
               || material == MountainSurfaceMaterials.Material.SNOW
               || material == MountainSurfaceMaterials.Material.MORAINE
         );
      if (!aboveAir && !clearCap) {
         return null;
      }

      boolean replaceGround = !sameGround;
      return !replaceGround && !clearCap && material != MountainSurfaceMaterials.Material.ROCK
         ? null
         : new MountainSurfacePlanning.WriteDecision(replaceGround, false, clearCap);
   }

   static int rockSubsurfaceDepth(IntPredicate naturalDryAtDepth) {
      Objects.requireNonNull(naturalDryAtDepth, "naturalDryAtDepth");
      int accepted = 0;

      for (int depth = 1; depth <= 2 && naturalDryAtDepth.test(depth); depth++) {
         accepted++;
      }

      return accepted;
   }

   static List<MountainSurfacePlanning.Placement> planChunk(
      MountainTerrainView terrain, long seed, int chunkX, int chunkZ, MountainSurfacePlanning.Classifier classifier
   ) {
      Objects.requireNonNull(terrain, "terrain");
      Objects.requireNonNull(classifier, "classifier");
      int baseX = Math.multiplyExact(chunkX, 16);
      int baseZ = Math.multiplyExact(chunkZ, 16);
      List<MountainSurfacePlanning.Placement> placements = new ArrayList<>();

      for (int dx = 0; dx < 16; dx++) {
         for (int dz = 0; dz < 16; dz++) {
            int x = Math.addExact(baseX, dx);
            int z = Math.addExact(baseZ, dz);
            MountainTerrainView.Sample surface = terrain.sample(x, z);
            int northY = terrain.firstAirNeighbour(x, z, 0, -1);
            int southY = terrain.firstAirNeighbour(x, z, 0, 1);
            int westY = terrain.firstAirNeighbour(x, z, -1, 0);
            int eastY = terrain.firstAirNeighbour(x, z, 1, 0);
            MountainSurfaceMaterials.Material material = Objects.requireNonNull(
               classifier.classify(seed, x, z, surface.firstAir(), surface.temperatureC(), northY, southY, westY, eastY), "classifier material"
            );
            if (material != MountainSurfaceMaterials.Material.NONE) {
               placements.add(new MountainSurfacePlanning.Placement(x, z, Math.subtractExact(surface.firstAir(), 1), material));
            }
         }
      }

      return List.copyOf(placements);
   }

   static <P> boolean planAndWrite(PerformanceProfile.PreparationMode mode, Supplier<P> planner, Predicate<P> writer) {
      Objects.requireNonNull(planner, "planner");
      Objects.requireNonNull(writer, "writer");

      P plan;
      try {
         plan = planner.get();
      } catch (PreparedFallback.PreparedTileMissingException missing) {
         if (mode == PerformanceProfile.PreparationMode.PREPARED_ONLY) {
            return false;
         }

         throw missing;
      }

      return writer.test(plan);
   }

   @FunctionalInterface
   interface Classifier {
      MountainSurfaceMaterials.Material classify(long var1, int var3, int var4, int var5, float var6, int var7, int var8, int var9, int var10);
   }

   record Placement(int x, int z, int groundY, MountainSurfaceMaterials.Material material) {
   }

   record WriteDecision(boolean replaceGround, boolean placeThinSnow, boolean clearSnowCap) {
   }
}

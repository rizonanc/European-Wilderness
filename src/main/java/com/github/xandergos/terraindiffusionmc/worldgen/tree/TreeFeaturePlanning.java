package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.PerformanceProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.PreparedFallback;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class TreeFeaturePlanning {
   private TreeFeaturePlanning() {
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
}

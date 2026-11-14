package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

final class TreePartPlacer {
   private TreePartPlacer() {
   }

   static int place(
      List<TreePlacement.PlacedVoxel> part,
      int chunkX,
      int chunkZ,
      Predicate<TreePlacement.PlacedVoxel> replaceable,
      Predicate<TreePlacement.PlacedVoxel> writer
   ) {
      Objects.requireNonNull(part, "part");
      Objects.requireNonNull(replaceable, "replaceable");
      Objects.requireNonNull(writer, "writer");

      for (TreePlacement.PlacedVoxel voxel : part) {
         if (Math.floorDiv(voxel.x(), 16) != chunkX || Math.floorDiv(voxel.z(), 16) != chunkZ) {
            return 0;
         }
      }

      for (TreePlacement.PlacedVoxel voxel : part) {
         if (!replaceable.test(voxel)) {
            return 0;
         }
      }

      int placed = 0;

      for (TreePlacement.PlacedVoxel voxel : part) {
         if (writer.test(voxel)) {
            placed++;
         }
      }

      return placed;
   }
}

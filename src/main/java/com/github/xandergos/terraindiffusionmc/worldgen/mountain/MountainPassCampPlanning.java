package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.ArrayList;
import java.util.List;

final class MountainPassCampPlanning {
   private MountainPassCampPlanning() {
   }

   static List<MountainPassCampPlanning.RouteColumn> routeForChunk(
      long seed, TerrainTile tile, int scale, int worldTopYInclusive, MountainPassPlanner.PassPlan pass, int chunkX, int chunkZ
   ) {
      int baseX = Math.multiplyExact(chunkX, 16);
      int baseZ = Math.multiplyExact(chunkZ, 16);
      int cap = WorldHeightProfile.surfaceCapY(worldTopYInclusive);
      List<MountainPassCampPlanning.RouteColumn> corrections = new ArrayList<>();
      TerrainTileKey key = tile.key();

      for (MountainPassPlanner.WalkablePoint point : pass.walkableRoute()) {
         if (point.raiseBlocks() != 0) {
            int x = point.x();
            int z = point.z();
            if (x >= baseX
               && x < baseX + 16
               && z >= baseZ
               && z < baseZ + 16
               && Math.floorDiv(x, key.tileSize()) == key.tileX()
               && Math.floorDiv(z, key.tileSize()) == key.tileZ()) {
               int naturalY = MountainHeightField.sampleForWorld(seed, x, z, tile.heightAt(key.localX(x), key.localZ(z)), scale, cap).surfaceY();
               if (naturalY != point.naturalFirstAir()) {
                  throw new IllegalStateException("Pass height disagrees with the prepared terrain tile");
               }

               corrections.add(new MountainPassCampPlanning.RouteColumn(x, z, naturalY - 1, point.plannedFirstAir() - 1));
            }
         }
      }

      return List.copyOf(corrections);
   }

   record RouteColumn(int x, int z, int naturalGroundY, int groundY) {
   }
}

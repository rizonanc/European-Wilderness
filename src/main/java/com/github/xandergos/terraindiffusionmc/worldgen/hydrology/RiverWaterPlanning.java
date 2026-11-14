package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.util.math.BlockPos;

final class RiverWaterPlanning {
   private static final int CHUNK_SIZE = 16;

   private RiverWaterPlanning() {
   }

   static List<BlockPos> planChunk(
      WorldSession.RiverOverlay overlay, int chunkX, int chunkZ, int bottomY, int topYInclusive, RiverWaterPlanning.ColumnAccess columns
   ) {
      if (overlay == null) {
         return List.of();
      }

      int baseX = Math.multiplyExact(chunkX, 16);
      int baseZ = Math.multiplyExact(chunkZ, 16);
      Set<Long> selfViable = new HashSet<>();

      for (int dx = 0; dx < 16; dx++) {
         int x = Math.addExact(baseX, dx);

         for (int dz = 0; dz < 16; dz++) {
            int z = Math.addExact(baseZ, dz);
            if (overlay.isReady(x, z)
               && overlay.isReady(Math.addExact(x, -1), z)
               && overlay.isReady(Math.addExact(x, 1), z)
               && overlay.isReady(x, Math.addExact(z, -1))
               && overlay.isReady(x, Math.addExact(z, 1))) {
               int waterY = overlay.waterY(x, z);
               if (waterY != Integer.MIN_VALUE && waterY > bottomY && waterY <= topYInclusive) {
                  int bedY = overlay.bedY(x, z, Math.addExact(waterY, 1));
                  long depth = (long)waterY - bedY;
                  if (depth >= 1L && depth <= FluidContainment.Fluid.WATER.maxDepthBlocks() && bedY >= bottomY && columns.hasBedSupport(x, bedY, z)) {
                     boolean air = true;

                     for (long y = bedY + 1L; y <= waterY; y++) {
                        if (!columns.isAir(x, (int)y, z) && !columns.isWater(x, (int)y, z)) {
                           air = false;
                           break;
                        }
                     }

                     if (air) {
                        selfViable.add(pack(x, z));
                     }
                  }
               }
            }
         }
      }

      List<BlockPos> placements = new ArrayList<>();

      for (int dx = 0; dx < 16; dx++) {
         int x = Math.addExact(baseX, dx);

         for (int dz = 0; dz < 16; dz++) {
            int z = Math.addExact(baseZ, dz);
            if (selfViable.contains(pack(x, z))) {
               int waterY = overlay.waterY(x, z);
               if (waterY != Integer.MIN_VALUE && waterY > bottomY && waterY <= topYInclusive) {
                  int bedY = overlay.bedY(x, z, Math.addExact(waterY, 1));
                  FluidContainment.WetTrust trust = (nx, nz) -> !isInChunk(baseX, baseZ, nx, nz) || selfViable.contains(pack(nx, nz));
                  if (FluidContainment.canPlace(FluidContainment.Fluid.WATER, overlay, x, z, bedY, waterY, bottomY, topYInclusive, columns, trust)) {
                     for (long y = bedY + 1L; y <= waterY; y++) {
                        placements.add(new BlockPos(x, (int)y, z));
                     }
                  }
               }
            }
         }
      }

      return List.copyOf(placements);
   }

   private static boolean isInChunk(int baseX, int baseZ, int nx, int nz) {
      long dx = (long)nx - baseX;
      long dz = (long)nz - baseZ;
      return dx >= 0L && dx < 16L && dz >= 0L && dz < 16L;
   }

   private static long pack(int x, int z) {
      return (long)x << 32 | z & 4294967295L;
   }

   interface ColumnAccess {
      default boolean hasBedSupport(int x,int y,int z) { return isSolidDry(x,y,z); }
      default boolean isWaterloggedBankCap(int x,int y,int z) { return false; }
      default boolean isWater(int x, int y, int z) { return false; }
      boolean isAir(int var1, int var2, int var3);

      boolean isSolidDry(int var1, int var2, int var3);
   }
}

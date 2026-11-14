package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;

public final class FluidContainment {
   private FluidContainment() {
   }

   public static boolean canPlace(
      FluidContainment.Fluid fluid,
      WorldSession.RiverOverlay overlay,
      int x,
      int z,
      int bedY,
      int waterY,
      int bottomY,
      int topYInclusive,
      RiverWaterPlanning.ColumnAccess columns
   ) {
      return canPlace(fluid, overlay, x, z, bedY, waterY, bottomY, topYInclusive, columns, null);
   }

   public static boolean canPlace(
      FluidContainment.Fluid fluid,
      WorldSession.RiverOverlay overlay,
      int x,
      int z,
      int bedY,
      int waterY,
      int bottomY,
      int topYInclusive,
      RiverWaterPlanning.ColumnAccess columns,
      FluidContainment.WetTrust wetTrust
   ) {
      if (fluid == null || overlay == null || columns == null) {
         return false;
      }

      if (waterY != Integer.MIN_VALUE && waterY > bottomY && waterY <= topYInclusive) {
         long depth = (long)waterY - bedY;
         if (depth >= 1L && depth <= fluid.maxDepthBlocks && bedY >= bottomY) {
            int westX = Math.addExact(x, -1);
            int eastX = Math.addExact(x, 1);
            int northZ = Math.addExact(z, -1);
            int southZ = Math.addExact(z, 1);
            if (!overlay.isReady(x, z)
               || !overlay.isReady(westX, z)
               || !overlay.isReady(eastX, z)
               || !overlay.isReady(x, northZ)
               || !overlay.isReady(x, southZ)) {
               return false;
            }

            if (!columns.hasBedSupport(x, bedY, z)) {
               return false;
            }

            for (long y = bedY + 1L; y <= waterY; y++) {
               if (!columns.isAir(x, (int)y, z) && !columns.isWater(x, (int)y, z)) {
                  return false;
               }
            }

            for (long y = bedY + 1L; y <= waterY; y++) {
               int level = (int)y;
               boolean seaOutlet=fluid==Fluid.WATER&&waterY==62;
               if (!sideClosed(overlay, columns, wetTrust, westX, level, z, bottomY, topYInclusive,seaOutlet,fluid==Fluid.WATER)
                  || !sideClosed(overlay, columns, wetTrust, eastX, level, z, bottomY, topYInclusive,seaOutlet,fluid==Fluid.WATER)
                  || !sideClosed(overlay, columns, wetTrust, x, level, northZ, bottomY, topYInclusive,seaOutlet,fluid==Fluid.WATER)
                  || !sideClosed(overlay, columns, wetTrust, x, level, southZ, bottomY, topYInclusive,seaOutlet,fluid==Fluid.WATER)) {
                  return false;
               }
            }

            int rimY = Math.addExact(waterY, 1);
            boolean seaOutlet=fluid==Fluid.WATER&&waterY==62;
            return rimClosed(overlay, columns, wetTrust, westX, rimY, z, waterY, bottomY, topYInclusive,seaOutlet,fluid==Fluid.WATER)
               && rimClosed(overlay, columns, wetTrust, eastX, rimY, z, waterY, bottomY, topYInclusive,seaOutlet,fluid==Fluid.WATER)
               && rimClosed(overlay, columns, wetTrust, x, rimY, northZ, waterY, bottomY, topYInclusive,seaOutlet,fluid==Fluid.WATER)
               && rimClosed(overlay, columns, wetTrust, x, rimY, southZ, waterY, bottomY, topYInclusive,seaOutlet,fluid==Fluid.WATER);
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private static boolean sideClosed(
      WorldSession.RiverOverlay overlay,
      RiverWaterPlanning.ColumnAccess columns,
      FluidContainment.WetTrust wetTrust,
      int nx,
      int y,
      int nz,
      int bottomY,
      int topYInclusive,boolean seaOutlet,boolean waterFluid
   ) {
      int neighbourWater = overlay.waterY(nx, nz);
      if(waterFluid&&neighbourWater==Integer.MIN_VALUE&&columns.isWaterloggedBankCap(nx,y,nz)) return true;
      if(seaOutlet&&neighbourWater==Integer.MIN_VALUE&&y<=62&&columns.isWater(nx,y,nz)) return true;
      if (overlay.isReady(nx, nz) && neighbourWater != Integer.MIN_VALUE && neighbourWater > bottomY
          && neighbourWater <= topYInclusive && overlay.bedY(nx, nz, neighbourWater + 1) < neighbourWater
          && (wetTrust == null || wetTrust.trust(nx, nz))) return true;
      return neighbourWater == Integer.MIN_VALUE
            || neighbourWater < y
            || neighbourWater <= bottomY
            || neighbourWater > topYInclusive
            || y <= overlay.bedY(nx, nz, Math.addExact(neighbourWater, 1))
            || wetTrust != null && !wetTrust.trust(nx, nz)
         ? columns.isSolidDry(nx, y, nz)
         : true;
   }

   private static boolean rimClosed(
      WorldSession.RiverOverlay overlay,
      RiverWaterPlanning.ColumnAccess columns,
      FluidContainment.WetTrust wetTrust,
      int nx,
      int rimY,
      int nz,
      int waterY,
      int bottomY,
      int topYInclusive,boolean seaOutlet,boolean waterFluid
   ) {
      int neighbourWater = overlay.waterY(nx, nz);
      if(waterFluid&&neighbourWater==Integer.MIN_VALUE&&columns.isWaterloggedBankCap(nx,waterY,nz)) return true;
      if(seaOutlet&&neighbourWater==Integer.MIN_VALUE&&waterY<=62&&columns.isWater(nx,waterY,nz)) return true;
      if (overlay.isReady(nx, nz) && neighbourWater != Integer.MIN_VALUE && neighbourWater > bottomY
          && neighbourWater <= topYInclusive && overlay.bedY(nx, nz, neighbourWater + 1) < neighbourWater
          && (wetTrust == null || wetTrust.trust(nx, nz))) return true;
      return neighbourWater == Integer.MIN_VALUE
            || neighbourWater < waterY
            || neighbourWater <= bottomY
            || neighbourWater > topYInclusive
            || wetTrust != null && !wetTrust.trust(nx, nz)
         // A dry beach can be flush with the water surface. The side-wall
         // checks already seal all wet levels; no retaining block above it is needed.
         ? columns.isSolidDry(nx, waterY, nz)
         : true;
   }

   public enum Fluid {
      WATER(PreparedWaterArea.MAX_DEPTH, 1),
      LAVA(1, 2);

      private final int maxDepthBlocks;
      private final int tickPeriod;

      Fluid(int maxDepthBlocks, int tickPeriod) {
         this.maxDepthBlocks = maxDepthBlocks;
         this.tickPeriod = tickPeriod;
      }

      int maxDepthBlocks() {
         return this.maxDepthBlocks;
      }
   }

   public static final class TickSimulator {
      private TickSimulator() {
      }

      public static boolean[] simulate(FluidContainment.Fluid fluid, int sizeX, int sizeY, int sizeZ, boolean[] solid, boolean[] initialFluid, int ticks) {
         if (fluid == null || solid == null || initialFluid == null) {
            throw new IllegalArgumentException("fluid, solid and initialFluid must not be null");
         }

         if (sizeX < 1 || sizeY < 1 || sizeZ < 1 || sizeX > 64 || sizeY > 64 || sizeZ > 64) {
            throw new IllegalArgumentException("grid dims must be within 1..64");
         }

         if (ticks >= 0 && ticks <= 1024) {
            long volume = (long)sizeX * sizeY * sizeZ;
            if (solid.length == volume && initialFluid.length == volume) {
               boolean[] current = (boolean[])initialFluid.clone();
               boolean[] next = new boolean[(int)volume];

               for (int tick = 1; tick <= ticks; tick++) {
                  if (tick % fluid.tickPeriod == 0) {
                     System.arraycopy(current, 0, next, 0, (int)volume);

                     for (int y = 0; y < sizeY; y++) {
                        for (int z = 0; z < sizeZ; z++) {
                           for (int x = 0; x < sizeX; x++) {
                              if (current[indexOf(sizeX, sizeZ, x, y, z)]) {
                                 int below = y - 1;
                                 if (below >= 0 && !solid[indexOf(sizeX, sizeZ, x, below, z)]) {
                                    next[indexOf(sizeX, sizeZ, x, below, z)] = true;
                                 }

                                 spread(next, solid, sizeX, sizeZ, y, x - 1, z);
                                 spread(next, solid, sizeX, sizeZ, y, x + 1, z);
                                 spread(next, solid, sizeX, sizeZ, y, x, z - 1);
                                 spread(next, solid, sizeX, sizeZ, y, x, z + 1);
                              }
                           }
                        }
                     }

                     boolean[] swap = current;
                     current = next;
                     next = swap;
                  }
               }

               return current;
            } else {
               throw new IllegalArgumentException("array length must equal sizeX*sizeY*sizeZ");
            }
         } else {
            throw new IllegalArgumentException("ticks must be within 0..1024, got " + ticks);
         }
      }

      private static void spread(boolean[] next, boolean[] solid, int sizeX, int sizeZ, int y, int nx, int nz) {
         if (nx >= 0 && nz >= 0 && nx < sizeX && nz < sizeZ) {
            int at = indexOf(sizeX, sizeZ, nx, y, nz);
            if (!solid[at]) {
               next[at] = true;
            }
         }
      }

      private static int indexOf(int sizeX, int sizeZ, int x, int y, int z) {
         return Math.addExact(Math.multiplyExact(Math.addExact(Math.multiplyExact(y, sizeZ), z), sizeX), x);
      }
   }

   public interface WetTrust {
      boolean trust(int var1, int var2);
   }
}

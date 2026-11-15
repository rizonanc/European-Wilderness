package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;

final class TreePlacementValidator {
   private TreePlacementValidator() {
   }

   @FunctionalInterface
   interface BlockedColumn {
      boolean test(int x, int z);
   }

   static boolean rootsAvoid(TreeShapeGenerator.Shape shape, int originX, int originZ, BlockedColumn blocked) {
      for (var voxel : shape.voxels()) {
         if (voxel.log() && voxel.y() == 0 && blocked.test(originX + voxel.x(), originZ + voxel.z())) return false;
      }
      return true;
   }

   /** Anchor on actual root voxels; a nearby river outside the trunk cannot sink it. */
   static int anchorBaseY(TreeShapeGenerator.Shape shape,int originX,int originZ,IntBinaryOperator firstAir) {
      int lowest=Integer.MAX_VALUE;
      for(var voxel:shape.voxels()) if(voxel.log()&&voxel.y()==0)
         lowest=Math.min(lowest,firstAir.applyAsInt(Math.addExact(originX,voxel.x()),Math.addExact(originZ,voxel.z())));
      if(lowest==Integer.MAX_VALUE) throw new IllegalArgumentException("Tree has no rooted trunk");
      return Math.subtractExact(lowest,1);
   }

   /** One complete-tree decision, shared by every chunk containing its canopy. */
   static boolean isWholeTreeClear(TreeShapeGenerator.Shape shape,int originX,int baseY,int originZ,
                                   IntBinaryOperator firstAir,IntBinaryOperator clearanceAir,IntBinaryOperator waterY) {
      for(var voxel:shape.voxels()) {
         int x=Math.addExact(originX,voxel.x()),z=Math.addExact(originZ,voxel.z());
         int y=Math.addExact(baseY,voxel.y()),groundAir=firstAir.applyAsInt(x,z),water=waterY.applyAsInt(x,z);
         if(voxel.log()&&voxel.y()==0) {
            if(water!=Integer.MIN_VALUE||baseY>groundAir||(long)baseY<groundAir-4L) return false;
         }
         if(water!=Integer.MIN_VALUE&&y<=water) return false;
         if(y<groundAir) {
            if(!voxel.log()||voxel.y()>3||(long)y<groundAir-4L) return false;
         } else if(y<clearanceAir.applyAsInt(x,z)) return false;
      }
      return true;
   }

   static boolean isWholeTreeWorldSupported(TreeShapeGenerator.Shape shape,int originX,int baseY,int originZ,
                                             IntBinaryOperator worldFirstAir) {
      for(var voxel:shape.voxels()) if(voxel.log()&&voxel.y()==0) {
         int air=worldFirstAir.applyAsInt(Math.addExact(originX,voxel.x()),Math.addExact(originZ,voxel.z()));
         if(baseY>air||(long)baseY<air-4L) return false;
      }
      return true;
   }

   static boolean mayPlaceWholeVoxel(boolean log,int localY,boolean fluid,boolean soft,boolean wood,boolean naturalRootGround) {
      if(fluid) return false;
      // Existing tree wood is compatible with planning, and preserved by the
      // writer. Otherwise a previously generated half would reject its own tree.
      return soft||wood||log&&localY<=3&&naturalRootGround;
   }

   static int anchorBaseY(TreeAge.Footprint base, int originX, int originZ, IntBinaryOperator firstAir) {
      int minX = originX - base.width() / 2 - 2;
      int minZ = originZ - base.depth() / 2 - 2;
      int maxX = minX + base.width() + 3;
      int maxZ = minZ + base.depth() + 3;
      int lowest = Integer.MAX_VALUE;

      for (int x = minX; x <= maxX; x++) {
         for (int z = minZ; z <= maxZ; z++) {
            int ground = firstAir.applyAsInt(x, z);
            if (ground < lowest) {
               lowest = ground;
            }
         }
      }

      if (lowest == Integer.MAX_VALUE) {
         throw new IllegalStateException("empty trunk footprint");
      } else {
         return Math.subtractExact(lowest, 1);
      }
   }

   static boolean isTerrainSupported(TreeShapeGenerator.Shape shape, int originX, int baseY, int originZ, IntBinaryOperator firstAir) {
      int radius = shape.radius();
      int side = 2 * radius + 1;
      int[] lowestVoxel = new int[side * side];
      boolean[] rootWood = new boolean[side * side];
      Arrays.fill(lowestVoxel, Integer.MAX_VALUE);

      for (TreeShapeGenerator.Voxel voxel : shape.voxels()) {
         int index = (voxel.x() + radius) * side + voxel.z() + radius;
         lowestVoxel[index] = Math.min(lowestVoxel[index], voxel.y());
         if (voxel.log() && voxel.y() == 0) {
            rootWood[index] = true;
         }
      }

      for (int index = 0; index < lowestVoxel.length; index++) {
         if (lowestVoxel[index] != Integer.MAX_VALUE) {
            int x = Math.addExact(originX, index / side - radius);
            int z = Math.addExact(originZ, index % side - radius);
            int terrainAir = firstAir.applyAsInt(x, z);
            if (rootWood[index] && baseY > terrainAir) {
               return false;
            }

            if (Math.addExact(baseY, lowestVoxel[index]) < terrainAir - 8) {
               return false;
            }
         }
      }

      return true;
   }

   static boolean isWorldSupported(TreeShapeGenerator.Shape shape, int originX, int baseY, int originZ, int chunkX, int chunkZ, IntBinaryOperator worldFirstAir) {
      for (TreeShapeGenerator.Voxel voxel : shape.voxels()) {
         if (voxel.log() && voxel.y() == 0) {
            int x = Math.addExact(originX, voxel.x());
            int z = Math.addExact(originZ, voxel.z());
            if (Math.floorDiv(x, 16) == chunkX && Math.floorDiv(z, 16) == chunkZ && baseY > worldFirstAir.applyAsInt(x, z)) {
               return false;
            }
         }
      }

      return true;
   }

   static boolean mayReplace(boolean log, boolean soft, boolean wood) {
      return soft || log && !wood;
   }

   static boolean touchesChunk(int originX, int originZ, int radius, int chunkX, int chunkZ) {
      if (radius < 0) {
         throw new IllegalArgumentException("radius must not be negative");
      }

      long chunkMinX = chunkX * 16L;
      long chunkMinZ = chunkZ * 16L;
      long chunkMaxX = chunkMinX + 15L;
      long chunkMaxZ = chunkMinZ + 15L;
      return (long)originX + radius >= chunkMinX
         && (long)originX - radius <= chunkMaxX
         && (long)originZ + radius >= chunkMinZ
         && (long)originZ - radius <= chunkMaxZ;
   }

   static boolean fitsWorldCoordinates(int originX, int originZ, int radius) {
      if (radius < 0) {
         throw new IllegalArgumentException("radius must not be negative");
      } else {
         return (long)originX - radius >= -2147483648L
            && (long)originX + radius <= 2147483647L
            && (long)originZ - radius >= -2147483648L
            && (long)originZ + radius <= 2147483647L;
      }
   }
}

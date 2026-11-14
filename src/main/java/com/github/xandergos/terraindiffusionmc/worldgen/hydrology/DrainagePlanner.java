package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.Arrays;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongHeapPriorityQueue;

public final class DrainagePlanner {
   public static final int COARSE_STEP_BLOCKS = 2;
   public static final int LAKE_MIN_CELLS = 12;
   public static final int LAKE_MIN_DEPTH_BLOCKS = 5;
   public static final long CHANNEL_MIN_ACCUMULATION_CELLS = 9L;
   public static final int CHANNEL_MIN_WIDTH_BLOCKS = 1;
   public static final int CHANNEL_MAX_WIDTH_BLOCKS = 7;
   public static final int CHANNEL_MIN_DEPTH_BLOCKS = 1;
   public static final int CHANNEL_MAX_DEPTH_BLOCKS = 4;
   public static final int CHANNEL_MAX_INCISION_BLOCKS = 6;
   public static final int MAX_GRID_DIMENSION = 2048;
   public static final int OUTLET = -1;
   public static final int[] DX = new int[]{1, 1, 0, -1, -1, -1, 0, 1};
   public static final int[] DZ = new int[]{0, 1, 1, 1, 0, -1, -1, -1};

   private DrainagePlanner() {
   }

   public static DrainagePlanner.DrainagePlan plan(long seed, int originX, int originZ, short[][] heights) {
      int width = validateFineGrid(heights);
      int height = heights.length;
      Math.addExact(originX, width - 1);
      Math.addExact(originZ, height - 1);
      long coarseW = (width + 2L - 1L) / 2L;
      long coarseH = (height + 2L - 1L) / 2L;
      int[][] coarseBase = new int[(int)coarseH][(int)coarseW];

      for (int cz = 0; cz < coarseH; cz++) {
         for (int cx = 0; cx < coarseW; cx++) {
            int min = Integer.MAX_VALUE;

            for (int fz = cz * 2; fz < Math.min(height, (cz + 1) * 2); fz++) {
               for (int fx = cx * 2; fx < Math.min(width, (cx + 1) * 2); fx++) {
                  min = Math.min(min, heights[fz][fx]);
               }
            }

            coarseBase[cz][cx] = min;
         }
      }

      return planCoarse(seed, originX, originZ, coarseBase);
   }

   public static DrainagePlanner.DrainagePlan planCoarse(long seed, int coarseOriginX, int coarseOriginZ, int[][] base) {
      return planCoarse(seed, coarseOriginX, coarseOriginZ, base, false, 100);
   }

   /** Prepared-water routing; keep the recovered public prototype's results compatible. */
   static DrainagePlan planPrepared(long seed, int originX, int originZ, int[][] base) {
      return planPrepared(seed, originX, originZ, base, 100);
   }

   static DrainagePlan planPrepared(long seed, int originX, int originZ, int[][] base, int lakeThreshold) {
      return planCoarse(seed, originX, originZ, base, true, lakeThreshold);
   }

   private static DrainagePlan planCoarse(long seed, int coarseOriginX, int coarseOriginZ, int[][] base, boolean naturalRouting, int lakeThreshold) {
      int width = validateCoarseGrid(base);
      int height = base.length;
      Math.addExact(coarseOriginX, width - 1);
      Math.addExact(coarseOriginZ, height - 1);
      int[][] filled = priorityFlood(base, width, height);
      DrainagePlanner.CellKind[][] kinds = classifyDepressions(base, filled, width, height, lakeThreshold);
      int[][] dirs = naturalRouting ? preparedDirections(seed,coarseOriginX,coarseOriginZ,filled,width,height)
         : flowDirections(filled, width, height);
      long[][] accumulation = accumulate(dirs, width, height);
      return new DrainagePlanner.DrainagePlan(seed, coarseOriginX, coarseOriginZ, filled, kinds, dirs, accumulation);
   }

   private static int[][] preparedDirections(long seed,int originX,int originZ,int[][] filled,int width,int height) {
      int[][] dirs=new int[height][width];
      boolean[][] resolved=new boolean[height][width];
      java.util.ArrayList<Integer> exits=new java.util.ArrayList<>();
      for(int z=0;z<height;z++) for(int x=0;x<width;x++) {
         int best=-1;double bestSlope=0;long bestTie=0;
         for(int d=0;d<8;d++) {
            int nx=x+DX[d],nz=z+DZ[d];
            if(nx<0||nz<0||nx>=width||nz>=height) continue;
            double drop=(long)filled[z][x]-filled[nz][nx];
            if(drop<=0) continue;
            double slope=drop/(DX[d]!=0&&DZ[d]!=0?Math.sqrt(2):1);
            long tie=routingTie(seed,originX+nx*2,originZ+nz*2);
            if(slope>bestSlope || (slope==bestSlope && Long.compareUnsigned(tie,bestTie)>0)) {
               best=d;bestSlope=slope;bestTie=tie;
            }
         }
         dirs[z][x]=best;
         if(best>=0||x==0||z==0||x==width-1||z==height-1) {
            resolved[z][x]=true;exits.add(z*width+x);
         }
      }
      // Seed ALL exits first. A newly resolved cell must not become another
      // zero-distance exit during a row scan: that caused long directional stripes.
      exits.sort((a,b)->Long.compareUnsigned(
         routingTie(seed,originX+a%width*2,originZ+a/width*2),
         routingTie(seed,originX+b%width*2,originZ+b/width*2)));
      IntArrayFIFOQueue wave=new IntArrayFIFOQueue(exits.size());
      for (int exit : exits) wave.enqueue(exit);
      while(!wave.isEmpty()) {
         int c=wave.dequeueInt(),x=c%width,z=c/width;
         for(int d=0;d<8;d++) {
            int nx=x+DX[d],nz=z+DZ[d];
            if(nx>=0&&nz>=0&&nx<width&&nz<height&&!resolved[nz][nx]&&filled[nz][nx]==filled[z][x]) {
               dirs[nz][nx]=opposite(d);resolved[nz][nx]=true;wave.enqueue(nz*width+nx);
            }
         }
      }
      for(int z=0;z<height;z++) for(int x=0;x<width;x++)
         if(!resolved[z][x]) throw new IllegalStateException("Unreachable prepared-water flat at "+x+","+z);
      return dirs;
   }

   private static long routingTie(long seed,int x,int z) {
      long value=seed^((long)x<<32)^(z&0xffffffffL);
      value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
      value=(value^(value>>>27))*0x94d049bb133111ebL;
      return value^(value>>>31);
   }

   public static int channelWidthBlocks(long accumulation) {
      if (accumulation < 9L) {
         return 0;
      }

      long ratio = accumulation / 9L;
      int doublings = 63 - Long.numberOfLeadingZeros(ratio);
      return Math.min(7, 1 + doublings);
   }

   public static int channelDepthBlocks(long accumulation) {
      if (accumulation < 9L) {
         return 0;
      }

      long ratio = accumulation / 9L;
      int doublings = 63 - Long.numberOfLeadingZeros(ratio);
      return Math.min(4, 1 + doublings);
   }

   private static int[][] priorityFlood(int[][] base, int width, int height) {
      int[][] filled = new int[height][];

      for (int z = 0; z < height; z++) {
         filled[z] = (int[])base[z].clone();
      }

      boolean[][] closed = new boolean[height][width];
      // Signed height in the high word, row-major index in the low word:
      // exactly the old (height, z, x) order, including negative/extreme heights.
      LongHeapPriorityQueue queue = new LongHeapPriorityQueue();

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            if (x == 0 || z == 0 || x == width - 1 || z == height - 1) {
               closed[z][x] = true;
               queue.enqueue(((long)filled[z][x] << 32) | ((z * width + x) & 0xffffffffL));
            }
         }
      }

      while (!queue.isEmpty()) {
         long cell = queue.dequeueLong();
         int code = (int)cell, x = code % width, z = code / width;
         int cellHeight = (int)(cell >> 32);

         for (int d = 0; d < DX.length; d++) {
            int nx = x + DX[d];
            int nz = z + DZ[d];
            if (nx >= 0 && nz >= 0 && nx < width && nz < height && !closed[nz][nx]) {
               closed[nz][nx] = true;
               filled[nz][nx] = Math.max(base[nz][nx], cellHeight);
               queue.enqueue(((long)filled[nz][nx] << 32) | ((nz * width + nx) & 0xffffffffL));
            }
         }
      }

      return filled;
   }

   private static DrainagePlanner.CellKind[][] classifyDepressions(int[][] base, int[][] filled, int width, int height, int lakeThreshold) {
      int minCells=(LAKE_MIN_CELLS*lakeThreshold+99)/100;
      int minDepth=(LAKE_MIN_DEPTH_BLOCKS*lakeThreshold+99)/100;
      DrainagePlanner.CellKind[][] kinds = new DrainagePlanner.CellKind[height][width];

      for (int z = 0; z < height; z++) {
         Arrays.fill(kinds[z], DrainagePlanner.CellKind.DRAINED);
      }

      boolean[][] seen = new boolean[height][width];
      IntArrayFIFOQueue queue = new IntArrayFIFOQueue();

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            if (filled[z][x] > base[z][x] && !seen[z][x]) {
               long cells = 0L;
               long maxDepth = 0L;
               IntArrayFIFOQueue component = new IntArrayFIFOQueue();
               queue.clear();
               queue.enqueue(z * width + x);
               seen[z][x] = true;

               while (!queue.isEmpty()) {
                  int code = queue.dequeueInt();
                  int cx = code % width;
                  int cz = code / width;
                  component.enqueue(code);
                  cells++;
                  maxDepth = Math.max(maxDepth, (long)filled[cz][cx] - base[cz][cx]);

                  for (int d = 0; d < DX.length; d++) {
                     int nx = cx + DX[d];
                     int nz = cz + DZ[d];
                     if (nx >= 0 && nz >= 0 && nx < width && nz < height && !seen[nz][nx] && filled[nz][nx] > base[nz][nx]) {
                        seen[nz][nx] = true;
                        queue.enqueue(nz * width + nx);
                     }
                  }
               }

               DrainagePlanner.CellKind kind = cells < minCells && maxDepth < minDepth
                  ? DrainagePlanner.CellKind.FILLED : DrainagePlanner.CellKind.LAKE;

               while (!component.isEmpty()) {
                  int code = component.dequeueInt();
                  kinds[code / width][code % width] = kind;
               }
            }
         }
      }

      return kinds;
   }

   private static int[][] flowDirections(int[][] filled, int width, int height) {
      int[][] dirs = new int[height][width];
      boolean[][] resolved = new boolean[height][width];

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            int best = -1;
            int bestHeight = filled[z][x];

            for (int d = 0; d < DX.length; d++) {
               int nx = x + DX[d];
               int nz = z + DZ[d];
               if (nx >= 0 && nz >= 0 && nx < width && nz < height && filled[nz][nx] < bestHeight) {
                  bestHeight = filled[nz][nx];
                  best = d;
               }
            }

            if (best >= 0) {
               dirs[z][x] = best;
               resolved[z][x] = true;
            } else if (x != 0 && z != 0 && x != width - 1 && z != height - 1) {
               dirs[z][x] = -1;
            } else {
               dirs[z][x] = -1;
               resolved[z][x] = true;
            }
         }
      }

      resolveFlats(filled, dirs, resolved, width, height);
      return dirs;
   }

   private static void resolveFlats(int[][] filled, int[][] dirs, boolean[][] resolved, int width, int height) {
      IntArrayFIFOQueue queue = new IntArrayFIFOQueue();

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            if (!resolved[z][x]) {
               for (int d = 0; d < DX.length; d++) {
                  int nx = x + DX[d];
                  int nz = z + DZ[d];
                  if (nx >= 0 && nz >= 0 && nx < width && nz < height && resolved[nz][nx] && filled[nz][nx] <= filled[z][x]) {
                     dirs[z][x] = d;
                     resolved[z][x] = true;
                     queue.enqueue(z * width + x);
                     break;
                  }
               }
            }
         }
      }

      while (!queue.isEmpty()) {
         int code = queue.dequeueInt();
         int x = code % width;
         int z = code / width;

         for (int d = 0; d < DX.length; d++) {
            int nx = x + DX[d];
            int nz = z + DZ[d];
            if (nx >= 0 && nz >= 0 && nx < width && nz < height && !resolved[nz][nx] && filled[nz][nx] == filled[z][x]) {
               dirs[nz][nx] = opposite(d);
               resolved[nz][nx] = true;
               queue.enqueue(nz * width + nx);
            }
         }
      }

      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            if (!resolved[z][x]) {
               throw new IllegalStateException("unreachable flat at (" + x + "," + z + ")");
            }
         }
      }
   }

   private static int opposite(int dir) {
      return (dir + 4) % DX.length;
   }

   /** Accumulate each tributary before its downstream cell, in linear time.
    * All contributions are positive integer cell counts, so changing the order
    * of independent tributaries cannot change the result. Routing is untouched.
    */
   private static long[][] accumulate(int[][] dirs, int width, int height) {
      byte[][] incoming = new byte[height][width]; // At most eight adjacent tributaries.
      long[][] accumulation = new long[height][width];
      int[] ready = new int[width * height];
      int head = 0, tail = 0;
      for (int z = 0; z < height; z++) {
         Arrays.fill(accumulation[z], 1L);
         for (int x = 0; x < width; x++) {
            int d = dirs[z][x];
            if (d != OUTLET) incoming[z + DZ[d]][x + DX[d]]++;
         }
      }
      for (int z = 0; z < height; z++) {
         for (int x = 0; x < width; x++) {
            if (incoming[z][x] == 0) ready[tail++] = z * width + x;
         }
      }
      while (head < tail) {
         int code = ready[head++], x = code % width, z = code / width;
         int d = dirs[z][x];
         if (d != OUTLET) {
            int nx = x + DX[d], nz = z + DZ[d];
            accumulation[nz][nx] = Math.addExact(accumulation[nz][nx], accumulation[z][x]);
            if (--incoming[nz][nx] == 0) ready[tail++] = nz * width + nx;
         }
      }
      if (tail != ready.length) throw new IllegalStateException("Unreachable drainage cells: " + (ready.length - tail));
      return accumulation;
   }

   private static int validateFineGrid(short[][] heights) {
      if (heights == null) {
         throw new IllegalArgumentException("heights must not be null");
      }

      if (heights.length == 0 || heights.length > 2048) {
         throw new IllegalArgumentException("heights height must be 1..2048, got " + heights.length);
      }

      if (heights[0] == null) {
         throw new IllegalArgumentException("heights row 0 must not be null");
      }

      int width = heights[0].length;
      if (width != 0 && width <= 2048) {
         for (int z = 1; z < heights.length; z++) {
            if (heights[z] == null) {
               throw new IllegalArgumentException("heights row " + z + " must not be null");
            }

            if (heights[z].length != width) {
               throw new IllegalArgumentException("heights must be rectangular: row " + z + " has width " + heights[z].length + " instead of " + width);
            }
         }

         return width;
      } else {
         throw new IllegalArgumentException("heights width must be 1..2048, got " + width);
      }
   }

   private static int validateCoarseGrid(int[][] base) {
      if (base == null) {
         throw new IllegalArgumentException("base must not be null");
      }

      if (base.length == 0 || base.length > 2048) {
         throw new IllegalArgumentException("base height must be 1..2048, got " + base.length);
      }

      if (base[0] == null) {
         throw new IllegalArgumentException("base row 0 must not be null");
      }

      int width = base[0].length;
      if (width != 0 && width <= 2048) {
         for (int z = 1; z < base.length; z++) {
            if (base[z] == null) {
               throw new IllegalArgumentException("base row " + z + " must not be null");
            }

            if (base[z].length != width) {
               throw new IllegalArgumentException("base must be rectangular: row " + z + " has width " + base[z].length + " instead of " + width);
            }
         }

         return width;
      } else {
         throw new IllegalArgumentException("base width must be 1..2048, got " + width);
      }
   }

   public enum CellKind {
      DRAINED,
      FILLED,
      LAKE;
   }

   public static final class DrainagePlan {
      private final long seed;
      private final int originX;
      private final int originZ;
      private final int width;
      private final int height;
      private final int[][] filled;
      private final DrainagePlanner.CellKind[][] kinds;
      private final int[][] dirs;
      private final long[][] accumulation;

      private DrainagePlan(long seed, int originX, int originZ, int[][] filled, DrainagePlanner.CellKind[][] kinds, int[][] dirs, long[][] accumulation) {
         this.seed = seed;
         this.originX = originX;
         this.originZ = originZ;
         this.height = filled.length;
         this.width = filled[0].length;
         // Private constructor: the planner transfers fresh, exclusively owned
         // arrays. Input terrain was copied by priorityFlood; public array
         // accessors below still return defensive copies.
         this.filled = filled;
         this.kinds = kinds;
         this.dirs = dirs;
         this.accumulation = accumulation;
      }

      public long seed() {
         return this.seed;
      }

      public int originX() {
         return this.originX;
      }

      public int originZ() {
         return this.originZ;
      }

      public int width() {
         return this.width;
      }

      public int height() {
         return this.height;
      }

      public int filledHeight(int x, int z) {
         this.checkCell(x, z);
         return this.filled[z][x];
      }

      public DrainagePlanner.CellKind kind(int x, int z) {
         this.checkCell(x, z);
         return this.kinds[z][x];
      }

      public int direction(int x, int z) {
         this.checkCell(x, z);
         return this.dirs[z][x];
      }

      public long accumulation(int x, int z) {
         this.checkCell(x, z);
         return this.accumulation[z][x];
      }

      public boolean isChannel(int x, int z) {
         return this.accumulation(x, z) >= 9L;
      }

      public int channelWidthBlocks(int x, int z) {
         return DrainagePlanner.channelWidthBlocks(this.accumulation(x, z));
      }

      public int channelDepthBlocks(int x, int z) {
         return DrainagePlanner.channelDepthBlocks(this.accumulation(x, z));
      }

      public int[][] filledSurface() {
         int[][] copy = new int[this.height][this.width];

         for (int z = 0; z < this.height; z++) {
            copy[z] = (int[])this.filled[z].clone();
         }

         return copy;
      }

      public int[][] directions() {
         int[][] copy = new int[this.height][this.width];

         for (int z = 0; z < this.height; z++) {
            copy[z] = (int[])this.dirs[z].clone();
         }

         return copy;
      }

      public long[][] accumulation() {
         long[][] copy = new long[this.height][this.width];

         for (int z = 0; z < this.height; z++) {
            copy[z] = (long[])this.accumulation[z].clone();
         }

         return copy;
      }

      public int worldXOf(int coarseX) {
         if (coarseX >= 0 && coarseX < this.width) {
            return Math.addExact(this.originX, Math.multiplyExact(coarseX, 2));
         } else {
            throw new IllegalArgumentException("coarseX out of range: " + coarseX);
         }
      }

      public int worldZOf(int coarseZ) {
         if (coarseZ >= 0 && coarseZ < this.height) {
            return Math.addExact(this.originZ, Math.multiplyExact(coarseZ, 2));
         } else {
            throw new IllegalArgumentException("coarseZ out of range: " + coarseZ);
         }
      }

      public int coarseXOf(int blockX) {
         int local = Math.subtractExact(blockX, this.originX);
         int offset = Math.floorMod(local, 2);
         if (offset >= 0 && offset < 2) {
            int coarse = Math.floorDiv(local, 2);
            if (coarse >= 0 && coarse < this.width) {
               return coarse;
            } else {
               throw new IllegalArgumentException("blockX outside the planned area: " + blockX);
            }
         } else {
            throw new IllegalStateException("floorMod out of range: " + offset);
         }
      }

      public int coarseZOf(int blockZ) {
         int local = Math.subtractExact(blockZ, this.originZ);
         int offset = Math.floorMod(local, 2);
         if (offset >= 0 && offset < 2) {
            int coarse = Math.floorDiv(local, 2);
            if (coarse >= 0 && coarse < this.height) {
               return coarse;
            } else {
               throw new IllegalArgumentException("blockZ outside the planned area: " + blockZ);
            }
         } else {
            throw new IllegalStateException("floorMod out of range: " + offset);
         }
      }

      private void checkCell(int x, int z) {
         if (x < 0 || x >= this.width || z < 0 || z >= this.height) {
            throw new IllegalArgumentException("cell (" + x + "," + z + ") outside " + this.width + "x" + this.height);
         }
      }
   }
}

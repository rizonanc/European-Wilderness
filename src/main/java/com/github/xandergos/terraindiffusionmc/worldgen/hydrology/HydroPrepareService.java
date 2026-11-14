package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

public final class HydroPrepareService {
   public static final int CHUNK_BLOCKS = 16;
   private final WatershedPlan plan;
   private final SeamContract seam;
   private final BiPredicate<Integer, Integer> activeTile;

   public HydroPrepareService(WatershedPlan plan, SeamContract seam) {
      this(plan, seam, (tileX, tileZ) -> false);
   }

   public HydroPrepareService(WatershedPlan plan, SeamContract seam, BiPredicate<Integer, Integer> activeTile) {
      this.plan = Objects.requireNonNull(plan, "plan");
      this.seam = Objects.requireNonNull(seam, "seam");
      this.activeTile = Objects.requireNonNull(activeTile, "activeTile");
      if (seam.plan() != plan) {
         throw new IllegalArgumentException("seam was frozen over a different plan");
      }
   }

   public WorldSession.RiverOverlay asOverlay() {
      return new WorldSession.RiverOverlay() {
         @Override
         public boolean isReady(int worldX, int worldZ) {
            return HydroPrepareService.this.plan.containsWorld(worldX, worldZ)
               && HydroPrepareService.this.activeTile
                  .test(
                     Math.floorDiv(worldX, HydroPrepareService.this.seam.tileSizeBlocks()),
                     Math.floorDiv(worldZ, HydroPrepareService.this.seam.tileSizeBlocks())
                  );
         }

         @Override
         public int carvedHeight(int worldX, int worldZ, int rawY) {
            if (!this.isReady(worldX, worldZ)) {
               return rawY;
            } else {
               int lx = HydroPrepareService.this.plan.localXOfWorld(worldX);
               int lz = HydroPrepareService.this.plan.localZOfWorld(worldZ);
               long acc = HydroPrepareService.this.plan.accumulation(lx, lz);
               if (acc < 9L) {
                  int rim = HydroPrepareService.this.bankRim(lx, lz, rawY);
                  return rim == Integer.MIN_VALUE ? rawY : rim;
               } else {
                  int water = HydroPrepareService.this.channelWater(lx, lz);
                  int depth = DrainagePlanner.channelDepthBlocks(acc);
                  int bed = Math.min(Math.subtractExact(rawY, 1), Math.subtractExact(water, depth));
                  int frozenBed = HydroPrepareService.this.seam.crossingBedAt(lx, lz);
                  return frozenBed == Integer.MIN_VALUE ? bed : Math.min(bed, frozenBed);
               }
            }
         }

         @Override
         public int waterY(int worldX, int worldZ) {
            if (!this.isReady(worldX, worldZ)) {
               return Integer.MIN_VALUE;
            }

            int lx = HydroPrepareService.this.plan.localXOfWorld(worldX);
            int lz = HydroPrepareService.this.plan.localZOfWorld(worldZ);
            return HydroPrepareService.this.plan.accumulation(lx, lz) < 9L ? Integer.MIN_VALUE : HydroPrepareService.this.channelWater(lx, lz);
         }
      };
   }

   private int channelWater(int lx, int lz) {
      int crossing = this.seam.crossingWaterAt(lx, lz);
      if (crossing != Integer.MIN_VALUE) {
         return crossing;
      } else if (this.plan.direction(lx, lz) == -1) {
         BasinOutlet outlet = this.plan.outletAt(lx, lz);
         return this.seam.joinWaterY(outlet.canonId());
      } else {
         int profiled = this.seam.profiledWaterAt(lx, lz);
         return profiled != Integer.MIN_VALUE ? profiled : Math.max(this.plan.filledHeight(lx, lz), 63);
      }
   }

   public static WatershedPlan prepareUnit(
      long seed, int unitOriginX, int unitOriginZ, int parentCoarseW, int parentCoarseH, Map<HydroPrepareService.ParentCoord, int[][]> parentBases
   ) {
      Objects.requireNonNull(parentBases, "parentBases");
      if (parentCoarseW >= 1 && parentCoarseH >= 1) {
         if (parentBases.isEmpty()) {
            throw new IllegalArgumentException("a unit needs at least one parent grid");
         }

         List<HydroPrepareService.ParentCoord> coords = new ArrayList<>(parentBases.keySet());
         coords.sort(Comparator.comparingInt(HydroPrepareService.ParentCoord::parentZ).thenComparingInt(HydroPrepareService.ParentCoord::parentX));
         int minX = coords.get(0).parentX();
         int minZ = coords.get(0).parentZ();
         int maxX = minX;
         int maxZ = minZ;

         for (HydroPrepareService.ParentCoord coord : coords) {
            maxX = Math.max(maxX, coord.parentX());
            maxZ = Math.max(maxZ, coord.parentZ());
         }

         long spanX = (long)maxX - minX + 1L;
         long spanZ = (long)maxZ - minZ + 1L;
         if (spanX * spanZ != coords.size()) {
            throw new IllegalArgumentException(
               "parent grids must tile a full rectangle, got " + coords.size() + " grids over a " + spanX + "x" + spanZ + " span"
            );
         }

         int unitW = Math.toIntExact(Math.multiplyExact(spanX, parentCoarseW));
         int unitH = Math.toIntExact(Math.multiplyExact(spanZ, parentCoarseH));
         int[][] assembled = new int[unitH][unitW];

         for (HydroPrepareService.ParentCoord coord : coords) {
            int[][] grid = parentBases.get(coord);
            if (grid == null || grid.length != parentCoarseH) {
               throw new IllegalArgumentException("parent " + coord + " must hold exactly " + parentCoarseH + " rows");
            }

            int baseZ = Math.toIntExact(Math.multiplyExact((long)coord.parentZ() - minZ, parentCoarseH));
            int baseX = Math.toIntExact(Math.multiplyExact((long)coord.parentX() - minX, parentCoarseW));

            for (int z = 0; z < parentCoarseH; z++) {
               if (grid[z] == null || grid[z].length != parentCoarseW) {
                  throw new IllegalArgumentException("parent " + coord + " row " + z + " must hold exactly " + parentCoarseW + " columns");
               }

               System.arraycopy(grid[z], 0, assembled[baseZ + z], baseX, parentCoarseW);
            }
         }

         return prepareExtent(seed, unitOriginX, unitOriginZ, unitW, unitH, assembled);
      } else {
         throw new IllegalArgumentException("parent grid must be at least 1x1, got " + parentCoarseW + "x" + parentCoarseH);
      }
   }

   public static WatershedPlan prepareExtent(long seed, int coarseOriginX, int coarseOriginZ, int coarseW, int coarseH, int[][] coarseBase) {
      return WatershedPlan.planExtent(seed, coarseOriginX, coarseOriginZ, coarseW, coarseH, coarseBase);
   }

   public static void requireCarvable(boolean sourceIsFallback) {
      RiverRasterizer.shouldRasterizeOrThrow(sourceIsFallback);
   }

   public HydroPrepareService.RiverLayer riverLayerForChunk(int chunkOriginBlockX, int chunkOriginBlockZ, int[][] rawGround, boolean sourceIsFallback) {
      validateRaw(rawGround);
      Math.addExact(chunkOriginBlockX, 15);
      Math.addExact(chunkOriginBlockZ, 15);
      if (!RiverRasterizer.shouldRasterize(sourceIsFallback)) {
         return HydroPrepareService.RiverLayer.empty(this.plan.seed(), chunkOriginBlockX, chunkOriginBlockZ);
      }

      int[][] carved = new int[16][16];
      int[][] water = new int[16][16];
      boolean[][] channel = new boolean[16][16];
      boolean[][] bank = new boolean[16][16];
      RiverRasterizer.BankMaterial[][] materials = new RiverRasterizer.BankMaterial[16][16];

      for (int dz = 0; dz < 16; dz++) {
         for (int dx = 0; dx < 16; dx++) {
            int worldX = Math.addExact(chunkOriginBlockX, dx);
            int worldZ = Math.addExact(chunkOriginBlockZ, dz);
            int raw = rawGround[dz][dx];
            carved[dz][dx] = raw;
            water[dz][dx] = Integer.MIN_VALUE;
            if (this.plan.containsWorld(worldX, worldZ)) {
               int lx = this.plan.localXOfWorld(worldX);
               int lz = this.plan.localZOfWorld(worldZ);
               long acc = this.plan.accumulation(lx, lz);
               if (acc >= 9L) {
                  int surface = this.channelWater(lx, lz);
                  if (this.plan.direction(lx, lz) == -1) {
                     BasinOutlet outlet = this.plan.outletAt(lx, lz);
                     int joinWater = this.seam.joinWaterY(outlet.canonId());
                     int joinBed = this.seam.joinBedY(outlet.canonId());
                     if (surface != joinWater) {
                        throw new IllegalStateException("seam drift at outlet " + outlet.canonId());
                     }

                     if (raw == this.plan.filledHeight(lx, lz)
                        && Math.min(Math.subtractExact(raw, 1), Math.subtractExact(joinWater, DrainagePlanner.channelDepthBlocks(acc))) != joinBed) {
                        throw new IllegalStateException("seam bed drift at outlet " + outlet.canonId());
                     }

                     surface = joinWater;
                  }

                  int depth = DrainagePlanner.channelDepthBlocks(acc);
                  carved[dz][dx] = Math.min(Math.subtractExact(raw, 1), Math.subtractExact(surface, depth));
                  int frozenBed = this.seam.crossingBedAt(lx, lz);
                  if (frozenBed != Integer.MIN_VALUE) {
                     carved[dz][dx] = Math.min(carved[dz][dx], frozenBed);
                  }

                  water[dz][dx] = surface;
                  channel[dz][dx] = true;
               } else {
                  int rim = this.bankRim(lx, lz, raw);
                  if (rim != Integer.MIN_VALUE) {
                     carved[dz][dx] = rim;
                     bank[dz][dx] = true;
                     int slope = Math.min(7, (int)Math.min(2147483647L, Math.abs((long)raw - this.bankWater(lx, lz))));
                     materials[dz][dx] = RiverRasterizer.bankMaterial(this.plan.seed(), worldX, worldZ, slope);
                  }
               }
            }
         }
      }

      return new HydroPrepareService.RiverLayer(this.plan.seed(), chunkOriginBlockX, chunkOriginBlockZ, carved, water, channel, bank, materials);
   }

   private int bankRim(int lx, int lz, int raw) {
      int water = this.bankWater(lx, lz);
      if (water == Integer.MIN_VALUE) {
         return Integer.MIN_VALUE;
      } else {
         int rim = Math.max(raw, Math.addExact(water, 1));
         if (!RiverRasterizer.rimHoldsWater(water, rim)) {
            throw new IllegalStateException("bank rim " + rim + " does not hold water " + water);
         } else {
            return rim;
         }
      }
   }

   private int bankWater(int lx, int lz) {
      int best = Integer.MIN_VALUE;

      for (int dz = -2; dz <= 2; dz++) {
         for (int dx = -2; dx <= 2; dx++) {
            if (dx != 0 || dz != 0) {
               int nx = lx + dx;
               int nz = lz + dz;
               if (nx >= 0 && nz >= 0 && nx < this.plan.coarseWidth() && nz < this.plan.coarseHeight()) {
                  long acc = this.plan.accumulation(nx, nz);
                  if (acc >= 9L) {
                     int bankWidth = RiverRasterizer.bankWidthBlocks(acc);
                     if (bankWidth > 0) {
                        int reach = (bankWidth + 1) / 2;
                        if (Math.max(Math.abs(dx), Math.abs(dz)) <= reach) {
                           best = Math.max(best, this.channelWater(nx, nz));
                        }
                     }
                  }
               }
            }
         }
      }

      return best;
   }

   private static void validateRaw(int[][] rawGround) {
      if (rawGround != null && rawGround.length == 16) {
         for (int z = 0; z < 16; z++) {
            if (rawGround[z] == null || rawGround[z].length != 16) {
               throw new IllegalArgumentException("rawGround row " + z + " must have exactly 16 columns");
            }
         }
      } else {
         throw new IllegalArgumentException("rawGround must be exactly 16 rows");
      }
   }

   public record ParentCoord(int parentX, int parentZ) {
   }

   public static final class RiverLayer {
      private final long seed;
      private final int originX;
      private final int originZ;
      private final int[][] carved;
      private final int[][] water;
      private final boolean[][] channel;
      private final boolean[][] bank;
      private final RiverRasterizer.BankMaterial[][] materials;

      private RiverLayer(
         long seed, int originX, int originZ, int[][] carved, int[][] water, boolean[][] channel, boolean[][] bank, RiverRasterizer.BankMaterial[][] materials
      ) {
         this.seed = seed;
         this.originX = originX;
         this.originZ = originZ;
         this.carved = carved;
         this.water = water;
         this.channel = channel;
         this.bank = bank;
         this.materials = materials;
      }

      private static HydroPrepareService.RiverLayer empty(long seed, int originX, int originZ) {
         int[][] carved = new int[16][16];
         int[][] water = new int[16][16];

         for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
               water[z][x] = Integer.MIN_VALUE;
            }
         }

         return new HydroPrepareService.RiverLayer(
            seed, originX, originZ, carved, water, new boolean[16][16], new boolean[16][16], new RiverRasterizer.BankMaterial[16][16]
         );
      }

      public int carvedHeight(int worldX, int worldZ, int rawY) {
         int[] local = this.checkWorld(worldX, worldZ);
         return !this.channel[local[1]][local[0]] && !this.bank[local[1]][local[0]] ? rawY : this.carved[local[1]][local[0]];
      }

      public int waterY(int worldX, int worldZ) {
         int[] local = this.checkWorld(worldX, worldZ);
         return this.water[local[1]][local[0]];
      }

      public boolean isChannel(int worldX, int worldZ) {
         int[] local = this.checkWorld(worldX, worldZ);
         return this.channel[local[1]][local[0]];
      }

      public boolean isBank(int worldX, int worldZ) {
         int[] local = this.checkWorld(worldX, worldZ);
         return this.bank[local[1]][local[0]];
      }

      public String bankBlockId(int worldX, int worldZ) {
         int[] local = this.checkWorld(worldX, worldZ);
         RiverRasterizer.BankMaterial material = this.materials[local[1]][local[0]];
         return material == null ? null : material.blockId();
      }

      private int[] checkWorld(int worldX, int worldZ) {
         int dx = Math.subtractExact(worldX, this.originX);
         int dz = Math.subtractExact(worldZ, this.originZ);
         if (dx >= 0 && dx < 16 && dz >= 0 && dz < 16) {
            return new int[]{dx, dz};
         } else {
            throw new IllegalArgumentException("world cell (" + worldX + "," + worldZ + ") outside this 16x16 layer");
         }
      }
   }
}

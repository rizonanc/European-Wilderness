package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;

public final class SeamContract {
   private final WatershedPlan plan;
   private final int tileSizeBlocks;
   private final Map<Long, BasinOutlet> outlets;
   private final CrossTileRiverTracer.ProfileIndex profiles;
   private final Map<Long, List<SeamContract.RiverCrossing>> crossingsByTile;
   private final int[] crossingWater;
   private final int[] crossingBed;

   private SeamContract(
      WatershedPlan plan,
      int tileSizeBlocks,
      Map<Long, BasinOutlet> outlets,
      CrossTileRiverTracer.ProfileIndex profiles,
      Map<Long, List<SeamContract.RiverCrossing>> crossingsByTile,
      int[] crossingWater,
      int[] crossingBed
   ) {
      this.plan = plan;
      this.tileSizeBlocks = tileSizeBlocks;
      this.outlets = outlets;
      this.profiles = profiles;
      this.crossingsByTile = crossingsByTile;
      this.crossingWater = crossingWater;
      this.crossingBed = crossingBed;
   }

   public static SeamContract of(WatershedPlan plan, int tileSizeBlocks) {
      if (plan == null) {
         throw new IllegalArgumentException("plan must not be null");
      }

      if (tileSizeBlocks >= 2 && tileSizeBlocks <= 4096) {
         Map<Long, BasinOutlet> frozen = new LinkedHashMap<>();

         for (BasinOutlet outlet : plan.outlets()) {
            frozen.put(outlet.canonId(), outlet);
         }

         CrossTileRiverTracer.ProfileIndex profiles = CrossTileRiverTracer.index(plan);
         int width = plan.coarseWidth();
         int height = plan.coarseHeight();
         int[] crossingWater = new int[Math.multiplyExact(width, height)];
         int[] crossingBed = new int[crossingWater.length];
         Arrays.fill(crossingWater, Integer.MIN_VALUE);
         Arrays.fill(crossingBed, Integer.MIN_VALUE);
         Map<Long, List<SeamContract.RiverCrossing>> byTile = new HashMap<>();

         for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
               int dir = plan.direction(x, z);
               if (dir != -1 && plan.accumulation(x, z) >= 9L) {
                  int nx = x + DrainagePlanner.DX[dir];
                  int nz = z + DrainagePlanner.DZ[dir];
                  if (plan.accumulation(nx, nz) >= 9L) {
                     int fromX = plan.worldXOf(x);
                     int fromZ = plan.worldZOf(z);
                     int toX = plan.worldXOf(nx);
                     int toZ = plan.worldZOf(nz);
                     int fromTileX = Math.floorDiv(fromX, tileSizeBlocks);
                     int fromTileZ = Math.floorDiv(fromZ, tileSizeBlocks);
                     int toTileX = Math.floorDiv(toX, tileSizeBlocks);
                     int toTileZ = Math.floorDiv(toZ, tileSizeBlocks);
                     if (fromTileX != toTileX || fromTileZ != toTileZ) {
                        int fromCode = z * width + x;
                        int toCode = nz * width + nx;
                        int fromWater = profileWater(plan, profiles, x, z);
                        int toWater = profileWater(plan, profiles, nx, nz);
                        int fromBed = Math.min(
                           Math.subtractExact(plan.filledHeight(x, z), 1),
                           Math.subtractExact(fromWater, DrainagePlanner.channelDepthBlocks(plan.accumulation(x, z)))
                        );
                        int toBed = Math.min(
                           Math.subtractExact(plan.filledHeight(nx, nz), 1),
                           Math.subtractExact(toWater, DrainagePlanner.channelDepthBlocks(plan.accumulation(nx, nz)))
                        );
                        SeamContract.RiverCrossing crossing = new SeamContract.RiverCrossing(fromX, fromZ, toX, toZ, fromWater, toWater, fromBed, toBed);
                        freeze(crossingWater, crossingBed, fromCode, fromWater, fromBed);
                        freeze(crossingWater, crossingBed, toCode, toWater, toBed);
                        byTile.computeIfAbsent(tileCode(fromTileX, fromTileZ), unused -> new ArrayList<>()).add(crossing);
                        byTile.computeIfAbsent(tileCode(toTileX, toTileZ), unused -> new ArrayList<>()).add(crossing);
                     }
                  }
               }
            }
         }

         Map<Long, List<SeamContract.RiverCrossing>> immutable = new HashMap<>();

         for (Entry<Long, List<SeamContract.RiverCrossing>> entry : byTile.entrySet()) {
            entry.getValue()
               .sort(
                  Comparator.comparingInt(SeamContract.RiverCrossing::fromWorldZ)
                     .thenComparingInt(SeamContract.RiverCrossing::fromWorldX)
                     .thenComparingInt(SeamContract.RiverCrossing::toWorldZ)
                     .thenComparingInt(SeamContract.RiverCrossing::toWorldX)
               );
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
         }

         return new SeamContract(plan, tileSizeBlocks, Collections.unmodifiableMap(frozen), profiles, Map.copyOf(immutable), crossingWater, crossingBed);
      } else {
         throw new IllegalArgumentException("tileSizeBlocks must be 2..4096, got " + tileSizeBlocks);
      }
   }

   public List<SeamContract.RiverCrossing> edgeCrossings(int tileX, int tileZ) {
      return this.crossingsByTile.getOrDefault(tileCode(tileX, tileZ), List.of());
   }

   CrossTileRiverTracer.ProfileIndex profiles() {
      return this.profiles;
   }

   int profiledWaterAt(int localX, int localZ) {
      return this.profiles.waterAt(localZ * this.plan.coarseWidth() + localX);
   }

   int crossingWaterAt(int localX, int localZ) {
      return this.crossingWater[localZ * this.plan.coarseWidth() + localX];
   }

   int crossingBedAt(int localX, int localZ) {
      return this.crossingBed[localZ * this.plan.coarseWidth() + localX];
   }

   private static int profileWater(WatershedPlan plan, CrossTileRiverTracer.ProfileIndex profiles, int x, int z) {
      int level = profiles.waterAt(z * plan.coarseWidth() + x);
      return level != Integer.MIN_VALUE ? level : Math.max(plan.filledHeight(x, z), 63);
   }

   private static void freeze(int[] waters, int[] beds, int code, int water, int bed) {
      if (waters[code] == Integer.MIN_VALUE || waters[code] == water && beds[code] == bed) {
         waters[code] = water;
         beds[code] = bed;
      } else {
         throw new IllegalStateException("conflicting internal seam at coarse cell " + code);
      }
   }

   private static long tileCode(int tileX, int tileZ) {
      return (long)tileZ << 32 | tileX & 4294967295L;
   }

   public WatershedPlan plan() {
      return this.plan;
   }

   public int tileSizeBlocks() {
      return this.tileSizeBlocks;
   }

   public List<BasinOutlet> edgeOutlets(int tileX, int tileZ) {
      int x0 = Math.multiplyExact(tileX, this.tileSizeBlocks);
      int z0 = Math.multiplyExact(tileZ, this.tileSizeBlocks);
      int x1 = Math.addExact(x0, this.tileSizeBlocks);
      int z1 = Math.addExact(z0, this.tileSizeBlocks);
      List<BasinOutlet> edge = new ArrayList<>();

      for (BasinOutlet outlet : this.outlets.values()) {
         int cx0 = outlet.worldX();
         int cz0 = outlet.worldZ();
         int cx1 = Math.addExact(cx0, 2);
         int cz1 = Math.addExact(cz0, 2);
         boolean overlaps = cx0 < x1 && cx1 > x0 && cz0 < z1 && cz1 > z0;
         boolean onEdge = cx0 <= x0 || cx1 >= x1 || cz0 <= z0 || cz1 >= z1;
         if (overlaps && onEdge) {
            edge.add(outlet);
         }
      }

      edge.sort(Comparator.comparingLong(BasinOutlet::canonId));
      return Collections.unmodifiableList(edge);
   }

   public BasinOutlet find(long outletId) {
      BasinOutlet outlet = this.outlets.get(outletId);
      if (outlet == null) {
         throw new IllegalArgumentException("unknown seam outlet id: " + outletId);
      } else {
         return outlet;
      }
   }

   public int joinWaterY(long outletId) {
      return this.find(outletId).waterY();
   }

   public int joinBedY(long outletId) {
      return this.find(outletId).bedY();
   }

   public static long crossingId(long seed, int worldX, int worldZ, int generatorVersion) {
      return WatershedPlan.basinIdOf(seed, worldX, worldZ, generatorVersion);
   }

   public List<SeamContract.RiverCrossing> sharedBorderCrossings(
      int parentTileX, int parentTileZ, int parentTilesW, int parentTilesH, SeamContract.BorderSide side
   ) {
      Objects.requireNonNull(side, "side");
      if (parentTilesW >= 1 && parentTilesH >= 1) {
         Set<SeamContract.RiverCrossing> shared = new LinkedHashSet<>();

         for (List<SeamContract.RiverCrossing> crossings : this.crossingsByTile.values()) {
            for (SeamContract.RiverCrossing crossing : crossings) {
               if (this.straddlesParentBorder(crossing, parentTileX, parentTileZ, parentTilesW, parentTilesH, side)) {
                  shared.add(crossing);
               }
            }
         }

         List<SeamContract.RiverCrossing> ordered = new ArrayList<>(shared);
         ordered.sort(
            Comparator.comparingInt(SeamContract.RiverCrossing::fromWorldZ)
               .thenComparingInt(SeamContract.RiverCrossing::fromWorldX)
               .thenComparingInt(SeamContract.RiverCrossing::toWorldZ)
               .thenComparingInt(SeamContract.RiverCrossing::toWorldX)
         );
         return List.copyOf(ordered);
      } else {
         throw new IllegalArgumentException("parent must cover at least 1x1 tiles, got " + parentTilesW + "x" + parentTilesH);
      }
   }

   private boolean straddlesParentBorder(
      SeamContract.RiverCrossing crossing, int parentTileX, int parentTileZ, int parentTilesW, int parentTilesH, SeamContract.BorderSide side
   ) {
      int fromTileX = Math.floorDiv(crossing.fromWorldX(), this.tileSizeBlocks);
      int fromTileZ = Math.floorDiv(crossing.fromWorldZ(), this.tileSizeBlocks);
      int toTileX = Math.floorDiv(crossing.toWorldX(), this.tileSizeBlocks);
      int toTileZ = Math.floorDiv(crossing.toWorldZ(), this.tileSizeBlocks);

      return switch (side) {
         case WEST -> inTileRows(fromTileZ, toTileZ, parentTileZ, parentTilesH)
            && (
               insideParent(fromTileX, parentTileX, parentTilesW) && toTileX == parentTileX - 1
                  || insideParent(toTileX, parentTileX, parentTilesW) && fromTileX == parentTileX - 1
            );
         case EAST -> inTileRows(fromTileZ, toTileZ, parentTileZ, parentTilesH)
            && (
               insideParent(fromTileX, parentTileX, parentTilesW) && toTileX == parentTileX + parentTilesW
                  || insideParent(toTileX, parentTileX, parentTilesW) && fromTileX == parentTileX + parentTilesW
            );
         case NORTH -> inTileCols(fromTileX, toTileX, parentTileX, parentTilesW)
            && (
               insideParent(fromTileZ, parentTileZ, parentTilesH) && toTileZ == parentTileZ - 1
                  || insideParent(toTileZ, parentTileZ, parentTilesH) && fromTileZ == parentTileZ - 1
            );
         case SOUTH -> inTileCols(fromTileX, toTileX, parentTileX, parentTilesW)
            && (
               insideParent(fromTileZ, parentTileZ, parentTilesH) && toTileZ == parentTileZ + parentTilesH
                  || insideParent(toTileZ, parentTileZ, parentTilesH) && fromTileZ == parentTileZ + parentTilesH
            );
      };
   }

   private static boolean insideParent(int tile, int origin, int span) {
      return tile >= origin && tile < origin + span;
   }

   private static boolean inTileRows(int fromTileZ, int toTileZ, int origin, int span) {
      return insideParent(fromTileZ, origin, span) || insideParent(toTileZ, origin, span);
   }

   private static boolean inTileCols(int fromTileX, int toTileX, int origin, int span) {
      return insideParent(fromTileX, origin, span) || insideParent(toTileX, origin, span);
   }

   public void requireDryBorderHasNoChannel(int parentTileX, int parentTileZ, int parentTilesW, int parentTilesH, SeamContract.BorderSide side) {
      Objects.requireNonNull(side, "side");
      if (parentTilesW >= 1 && parentTilesH >= 1) {
         int blockX0 = Math.multiplyExact(parentTileX, this.tileSizeBlocks);
         int blockZ0 = Math.multiplyExact(parentTileZ, this.tileSizeBlocks);
         int blockX1 = Math.addExact(blockX0, Math.multiplyExact(parentTilesW, this.tileSizeBlocks));
         int blockZ1 = Math.addExact(blockZ0, Math.multiplyExact(parentTilesH, this.tileSizeBlocks));

         for (int z = 0; z < this.plan.coarseHeight(); z++) {
            for (int x = 0; x < this.plan.coarseWidth(); x++) {
               int cellX = this.plan.worldXOf(x);
               int cellZ = this.plan.worldZOf(z);
               if (cellX >= blockX0
                  && cellX < blockX1
                  && cellZ >= blockZ0
                  && cellZ < blockZ1
                  && this.exitsParentThrough(x, z, blockX0, blockZ0, blockX1, blockZ1, side)
                  && this.plan.isChannelCell(x, z)) {
                  throw new IllegalStateException(
                     "dry "
                        + side
                        + " border of parent ("
                        + parentTileX
                        + ","
                        + parentTileZ
                        + ") would leave a hanging channel at world ("
                        + cellX
                        + ","
                        + cellZ
                        + "): resolve as a contained lake or another outlet before publication"
                  );
               }
            }
         }
      } else {
         throw new IllegalArgumentException("parent must cover at least 1x1 tiles, got " + parentTilesW + "x" + parentTilesH);
      }
   }

   private boolean exitsParentThrough(int x, int z, int blockX0, int blockZ0, int blockX1, int blockZ1, SeamContract.BorderSide side) {
      int cellX = this.plan.worldXOf(x);
      int cellZ = this.plan.worldZOf(z);
      int dir = this.plan.direction(x, z);
      if (dir == -1) {
         return switch (side) {
            case WEST -> cellX <= blockX0;
            case EAST -> Math.addExact(cellX, 2) >= blockX1;
            case NORTH -> cellZ <= blockZ0;
            case SOUTH -> Math.addExact(cellZ, 2) >= blockZ1;
         };
      } else {
         int nx = x + DrainagePlanner.DX[dir];
         int nz = z + DrainagePlanner.DZ[dir];
         if (nx >= 0 && nz >= 0 && nx < this.plan.coarseWidth() && nz < this.plan.coarseHeight()) {
            int nextX = this.plan.worldXOf(nx);
            int nextZ = this.plan.worldZOf(nz);
            boolean inside = nextX >= blockX0 && nextX < blockX1 && nextZ >= blockZ0 && nextZ < blockZ1;
            if (inside) {
               return false;
            }

            return switch (side) {
               case WEST -> nextX < blockX0;
               case EAST -> nextX >= blockX1;
               case NORTH -> nextZ < blockZ0;
               case SOUTH -> nextZ >= blockZ1;
            };
         } else {
            return true;
         }
      }
   }

   public void verifyCrossParentJoin(List<SeamContract.RiverCrossing> border) {
      Objects.requireNonNull(border, "border");

      for (SeamContract.RiverCrossing crossing : border) {
         int fromX = this.plan.localXOfWorld(crossing.fromWorldX());
         int fromZ = this.plan.localZOfWorld(crossing.fromWorldZ());
         int toX = this.plan.localXOfWorld(crossing.toWorldX());
         int toZ = this.plan.localZOfWorld(crossing.toWorldZ());
         if (this.plan.isChannelCell(fromX, fromZ) && this.plan.isChannelCell(toX, toZ)) {
            if (this.crossingWaterAt(fromX, fromZ) == crossing.fromWaterY()
               && this.crossingBedAt(fromX, fromZ) == crossing.fromBedY()
               && this.crossingWaterAt(toX, toZ) == crossing.toWaterY()
               && this.crossingBedAt(toX, toZ) == crossing.toBedY()) {
               this.checkJoinColumn(fromX, fromZ, crossing.fromWaterY(), crossing.fromBedY());
               this.checkJoinColumn(toX, toZ, crossing.toWaterY(), crossing.toBedY());
               this.verifyDownstreamProfile(fromX, fromZ);
               continue;
            }

            throw new IllegalStateException("cross-parent seam drift at (" + crossing.fromWorldX() + "," + crossing.fromWorldZ() + ")");
         }

         throw new IllegalStateException(
            "cross-parent join over non-channel cells from ("
               + crossing.fromWorldX()
               + ","
               + crossing.fromWorldZ()
               + ") to ("
               + crossing.toWorldX()
               + ","
               + crossing.toWorldZ()
               + "): a dry boundary must not carry a carved river"
         );
      }
   }

   private void checkJoinColumn(int x, int z, int waterY, int bedY) {
      int ground = this.plan.filledHeight(x, z);
      if ((long)ground - bedY > 6L) {
         throw new IllegalStateException("cross-parent join exceeds the six-block carve budget at (" + x + "," + z + "): ground " + ground + ", bed " + bedY);
      }

      if ((long)waterY - bedY > 4L) {
         throw new IllegalStateException("cross-parent water column exceeds four blocks at (" + x + "," + z + ")");
      }
   }

   private void verifyDownstreamProfile(int startX, int startZ) {
      int width = this.plan.coarseWidth();
      int height = this.plan.coarseHeight();
      int cap = Math.multiplyExact(width, height);
      List<Integer> waters = new ArrayList<>();
      Set<Long> visited = new HashSet<>();
      int cx = startX;
      int cz = startZ;

      for (int step = 0; step < cap; step++) {
         long code = (long)cz * width + cx;
         if (!visited.add(code)) {
            throw new IllegalStateException("cross-parent cycle at (" + cx + "," + cz + ")");
         }

         waters.add(this.frozenWaterAt(cx, cz));
         int dir = this.plan.direction(cx, cz);
         if (dir == -1) {
            BasinOutlet outlet = this.plan.outletAt(cx, cz);
            if (this.joinWaterY(outlet.canonId()) == outlet.waterY() && this.joinBedY(outlet.canonId()) == outlet.bedY()) {
               int[] profile = waters.stream().mapToInt(Integer::intValue).toArray();
               BasinOutlet.verifyProfile(profile, 63);
               return;
            }

            throw new IllegalStateException("cross-parent outlet drift at (" + cx + "," + cz + ")");
         }

         cx += DrainagePlanner.DX[dir];
         cz += DrainagePlanner.DZ[dir];
         if (cx < 0 || cz < 0 || cx >= width || cz >= height) {
            throw new IllegalStateException("cross-parent broken edge at (" + cx + "," + cz + ")");
         }
      }

      throw new IllegalStateException("cross-parent river exceeds W*H steps from (" + startX + "," + startZ + ")");
   }

   private int frozenWaterAt(int localX, int localZ) {
      int crossing = this.crossingWaterAt(localX, localZ);
      if (crossing != Integer.MIN_VALUE) {
         return crossing;
      }

      if (this.plan.direction(localX, localZ) == -1) {
         return this.joinWaterY(this.plan.outletAt(localX, localZ).canonId());
      }

      int profiled = this.profiledWaterAt(localX, localZ);
      return profiled != Integer.MIN_VALUE ? profiled : Math.max(this.plan.filledHeight(localX, localZ), 63);
   }

   public enum BorderSide {
      WEST,
      EAST,
      NORTH,
      SOUTH;
   }

   public record RiverCrossing(int fromWorldX, int fromWorldZ, int toWorldX, int toWorldZ, int fromWaterY, int toWaterY, int fromBedY, int toBedY) {
      public RiverCrossing(int fromWorldX, int fromWorldZ, int toWorldX, int toWorldZ, int fromWaterY, int toWaterY, int fromBedY, int toBedY) {
         if (fromWaterY >= 63 && toWaterY >= 63) {
            int drop = Math.subtractExact(fromWaterY, toWaterY);
            if (drop < 0 || drop > 2) {
               throw new IllegalArgumentException("internal seam water step " + drop + " must be 0..2");
            }

            if (fromBedY < fromWaterY && toBedY < toWaterY) {
               this.fromWorldX = fromWorldX;
               this.fromWorldZ = fromWorldZ;
               this.toWorldX = toWorldX;
               this.toWorldZ = toWorldZ;
               this.fromWaterY = fromWaterY;
               this.toWaterY = toWaterY;
               this.fromBedY = fromBedY;
               this.toBedY = toBedY;
            } else {
               throw new IllegalArgumentException("internal seam bed must stay below water");
            }
         } else {
            throw new IllegalArgumentException("internal seam water below sea level");
         }
      }
   }
}

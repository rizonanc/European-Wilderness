package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

public final class HydroUnitGate {
   private final WorldSession ticket;
   private final WatershedStore store;
   private volatile Map<WatershedStore.UnitKey, HydroUnitGate.Published> active = Map.of();
   private final Object activeLock = new Object();
   private final ConcurrentHashMap<WatershedStore.UnitKey, Future<WatershedPlan>> pending = new ConcurrentHashMap<>();

   public HydroUnitGate(WorldSession ticket, WatershedStore store) {
      this.ticket = Objects.requireNonNull(ticket, "ticket");
      this.store = Objects.requireNonNull(store, "store");
   }

   public HydroUnitGate.GateView viewForChunk(int chunkX, int chunkZ) {
      if (!this.ticket.isActive()) {
         return new HydroUnitGate.GateView(HydroUnitGate.Verdict.DEFER, "retired session: no river overlay", null);
      }

      long chunkX0 = chunkX * 16L;
      long chunkZ0 = chunkZ * 16L;
      long chunkX1 = chunkX0 + 16L;
      long chunkZ1 = chunkZ0 + 16L;
      Map<WatershedStore.UnitKey, HydroUnitGate.Published> snapshot = this.active;

      for (Entry<WatershedStore.UnitKey, HydroUnitGate.Published> entry : snapshot.entrySet()) {
         WatershedStore.UnitKey key = entry.getKey();
         HydroUnitGate.Published published = entry.getValue();
         if (intersects(key, chunkX0, chunkZ0, chunkX1, chunkZ1)) {
            return new HydroUnitGate.GateView(HydroUnitGate.Verdict.READY, "watershed ready", published.overlay());
         }
      }

      for (WatershedStore.UnitKey key : this.pending.keySet()) {
         if (intersects(key, chunkX0, chunkZ0, chunkX1, chunkZ1)) {
            return new HydroUnitGate.GateView(
               HydroUnitGate.Verdict.DEFER, "preparing watershed (" + key.originX() + "," + key.originZ() + "): background planning in progress", null
            );
         }
      }

      return new HydroUnitGate.GateView(
         HydroUnitGate.Verdict.DEFER, "waiting for background watershed preparation at frontier chunk (" + chunkX + "," + chunkZ + ")", null
      );
   }

   public WatershedPlan publishUnit(
      long seed,
      int unitOriginX,
      int unitOriginZ,
      int parentCoarseW,
      int parentCoarseH,
      Map<HydroPrepareService.ParentCoord, int[][]> parentBases,
      WatershedStore.UnitKey key,
      List<TerrainTileKey> daughterTiles,
      Set<Long> contractedOutletIds,
      Path regionDirectory,
      GeneratedChunkGuard.RuntimeChunkProbe probe,
      HydroUnitGate.RawReady rawReady
   ) throws IOException {
      WorldSession.requireActive(this.ticket);
      Objects.requireNonNull(parentBases, "parentBases");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(daughterTiles, "daughterTiles");
      Objects.requireNonNull(contractedOutletIds, "contractedOutletIds");
      Objects.requireNonNull(regionDirectory, "regionDirectory");
      Objects.requireNonNull(probe, "probe");
      Objects.requireNonNull(rawReady, "rawReady");
      FutureTask<WatershedPlan> task = new FutureTask<>(
         () -> this.publishLocked(
            seed,
            unitOriginX,
            unitOriginZ,
            parentCoarseW,
            parentCoarseH,
            parentBases,
            key,
            daughterTiles,
            contractedOutletIds,
            regionDirectory,
            probe,
            rawReady
         )
      );
      Future<WatershedPlan> flight = this.pending.putIfAbsent(key, task);
      boolean ownsFlight = flight == null;
      if (ownsFlight) {
         flight = task;
         task.run();
      }

      try {
         return flight.get();
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         throw new IllegalStateException("interrupted while joining watershed flight", interrupted);
      } catch (ExecutionException failed) {
         Throwable cause = failed.getCause();
         if (cause instanceof IOException io) {
            throw io;
         } else if (cause instanceof RuntimeException runtime) {
            throw runtime;
         } else if (cause instanceof Error error) {
            throw error;
         } else {
            throw new RuntimeException(cause);
         }
      } finally {
         if (ownsFlight) {
            this.pending.remove(key, task);
         }
      }
   }

   public boolean loadCommitted(WatershedStore.UnitKey key, List<TerrainTileKey> daughterTiles, Set<Long> contractedOutletIds) throws IOException {
      WorldSession.requireActive(this.ticket);
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(daughterTiles, "daughterTiles");
      Objects.requireNonNull(contractedOutletIds, "contractedOutletIds");

      WatershedPlan plan;
      SeamContract seam;
      try {
         Optional<WatershedPlan> saved = this.store.loadUnit(key);
         if (saved.isEmpty()) {
            this.evict(key);
            return false;
         }

         this.store.requireUnitDecisions(key, daughterTiles);
         plan = saved.get();
         seam = SeamContract.of(plan, key.tileSizeBlocks());
         new CrossTileRiverTracer(plan, seam).verifyCompleteDrainage(contractedOutletIds);
      } catch (IOException | RuntimeException lost) {
         this.evict(key);
         throw lost;
      }

      this.register(key, plan, seam, daughterTiles, contractedOutletIds);
      return true;
   }

   private WatershedPlan publishLocked(
      long seed,
      int unitOriginX,
      int unitOriginZ,
      int parentCoarseW,
      int parentCoarseH,
      Map<HydroPrepareService.ParentCoord, int[][]> parentBases,
      WatershedStore.UnitKey key,
      List<TerrainTileKey> daughterTiles,
      Set<Long> contractedOutletIds,
      Path regionDirectory,
      GeneratedChunkGuard.RuntimeChunkProbe probe,
      HydroUnitGate.RawReady rawReady
   ) throws IOException {
      WorldSession.requireActive(this.ticket);
      boolean allRawReady = true;

      for (TerrainTileKey tile : daughterTiles) {
         WorldSession.requireActive(this.ticket);
         if (!rawReady.isReady(tile)) {
            allRawReady = false;
            break;
         }
      }

      if (!allRawReady) {
         if (!Files.exists(this.store.unitSidecarPath(key)) && !this.store.hasUnitJournal(key)) {
            return null;
         } else {
            throw new IllegalStateException("Frozen watershed has missing raw terrain: " + key);
         }
      } else {
         for (long[] extent : coveringExtents(key)) {
            WorldSession.requireActive(this.ticket);
            GeneratedChunkGuard.Result barrier = GeneratedChunkGuard.check(regionDirectory, (int)extent[0], (int)extent[1], probe);
            if (!barrier.safeToPublish()) {
               throw new IllegalStateException("Watershed frontier has generated chunks at extent (" + extent[0] + "," + extent[1] + "): " + barrier.reason());
            }
         }

         WorldSession.requireActive(this.ticket);
         WatershedPlan plan = this.store
            .publishUnit(seed, unitOriginX, unitOriginZ, parentCoarseW, parentCoarseH, parentBases, key, daughterTiles, contractedOutletIds);
         this.store.requireUnitDecisions(key, daughterTiles);
         SeamContract seam = SeamContract.of(plan, key.tileSizeBlocks());
         this.register(key, plan, seam, daughterTiles, contractedOutletIds);
         return plan;
      }
   }

   private void register(WatershedStore.UnitKey key, WatershedPlan plan, SeamContract seam, List<TerrainTileKey> daughters, Set<Long> contracted) {
      WorldSession.requireActive(this.ticket);
      Set<Long> tileCodes = new HashSet<>();

      for (TerrainTileKey tile : daughters) {
         tileCodes.add((long)tile.tileZ() << 32 | tile.tileX() & 4294967295L);
      }

      int tileSize = key.tileSizeBlocks();
      HydroPrepareService service = new HydroPrepareService(
         plan, seam, (tileX, tileZ) -> tileCodes.contains((long)tileZ.intValue() << 32 | tileX.intValue() & 4294967295L) && tileSize == key.tileSizeBlocks()
      );
      WorldSession.RiverOverlay overlay = service.asOverlay();
      synchronized (this.activeLock) {
         Map<WatershedStore.UnitKey, HydroUnitGate.Published> next = new HashMap<>(this.active);
         next.put(key, new HydroUnitGate.Published(plan, seam, service, overlay, List.copyOf(daughters), Set.copyOf(contracted)));
         this.active = Map.copyOf(next);
      }

      this.revalidateActive();
   }

   private void evict(WatershedStore.UnitKey key) {
      synchronized (this.activeLock) {
         Map<WatershedStore.UnitKey, HydroUnitGate.Published> next = new HashMap<>(this.active);
         if (next.remove(key) != null) {
            this.active = Map.copyOf(next);
         }
      }
   }

   public void revalidateActive() {
      List<Entry<WatershedStore.UnitKey, HydroUnitGate.Published>> snapshot;
      synchronized (this.activeLock) {
         snapshot = List.copyOf(this.active.entrySet());
      }

      for (Entry<WatershedStore.UnitKey, HydroUnitGate.Published> entry : snapshot) {
         WatershedStore.UnitKey key = entry.getKey();
         HydroUnitGate.Published published = entry.getValue();

         try {
            Optional<WatershedPlan> saved = this.store.loadUnit(key);
            if (saved.isEmpty()) {
               this.evict(key);
            } else {
               this.store.requireUnitDecisions(key, published.daughters());
               SeamContract seam = SeamContract.of(saved.get(), key.tileSizeBlocks());
               new CrossTileRiverTracer(saved.get(), seam).verifyCompleteDrainage(published.contracted());
            }
         } catch (IOException | RuntimeException lost) {
            this.evict(key);
         }
      }
   }

   private static boolean intersects(WatershedStore.UnitKey key, long chunkX0, long chunkZ0, long chunkX1, long chunkZ1) {
      long unitX0 = key.originX();
      long unitZ0 = key.originZ();
      long unitX1 = unitX0 + key.width() * 2L;
      long unitZ1 = unitZ0 + key.height() * 2L;
      return chunkX0 < unitX1 && chunkX1 > unitX0 && chunkZ0 < unitZ1 && chunkZ1 > unitZ0;
   }

   private static List<long[]> coveringExtents(WatershedStore.UnitKey key) {
      long unitX0 = key.originX();
      long unitZ0 = key.originZ();
      long unitX1 = unitX0 + key.width() * 2L;
      long unitZ1 = unitZ0 + key.height() * 2L;
      long extX0 = Math.floorDiv(unitX0, 512) * 512L;
      long extZ0 = Math.floorDiv(unitZ0, 512) * 512L;
      long extX1 = Math.floorDiv(unitX1 - 1L, 512) * 512L;
      long extZ1 = Math.floorDiv(unitZ1 - 1L, 512) * 512L;
      ArrayList<long[]> extents = new ArrayList<>();

      for (long z = extZ0; z <= extZ1; z += 512L) {
         for (long x = extX0; x <= extX1; x += 512L) {
            extents.add(new long[]{x, z});
         }
      }

      return List.copyOf(extents);
   }

   public record GateView(HydroUnitGate.Verdict verdict, String progress, WorldSession.RiverOverlay overlay) {
      public GateView {
         Objects.requireNonNull(verdict, "verdict");
         Objects.requireNonNull(progress, "progress");
      }
   }

   private record Published(
      WatershedPlan plan,
      SeamContract seam,
      HydroPrepareService service,
      WorldSession.RiverOverlay overlay,
      List<TerrainTileKey> daughters,
      Set<Long> contracted
   ) {
   }

   @FunctionalInterface
   public interface RawReady {
      boolean isReady(TerrainTileKey var1);
   }

   public enum Verdict {
      READY,
      DEFER;
   }
}

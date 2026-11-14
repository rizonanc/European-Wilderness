package com.github.xandergos.terraindiffusionmc.worldgen.terrain;

import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.GenerationMetrics;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

public final class TerrainRepository {
   public static final long DEFAULT_RAM_BUDGET_BYTES = 67108864L;
   private final DiskTerrainStore store;
   private final TerrainRepository.TileLoader loader;
   private final int tileSize;
   private final long maxRamBytes;
   private final long diskQuotaBytes;
   private final WorldSession owner;
   private final Object ramLock = new Object();
   private final LinkedHashMap<TerrainTileKey, TerrainTile> ram = new LinkedHashMap<>(64, 0.75F, true);
   private long ramBytes = 0L;
   private final ConcurrentHashMap<TerrainTileKey, PendingTile> pending = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<TerrainTileKey, PendingRead> diskReads = new ConcurrentHashMap<>();
   private final class PendingRead extends FutureTask<Optional<TerrainTile>> {
      final TerrainTileKey key;
      PendingRead(TerrainTileKey key) {
         super(() -> {
            requireOwnerActive();
            var cached = peekRam(key);
            if (cached.isPresent()) return cached;
            var disk = store.load(key);
            requireOwnerActive();
            if (disk.isPresent()) {
               GenerationMetrics.countRepoDiskHit();
               // Publish before completion/removal: a late RAM miss must not launch another decode.
               ramPut(disk.get());
            }
            return disk;
         });
         this.key = key;
      }
      @Override protected void done() { diskReads.remove(key, this); }
   }
   private static final class PendingTile extends FutureTask<TerrainTile> {
      final com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand demand;
      PendingTile(java.util.concurrent.Callable<TerrainTile> loader){this(com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.current(),loader);}
      private PendingTile(com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand demand,java.util.concurrent.Callable<TerrainTile> loader){
         super(()->com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.call(demand,loader));this.demand=demand;}
   }

   public TerrainRepository(DiskTerrainStore store, TerrainRepository.TileLoader loader, int tileSize, long maxRamBytes, long diskQuotaBytes) {
      this(store, loader, tileSize, maxRamBytes, diskQuotaBytes, null);
   }

   public TerrainRepository(
      DiskTerrainStore store, TerrainRepository.TileLoader loader, int tileSize, long maxRamBytes, long diskQuotaBytes, WorldSession owner
   ) {
      if (store == null || loader == null) {
         throw new IllegalArgumentException("store and loader must not be null");
      }

      if (tileSize < 16 || tileSize > 1024) {
         throw new IllegalArgumentException("tileSize out of range: " + tileSize);
      }

      if (maxRamBytes > 0L && diskQuotaBytes > 0L) {
         this.store = store;
         this.loader = loader;
         this.tileSize = tileSize;
         this.maxRamBytes = maxRamBytes;
         this.diskQuotaBytes = diskQuotaBytes;
         this.owner = owner;
      } else {
         throw new IllegalArgumentException("budgets must be positive");
      }
   }

   public int tileSize() {
      return this.tileSize;
   }

   public DiskTerrainStore store() {
      return this.store;
   }

   public TerrainTile get(TerrainTileKey key) throws Exception {
      this.requireOwnerActive();
      Optional<TerrainTile> ready = this.lookupReadyInterruptibly(key);
      if (ready.isPresent()) {
         return ready.get();
      }

      long estimateBytes = estimateBytes(key);
      // Admission is shared by every repository/store for this cache directory.
      var reservation = new java.util.concurrent.atomic.AtomicReference<DiskTerrainStore.Reservation>();
      PendingTile task = new PendingTile(() -> {
         try (var admitted = reservation.get()) {
            var cached = peekRam(key);
            if (cached.isPresent()) return cached.get();
            GenerationMetrics.countRepoComputed();
            TerrainTile computed = this.loader.load(key);
            if (computed != null && computed.key().equals(key)) {
               this.requireOwnerActive();
               this.store.store(key, computed, admitted);
               return computed;
            } else {
               throw new IllegalStateException("Tile loader returned foreign data for " + key);
            }
         }
      });
      PendingTile flight = this.pending.putIfAbsent(key, task);
      if (flight == null) {
         flight = task;

         try {
            var admitted = this.store.tryReserve(estimateBytes, this.diskQuotaBytes);
            if (admitted == null) throw new DiskQuotaExceededException("Disk quota exceeded: " + store.quotaStatistics()
               + ", quota " + diskQuotaBytes + ", refusing to compute " + key + ". Stored tiles were left untouched.");
            reservation.set(admitted);
         } catch (IOException | RuntimeException admissionFailed) {
            emit(Code.CAC_QUOTA,DiagnosticErrors.context("CACHE_ADMISSION","tile=("+key.tileX()+","+key.tileZ()+")"),admissionFailed,Outcome.FAILED,"No generation started; stored tiles unchanged",true);
            this.pending.remove(key, task);
            task.cancel(false);
            throw admissionFailed;
         }

         task.run();
      }

      try (var inherited=flight.demand.inheritPriorityFrom(com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.current())) {
         TerrainTile result = flight.get();
         this.requireOwnerActive();
         this.pending.remove(key, flight);
         this.ramPut(result);
         return result;
      } catch (Exception failed) {
         DiagnosticErrors.failed(Code.CAC_FLIGHT,"TILE_SINGLE_FLIGHT",failed);
         if(flight.isDone())this.pending.remove(key, flight);
         throw failed;
      }
   }

   public Optional<TerrainTile> peek(TerrainTileKey key) {
      this.requireOwnerActive();
      boolean interrupted = false;
      try {
         while (true) try { return lookupReadyInterruptibly(key); }
            catch (InterruptedException retry) { interrupted = true; }
      } finally { if (interrupted) Thread.currentThread().interrupt(); }
   }

   private void requireOwnerActive() {
      if (this.owner != null) {
         WorldSession.requireActive(this.owner);
      }
   }

   /** A true RAM-only probe: no IO, scheduling or inference. */
   public Optional<TerrainTile> peekRam(TerrainTileKey key) {
      this.requireOwnerActive();
      if (key.tileSize() != this.tileSize) {
         throw new IllegalArgumentException("key tile size " + key.tileSize() + " does not match repository " + this.tileSize);
      }

      synchronized (this.ramLock) {
         TerrainTile cached = this.ram.get(key);
         if (cached != null) {
            GenerationMetrics.countRepoRamHit();
            return Optional.of(cached);
         }
      }

      return Optional.empty();
   }

   private Optional<TerrainTile> lookupReadyInterruptibly(TerrainTileKey key) throws InterruptedException {
      var cached = peekRam(key);
      if (cached.isPresent()) return cached;
      var candidate = new PendingRead(key);
      var flight = diskReads.putIfAbsent(key, candidate);
      if (flight == null) { flight = candidate; flight.run(); }
      try {
         var result = flight.get();
         requireOwnerActive();
         return result;
      } catch (java.util.concurrent.ExecutionException failure) {
         if (failure.getCause() instanceof Error error) throw error;
         if (failure.getCause() instanceof RuntimeException error) throw error;
         throw new IllegalStateException("Disk tile publication failed", failure.getCause());
      }
   }

   static long estimateBytes(TerrainTileKey key) {
      return DiskTerrainStore.worstCaseEncodedSize(key);
   }

   private void ramPut(TerrainTile tile) {
      synchronized (this.ramLock) {
         TerrainTile previous = this.ram.put(tile.key(), tile);
         if (previous != null) {
            this.ramBytes = this.ramBytes - previous.ramBytes();
         }

         this.ramBytes = this.ramBytes + tile.ramBytes();
         Iterator<Entry<TerrainTileKey, TerrainTile>> eldest = this.ram.entrySet().iterator();

         while (this.ramBytes > this.maxRamBytes && this.ram.size() > 1 && eldest.hasNext()) {
            TerrainTile evicted = eldest.next().getValue();
            eldest.remove();
            this.ramBytes = this.ramBytes - evicted.ramBytes();
         }
      }
   }

   long ramBytesUsed() {
      synchronized (this.ramLock) {
         return this.ramBytes;
      }
   }

   public static final class DiskQuotaExceededException extends IOException {
      public DiskQuotaExceededException(String message) {
         super(message);
      }
   }

   @FunctionalInterface
   public interface TileLoader {
      TerrainTile load(TerrainTileKey var1) throws Exception;
   }
}

package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

public final class MountainRegionCache {
   public static final long DEFAULT_MAX_BYTES = 33554432L;
   public static final int MAX_ENTRIES = 4096;
   private final long maxBytes;
   private final LinkedHashMap<MountainRegionCache.Key, MountainSurfaceRegion> entries = new LinkedHashMap<>(64, 0.75F, true);
   private final ConcurrentHashMap<MountainRegionCache.Key, Future<MountainSurfaceRegion>> pending = new ConcurrentHashMap<>();
   private long usedBytes;

   public MountainRegionCache() {
      this(33554432L);
   }

   public MountainRegionCache(long maxBytes) {
      if (maxBytes <= 0L) {
         throw new IllegalArgumentException("maxBytes must be positive");
      }

      this.maxBytes = maxBytes;
   }

   public synchronized Optional<MountainSurfaceRegion> get(MountainRegionCache.Key key) {
      return Optional.ofNullable(this.entries.get(Objects.requireNonNull(key, "key")));
   }

   public void put(MountainRegionCache.Key key, int[][] surfaceY) {
      Objects.requireNonNull(key, "key");
      long payloadBytes = payloadBytes(key);
      if (payloadBytes > this.maxBytes) {
         throw new IllegalArgumentException("region requires " + payloadBytes + " bytes, cache budget is " + this.maxBytes);
      }

      MountainSurfaceRegion.validateShape(surfaceY, key.width(), key.height());
      MountainSurfaceRegion owned = MountainSurfaceRegion.copyOf(surfaceY, key.width(), key.height());
      this.publish(key, owned);
   }

   public MountainSurfaceRegion getOrCompute(MountainRegionCache.Key key, WorldSession session, MountainRegionCache.SurfaceComputer computer) throws Exception {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(session, "session");
      Objects.requireNonNull(computer, "computer");
      MountainSurfaceRegion cached = this.get(key).orElse(null);
      if (cached != null) {
         return cached;
      }

      FutureTask<MountainSurfaceRegion> task = new FutureTask<>(() -> {
         MountainSurfaceRegion raced = this.get(key).orElse(null);
         if (raced != null) {
            return raced;
         }

         MountainSurfaceRegion computed = this.computeUncached(key, computer);
         WorldSession.requireActive(session);
         if (computed.payloadBytes() <= this.maxBytes) {
            this.publishForSession(key, computed, session);
         }

         return computed;
      });
      Future<MountainSurfaceRegion> flight = this.pending.putIfAbsent(key, task);
      boolean ownsFlight = flight == null;
      if (ownsFlight) {
         flight = task;
         task.run();
      }

      try {
         return flight.get();
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         throw interrupted;
      } catch (ExecutionException failed) {
         Throwable cause = failed.getCause();
         if (cause instanceof Exception exception) {
            throw exception;
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

   public MountainSurfaceRegion computeUncached(MountainRegionCache.Key key, MountainRegionCache.SurfaceComputer computer) throws Exception {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(computer, "computer");
      MountainSurfaceRegion computed = Objects.requireNonNull(computer.compute(), "surface computer returned null");
      if (computed.width() == key.width() && computed.height() == key.height()) {
         return computed;
      } else {
         throw new IllegalArgumentException(
            "computed surface " + computed.width() + "x" + computed.height() + " does not match key " + key.width() + "x" + key.height()
         );
      }
   }

   public synchronized long usedBytes() {
      return this.usedBytes;
   }

   public synchronized int entryCount() {
      return this.entries.size();
   }

   private synchronized void publishForSession(MountainRegionCache.Key key, MountainSurfaceRegion value, WorldSession session) {
      WorldSession.requireActive(session);
      this.publishLocked(key, value);
   }

   private synchronized void publish(MountainRegionCache.Key key, MountainSurfaceRegion value) {
      this.publishLocked(key, value);
   }

   private void publishLocked(MountainRegionCache.Key key, MountainSurfaceRegion value) {
      MountainSurfaceRegion previous = this.entries.remove(key);
      if (previous != null) {
         this.usedBytes = this.usedBytes - previous.payloadBytes();
      }

      Iterator<Entry<MountainRegionCache.Key, MountainSurfaceRegion>> eldest = this.entries.entrySet().iterator();

      while ((this.usedBytes > this.maxBytes - value.payloadBytes() || this.entries.size() >= 4096) && eldest.hasNext()) {
         MountainSurfaceRegion evicted = eldest.next().getValue();
         eldest.remove();
         this.usedBytes = this.usedBytes - evicted.payloadBytes();
      }

      this.entries.put(key, value);
      this.usedBytes = this.usedBytes + value.payloadBytes();
   }

   private static long payloadBytes(MountainRegionCache.Key key) {
      return MountainSurfaceRegion.payloadBytes(key.width(), key.height());
   }

   public record Key(long seed, String fingerprint, int scale, int startX, int startZ, int width, int height, int surfaceCapY) {
      public Key(long seed, String fingerprint, int scale, int startX, int startZ, int width, int height, int surfaceCapY) {
         if (fingerprint == null || fingerprint.isBlank()) {
            throw new IllegalArgumentException("fingerprint must be non-blank");
         }

         if (scale < 1 || scale > 6) {
            throw new IllegalArgumentException("scale must be 1..6, got " + scale);
         }

         if (width > 0 && height > 0) {
            MountainSurfaceRegion.payloadBytes(width, height);
            this.seed = seed;
            this.fingerprint = fingerprint;
            this.scale = scale;
            this.startX = startX;
            this.startZ = startZ;
            this.width = width;
            this.height = height;
            this.surfaceCapY = surfaceCapY;
         } else {
            throw new IllegalArgumentException("region dimensions must be positive, got " + width + "x" + height);
         }
      }
   }

   @FunctionalInterface
   public interface SurfaceComputer {
      MountainSurfaceRegion compute() throws Exception;
   }
}

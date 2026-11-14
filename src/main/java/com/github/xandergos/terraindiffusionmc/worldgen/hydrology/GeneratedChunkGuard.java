package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class GeneratedChunkGuard {
   public static final int EXTENT_BLOCKS = 512;
   private static final int CHUNK_BLOCKS = 16;
   private static final int CHUNKS_PER_REGION = 32;
   private static final int SECTOR_BYTES = 4096;
   private static final int HEADER_BYTES = 8192;

   private GeneratedChunkGuard() {
   }

   public static GeneratedChunkGuard.Result check(Path regionDirectory, int originBlockX, int originBlockZ, GeneratedChunkGuard.RuntimeChunkProbe runtimeProbe) {
      Objects.requireNonNull(regionDirectory, "regionDirectory");
      Objects.requireNonNull(runtimeProbe, "runtimeProbe");
      if (Math.floorMod(originBlockX, 512) == 0 && Math.floorMod(originBlockZ, 512) == 0) {
         int regionX = Math.floorDiv(originBlockX, 512);
         int regionZ = Math.floorDiv(originBlockZ, 512);
         Path region = regionDirectory.resolve("r." + regionX + "." + regionZ + ".mca");
         GeneratedChunkGuard.Result disk = scanRegion(regionDirectory, region);
         if (disk.status() != GeneratedChunkGuard.Status.CLEAR) {
            return disk;
         }

         int firstChunkX = Math.floorDiv(originBlockX, 16);
         int firstChunkZ = Math.floorDiv(originBlockZ, 16);

         for (int dz = 0; dz < 32; dz++) {
            for (int dx = 0; dx < 32; dx++) {
               int chunkX = firstChunkX + dx;
               int chunkZ = firstChunkZ + dz;

               GeneratedChunkGuard.RuntimeState state;
               try {
                  state = runtimeProbe.state(chunkX, chunkZ);
               } catch (RuntimeException error) {
                  return new GeneratedChunkGuard.Result(
                     GeneratedChunkGuard.Status.INDETERMINATE,
                     "runtime chunk probe failed at " + chunkX + "," + chunkZ + ": " + error.getClass().getSimpleName()
                  );
               }

               if (state == GeneratedChunkGuard.RuntimeState.PRESENT_OR_IN_FLIGHT) {
                  return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.CHUNK_PRESENT, "loaded or in-flight chunk at " + chunkX + "," + chunkZ);
               }

               if (state != GeneratedChunkGuard.RuntimeState.ABSENT) {
                  return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "runtime chunk state unknown at " + chunkX + "," + chunkZ);
               }
            }
         }

         return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.CLEAR, "no indexed, loaded, or in-flight chunks in extent");
      } else {
         throw new IllegalArgumentException("extent origin must align to 512 blocks");
      }
   }

   private static GeneratedChunkGuard.Result scanRegion(Path regionDirectory, Path region) {
      try {
         BasicFileAttributes directoryAttributes = Files.readAttributes(regionDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         if (!directoryAttributes.isDirectory()) {
            return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "region directory is not a directory");
         }
      } catch (NoSuchFileException absent) {
         return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.CLEAR, "region directory absent");
      } catch (IOException | SecurityException error) {
         return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "cannot inspect region directory: " + error.getClass().getSimpleName());
      }

      try {
         BasicFileAttributes attributes = Files.readAttributes(region, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         if (!attributes.isRegularFile()) {
            return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "region path is not a regular file");
         }
      } catch (NoSuchFileException absent) {
         return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.CLEAR, "region file absent");
      } catch (IOException | SecurityException error) {
         return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "cannot inspect region file: " + error.getClass().getSimpleName());
      }

      try (FileChannel channel = FileChannel.open(region, StandardOpenOption.READ)) {
         long length = channel.size();
         if (length >= 8192L && length % 4096L == 0L) {
            ByteBuffer header = ByteBuffer.allocate(8192);

            while (header.hasRemaining()) {
               if (channel.read(header) <= 0) {
                  return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "truncated Anvil region header");
               }
            }

            header.flip();

            for (int slot = 0; slot < 1024; slot++) {
               int location = header.getInt(slot * 4);
               int timestamp = header.getInt(4096 + slot * 4);
               if (location != 0) {
                  return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.CHUNK_PRESENT, "indexed chunk in region slot " + slot);
               }

               if (timestamp != 0) {
                  return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "orphaned timestamp in region slot " + slot);
               }
            }

            return length != 8192L
               ? new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "region has unindexed payload sectors")
               : new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.CLEAR, "empty Anvil region header");
         } else {
            return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "invalid Anvil region length: " + length);
         }
      } catch (IOException | SecurityException error) {
         return new GeneratedChunkGuard.Result(GeneratedChunkGuard.Status.INDETERMINATE, "cannot read region: " + error.getClass().getSimpleName());
      }
   }

   public record Result(GeneratedChunkGuard.Status status, String reason) {
      public Result {
         Objects.requireNonNull(status, "status");
         Objects.requireNonNull(reason, "reason");
      }

      public boolean safeToPublish() {
         return this.status == GeneratedChunkGuard.Status.CLEAR;
      }
   }

   @FunctionalInterface
   public interface RuntimeChunkProbe {
      GeneratedChunkGuard.RuntimeState state(int var1, int var2);
   }

   public enum RuntimeState {
      ABSENT,
      PRESENT_OR_IN_FLIGHT,
      UNKNOWN;
   }

   public enum Status {
      CLEAR,
      CHUNK_PRESENT,
      INDETERMINATE;
   }
}

package com.github.xandergos.terraindiffusionmc.worldgen.terrain;

import java.util.Objects;

public final class TerrainTileKey {
   public static final int MIN_TILE_SIZE = 16;
   public static final int MAX_TILE_SIZE = 1024;
   private final long seed;
   private final int tileX;
   private final int tileZ;
   private final int tileSize;
   private final String fingerprint;
   private final int hashCode;

   private TerrainTileKey(long seed, int tileX, int tileZ, int tileSize, String fingerprint) {
      if (tileSize < 16 || tileSize > 1024) {
         throw new IllegalArgumentException("tileSize must be 16..1024, got " + tileSize);
      }

      if (fingerprint != null && !fingerprint.isBlank()) {
         this.seed = seed;
         this.tileX = tileX;
         this.tileZ = tileZ;
         this.tileSize = tileSize;
         this.fingerprint = fingerprint;
         this.hashCode = Objects.hash(seed, tileX, tileZ, tileSize, fingerprint);
      } else {
         throw new IllegalArgumentException("fingerprint must be non-blank");
      }
   }

   public static TerrainTileKey forBlock(int blockX, int blockZ, int tileSize, long seed, String fingerprint) {
      return new TerrainTileKey(seed, Math.floorDiv(blockX, tileSize), Math.floorDiv(blockZ, tileSize), tileSize, fingerprint);
   }

   public static TerrainTileKey ofTile(long seed, int tileX, int tileZ, int tileSize, String fingerprint) {
      return new TerrainTileKey(seed, tileX, tileZ, tileSize, fingerprint);
   }

   public int blockStartX() {
      return Math.multiplyExact(this.tileX, this.tileSize);
   }

   public int blockStartZ() {
      return Math.multiplyExact(this.tileZ, this.tileSize);
   }

   public int localX(int blockX) {
      return blockX - this.blockStartX();
   }

   public int localZ(int blockZ) {
      return blockZ - this.blockStartZ();
   }

   public long seed() {
      return this.seed;
   }

   public int tileX() {
      return this.tileX;
   }

   public int tileZ() {
      return this.tileZ;
   }

   public int tileSize() {
      return this.tileSize;
   }

   public String fingerprint() {
      return this.fingerprint;
   }

   public String fileName() {
      return "t_" + this.seed + "_" + this.tileX + "_" + this.tileZ + ".ewt";
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else {
         return !(o instanceof TerrainTileKey other)
            ? false
            : this.seed == other.seed
               && this.tileX == other.tileX
               && this.tileZ == other.tileZ
               && this.tileSize == other.tileSize
               && this.fingerprint.equals(other.fingerprint);
      }
   }

   @Override
   public int hashCode() {
      return this.hashCode;
   }

   @Override
   public String toString() {
      return "TerrainTileKey[seed="
         + this.seed
         + ", x="
         + this.tileX
         + ", z="
         + this.tileZ
         + ", size="
         + this.tileSize
         + ", fp="
         + this.fingerprint.substring(0, Math.min(12, this.fingerprint.length()))
         + "]";
   }
}

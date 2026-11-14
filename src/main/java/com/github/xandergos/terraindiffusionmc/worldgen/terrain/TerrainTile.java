package com.github.xandergos.terraindiffusionmc.worldgen.terrain;

import java.util.Arrays;

public final class TerrainTile {
   public static final int CLIMATE_CHANNELS = 4;
   private final TerrainTileKey key;
   private final short[] heightsMeters;
   private final short[] biomeIds;
   private final float[] climate;

   public TerrainTile(TerrainTileKey key, short[] heightsMeters, short[] biomeIds) {
      this(key, heightsMeters, biomeIds, null);
   }

   public TerrainTile(TerrainTileKey key, short[] heightsMeters, short[] biomeIds, float[] climate) {
      if (key == null) {
         throw new IllegalArgumentException("key must not be null");
      }

      int expected = key.tileSize() * key.tileSize();
      if (heightsMeters == null || heightsMeters.length != expected) {
         throw new IllegalArgumentException("heightsMeters must hold exactly " + expected + " values for tile size " + key.tileSize());
      }

      if (biomeIds == null || biomeIds.length != expected) {
         throw new IllegalArgumentException("biomeIds must hold exactly " + expected + " values for tile size " + key.tileSize());
      }

      if (climate != null && climate.length != 4 * expected) {
         throw new IllegalArgumentException("climate must hold exactly " + 4 * expected + " values for tile size " + key.tileSize());
      }

      this.key = key;
      this.heightsMeters = (short[])heightsMeters.clone();
      this.biomeIds = (short[])biomeIds.clone();
      this.climate = climate == null ? null : (float[])climate.clone();
   }

   public TerrainTileKey key() {
      return this.key;
   }

   public short[] heightsMeters() {
      return (short[])this.heightsMeters.clone();
   }

   public short[] biomeIds() {
      return (short[])this.biomeIds.clone();
   }

   public float[] climate() {
      return this.climate == null ? null : (float[])this.climate.clone();
   }

   public boolean hasClimate() {
      return this.climate != null;
   }

   public float climateAt(int channel, int localX, int localZ) {
      int size = this.key.tileSize();
      if (this.climate == null) {
         throw new IllegalStateException("Tile has no climate layer");
      } else if (channel >= 0 && channel < 4 && localX >= 0 && localZ >= 0 && localX < size && localZ < size) {
         return this.climate[channel * size * size + localZ * size + localX];
      } else {
         throw new IllegalArgumentException("Invalid climate coordinates");
      }
   }

   public short heightAt(int localX, int localZ) {
      return this.heightsMeters[localZ * this.key.tileSize() + localX];
   }

   public short biomeAt(int localX, int localZ) {
      return this.biomeIds[localZ * this.key.tileSize() + localX];
   }

   public long ramBytes() {
      return 64L + 2L * this.heightsMeters.length + 2L * this.biomeIds.length + (this.climate == null ? 0L : 4L * this.climate.length);
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else {
         return !(o instanceof TerrainTile other)
            ? false
            : this.key.equals(other.key)
               && Arrays.equals(this.heightsMeters, other.heightsMeters)
               && Arrays.equals(this.biomeIds, other.biomeIds)
               && Arrays.equals(this.climate, other.climate);
      }
   }

   @Override
   public int hashCode() {
      int result = this.key.hashCode();
      result = 31 * result + Arrays.hashCode(this.heightsMeters);
      result = 31 * result + Arrays.hashCode(this.biomeIds);
      return 31 * result + Arrays.hashCode(this.climate);
   }
}

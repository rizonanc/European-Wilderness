package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

public final class WorldHeightProfile {
   public static final int MIN_Y = -64;
   public static final int FEATURE_HEADROOM = 96;

   private WorldHeightProfile() {
   }

   public static int dimensionHeightForScale(int scale) {
      return switch (scale) {
         case 1 -> 464;
         case 2 -> 2032;
         case 3 -> 1136;
         case 4 -> 1472;
         case 5 -> 1808;
         case 6 -> 2032;
         default -> throw new IllegalArgumentException("Unsupported world scale: " + scale);
      };
   }

   public static int topYInclusiveForScale(int scale) {
      return Math.addExact(-64, dimensionHeightForScale(scale)) - 1;
   }

   public static int surfaceCapYForScale(int scale) {
      return surfaceCapY(topYInclusiveForScale(scale));
   }

   public static int surfaceCapY(int topYInclusive) {
      return Math.subtractExact(topYInclusive, 96);
   }
}

package com.github.xandergos.terraindiffusionmc.pipeline;

public final class PortableRng {
   private static final long PCG64_MULT = 6364136223846793005L;
   private static final long PCG64_INC = 1442695040888963407L;
   private static final long MASK64 = -1L;
   private static final double INV_2P32 = 2.3283064E-10F;

   public static long tileSeed(long baseSeed, int ty, int tx) {
      long h = (baseSeed & -1L) * 2654435769L;
      h = h + (ty & 4294967295L) & -1L;
      return h * 2654435769L + (tx & 4294967295L) & -1L;
   }

   public static long[] pcg64Next(long state) {
      state = state * 6364136223846793005L + 1442695040888963407L & -1L;
      long x = (state >>> 18 ^ state) >>> 27 & 4294967295L;
      int rot = (int)(state >>> 59);
      long out32 = (x >>> rot | x << (32 - rot & 31)) & 4294967295L;
      return new long[]{state, out32};
   }

   public static void fillStandardNormal(long seed, float[] out, int offset, int length) {
      long state = seed & -1L;
      int i = 0;

      while (i < length) {
         long[] r1 = pcg64Next(state);
         state = r1[0];
         long[] r2 = pcg64Next(state);
         state = r2[0];
         double v1 = 2.0 * (r1[1] + 1.0) * 2.3283064E-10F - 1.0;
         double v2 = 2.0 * (r2[1] + 1.0) * 2.3283064E-10F - 1.0;
         double s = v1 * v1 + v2 * v2;
         if (s > 0.0 && s < 1.0) {
            double f = Math.sqrt(-2.0 * Math.log(s) / s);
            out[offset + i] = (float)(v1 * f);
            if (++i < length) {
               out[offset + i] = (float)(v2 * f);
               i++;
            }
         }
      }
   }
}

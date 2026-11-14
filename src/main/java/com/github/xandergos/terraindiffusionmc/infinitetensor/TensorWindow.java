package com.github.xandergos.terraindiffusionmc.infinitetensor;

public class TensorWindow {
   public final int[] size;
   public final int[] stride;
   public final int[] offset;

   public TensorWindow(int[] size, int[] stride, int[] offset) {
      this.size = (int[])size.clone();
      this.stride = (int[])stride.clone();
      this.offset = (int[])offset.clone();
   }

   public TensorWindow(int[] size) {
      this.size = (int[])size.clone();
      this.stride = (int[])size.clone();
      this.offset = new int[size.length];
   }

   public TensorWindow(int[] size, int[] stride) {
      this.size = (int[])size.clone();
      this.stride = (int[])stride.clone();
      this.offset = new int[size.length];
   }

   public int ndim() {
      return this.size.length;
   }

   public int[][] getBounds(int[] windowIndex) {
      int n = this.size.length;
      int[][] bounds = new int[n][2];

      for (int i = 0; i < n; i++) {
         bounds[i][0] = windowIndex[i] * this.stride[i] + this.offset[i];
         bounds[i][1] = windowIndex[i] * this.stride[i] + this.offset[i] + this.size[i];
      }

      return bounds;
   }

   public int[] getLowestIntersection(int[][] pixelRange) {
      int n = this.size.length;
      int[] result = new int[n];

      for (int i = 0; i < n; i++) {
         int p = pixelRange[i][0];
         int numerator = p - this.offset[i] - this.size[i] + 1;
         if (numerator >= 0) {
            result[i] = (numerator + this.stride[i] - 1) / this.stride[i];
         } else {
            result[i] = -(-numerator / this.stride[i]);
         }
      }

      return result;
   }

   public int[] getHighestIntersection(int[][] pixelRange) {
      int n = this.size.length;
      int[] result = new int[n];

      for (int i = 0; i < n; i++) {
         int p = pixelRange[i][1] - 1;
         result[i] = Math.floorDiv(p - this.offset[i], this.stride[i]);
      }

      return result;
   }
}

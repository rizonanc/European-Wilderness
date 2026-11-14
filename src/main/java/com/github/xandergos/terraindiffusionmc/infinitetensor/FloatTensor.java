package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.Arrays;

public class FloatTensor {
   public final int[] shape;
   public final float[] data;
   final int[] strides;

   public FloatTensor(int[] shape) {
      this.shape = (int[])shape.clone();
      int total = 1;

      for (int d : shape) {
         total *= d;
      }

      this.data = new float[total];
      this.strides = computeStrides(shape);
   }

   public FloatTensor(int[] shape, float[] data) {
      this.shape = (int[])shape.clone();
      this.data = data;
      this.strides = computeStrides(shape);
   }

   static int[] computeStrides(int[] shape) {
      int n = shape.length;
      int[] s = new int[n];
      int stride = 1;

      for (int i = n - 1; i >= 0; i--) {
         s[i] = stride;
         stride *= shape[i];
      }

      return s;
   }

   public int ndim() {
      return this.shape.length;
   }

   public long byteSize() {
      return this.data.length * 4L;
   }

   public void addFrom(FloatTensor src, int[][] dstRegion, int[][] srcRegion) {
      int n=shape.length;
      if(src==null||src.shape.length!=n||dstRegion==null||srcRegion==null||dstRegion.length!=n||srcRegion.length!=n||n==0)
         throw new IllegalArgumentException("Incompatible tensor regions");
      long total=1,dstMin=0,dstMax=0,srcMin=0,srcMax=0;
      for(int d=0;d<n;d++){
         if(dstRegion[d]==null||srcRegion[d]==null||dstRegion[d].length!=2||srcRegion[d].length!=2)
            throw new IllegalArgumentException("Each region dimension needs start/end");
         int da=dstRegion[d][0],db=dstRegion[d][1],sa=srcRegion[d][0],sb=srcRegion[d][1];
         if(da<0||sa<0||db<da||sb<sa||db>shape[d]||sb>src.shape[d]||db-da!=sb-sa)
            throw new IllegalArgumentException("Out-of-bounds or unequal tensor regions");
         total=Math.multiplyExact(total,db-da);
         long dl=(long)da*strides[d],dr=(long)(db-1)*strides[d],sl=(long)sa*src.strides[d],sr=(long)(sb-1)*src.strides[d];
         dstMin+=Math.min(dl,dr);dstMax+=Math.max(dl,dr);srcMin+=Math.min(sl,sr);srcMax+=Math.max(sl,sr);
      }
      if(total==0)return;
      if(total>Integer.MAX_VALUE||dstMin<0||srcMin<0||dstMax>=data.length||srcMax>=src.data.length)
         throw new IllegalArgumentException("Tensor strides exceed backing storage");
      if(n<=3&&strides[n-1]==1&&src.strides[n-1]==1){
         int length=dstRegion[n-1][1]-dstRegion[n-1][0];
         if(n==1){addRow(src,dstRegion[0][0],srcRegion[0][0],length);return;}
         int planes=n==3?dstRegion[0][1]-dstRegion[0][0]:1;
         int rows=dstRegion[n-2][1]-dstRegion[n-2][0];
         for(int plane=0;plane<planes;plane++){
            int dp=n==3?(dstRegion[0][0]+plane)*strides[0]:0,sp=n==3?(srcRegion[0][0]+plane)*src.strides[0]:0;
            for(int row=0;row<rows;row++){
               int dst=dp+(dstRegion[n-2][0]+row)*strides[n-2]+dstRegion[n-1][0];
               int source=sp+(srcRegion[n-2][0]+row)*src.strides[n-2]+srcRegion[n-1][0];
               addRow(src,dst,source,length);
            }
         }
         return;
      }
      addFromGeneric(src,dstRegion,srcRegion);
   }

   private void addRow(FloatTensor src,int dst,int source,int length){
      // Forward traversal preserves the original result even for overlapping aliases.
      for(int c=0;c<length;c++)data[dst+c]=data[dst+c]+src.data[source+c];
   }

   void addFromGeneric(FloatTensor src, int[][] dstRegion, int[][] srcRegion) {
      int n = this.shape.length;
      int[] count = new int[n];
      int total = 1;

      for (int d = 0; d < n; d++) {
         count[d] = dstRegion[d][1] - dstRegion[d][0];
         total *= count[d];
      }

      if (total != 0) {
         int[] iterStrides = new int[n];
         iterStrides[n - 1] = 1;

         for (int d = n - 2; d >= 0; d--) {
            iterStrides[d] = iterStrides[d + 1] * count[d + 1];
         }

         for (int flat = 0; flat < total; flat++) {
            int dstFlat = 0;
            int srcFlat = 0;

            for (int d = 0; d < n; d++) {
               int idx = flat / iterStrides[d] % count[d];
               dstFlat += (dstRegion[d][0] + idx) * this.strides[d];
               srcFlat += (srcRegion[d][0] + idx) * src.strides[d];
            }

            this.data[dstFlat] = this.data[dstFlat] + src.data[srcFlat];
         }
      }
   }

   public FloatTensor slice(int[][] region) {
      int n = this.shape.length;
      int[] newShape = new int[n];

      for (int d = 0; d < n; d++) {
         newShape[d] = region[d][1] - region[d][0];
      }

      FloatTensor result = new FloatTensor(newShape);
      int[][] dstRegion = new int[n][2];

      for (int d = 0; d < n; d++) {
         dstRegion[d][0] = 0;
         dstRegion[d][1] = newShape[d];
      }

      result.addFrom(this, dstRegion, region);
      return result;
   }

   @Override
   public String toString() {
      return "FloatTensor(shape=" + Arrays.toString(this.shape) + ")";
   }
}

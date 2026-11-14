package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class InfiniteTensor {
   final String id;
   final Integer[] shape;
   final TensorWindow outputWindow;
   final TensorFunction function;
   final BatchTensorFunction batchFunction;
   final int batchSize;
   final InfiniteTensor[] deps;
   final TensorWindow[] depWindows;
   final MemoryTileStore store;
   final long cacheLimitBytes;

   InfiniteTensor(
      String id,
      Integer[] shape,
      TensorWindow outputWindow,
      TensorFunction function,
      BatchTensorFunction batchFunction,
      int batchSize,
      InfiniteTensor[] deps,
      TensorWindow[] depWindows,
      MemoryTileStore store,
      long cacheLimitBytes
   ) {
      this.id = id;
      this.shape = shape;
      this.outputWindow = outputWindow;
      this.function = function;
      this.batchFunction = batchFunction;
      this.batchSize = batchSize;
      this.deps = deps;
      this.depWindows = depWindows;
      this.store = store;
      this.cacheLimitBytes = cacheLimitBytes;
   }

   public FloatTensor getSlice(int[] start, int[] end) {
      int n = this.shape.length;
      int[][] pixelRange = buildRange(start, end);
      this.ensureComputed(pixelRange);
      int[] outShape = new int[n];

      for (int d = 0; d < n; d++) {
         outShape[d] = end[d] - start[d];
      }

      FloatTensor output = new FloatTensor(outShape);
      int[] lo = this.outputWindow.getLowestIntersection(pixelRange);
      int[] hi = this.outputWindow.getHighestIntersection(pixelRange);
      iterateWindows(lo, hi, windowIndex -> {
         FloatTensor cached = this.store.getCachedWindow(this.id, windowIndex);
         if (cached != null) {
            int[][] wBounds = this.outputWindow.getBounds(windowIndex);
            int[][] isect = new int[n][2];

            for (int d = 0; d < n; d++) {
               isect[d][0] = Math.max(pixelRange[d][0], wBounds[d][0]);
               isect[d][1] = Math.min(pixelRange[d][1], wBounds[d][1]);
               if (isect[d][0] >= isect[d][1]) {
                  return;
               }
            }

            int[][] srcRegion = new int[n][2];
            int[][] dstRegion = new int[n][2];

            for (int d = 0; d < n; d++) {
               srcRegion[d][0] = isect[d][0] - wBounds[d][0];
               srcRegion[d][1] = isect[d][1] - wBounds[d][0];
               dstRegion[d][0] = isect[d][0] - pixelRange[d][0];
               dstRegion[d][1] = isect[d][1] - pixelRange[d][0];
            }

            output.addFrom(cached, dstRegion, srcRegion);
         }
      });
      this.store.evictIfNeeded(this.id, this.cacheLimitBytes);
      return output;
   }

   /** One independent window per admission, so distant coarse preparation yields
    * between model windows instead of monopolising the inference worker. */
   public boolean prepareIndependentSliceStep(int[] start,int[] end) {
      if(deps.length!=0)throw new IllegalStateException("Incremental preparation requires an independent stage");
      int[][] range=buildRange(start,end);
      int[] lo=outputWindow.getLowestIntersection(range),hi=outputWindow.getHighestIntersection(range);
      boolean[] computed={false};
      iterateWindows(lo,hi,wi->{
         if(!computed[0]&&!store.isWindowCached(id,wi)){computeSingle(wi);computed[0]=true;}
      });
      store.evictIfNeeded(id,cacheLimitBytes);
      return !computed[0];
   }

   /** Phase 4.3: like {@link #prepareIndependentSliceStep} but with an explicit status so
    * the caller can distinguish progress from deferral. Returns 1 when one uncached window
    * was computed, 0 when the whole slice is already computed. Requires an independent stage. */
   public int prepareIndependentWindowStep(int[] start,int[] end) {
      if(deps.length!=0)throw new IllegalStateException("Incremental preparation requires an independent stage");
      int[][] range=buildRange(start,end);
      int[] lo=outputWindow.getLowestIntersection(range),hi=outputWindow.getHighestIntersection(range);
      boolean[] complete={true},computed={false};
      iterateWindows(lo,hi,wi->{
         if(!store.isWindowCached(id,wi)){
            complete[0]=false;
            if(!computed[0]){computeSingle(wi);computed[0]=true;}
         }
      });
      store.evictIfNeeded(id,cacheLimitBytes);
      return computed[0]?1:(complete[0]?0:-1);
   }

   void ensureComputed(int[][] pixelRange) {
      this.ensureComputedRanges(Collections.singletonList(pixelRange));
   }

   void ensureComputedRanges(List<int[][]> pixelRanges) {
      Set<List<Integer>> pendingSet = new LinkedHashSet<>();

      for (int[][] range : pixelRanges) {
         int[] lo = this.outputWindow.getLowestIntersection(range);
         int[] hi = this.outputWindow.getHighestIntersection(range);
         iterateWindows(lo, hi, wix -> {
            if (!this.store.isWindowCached(this.id, wix)) {
               List<Integer> key = new ArrayList<>(wix.length);

               for (int v : wix) {
                  key.add(v);
               }

               pendingSet.add(key);
            }
         });
      }

      List<int[]> pending = pendingSet.stream().map(k -> k.stream().mapToInt(Integer::intValue).toArray()).collect(Collectors.toList());
      if (!pending.isEmpty()) {
         for (int i = 0; i < this.deps.length; i++) {
            List<int[][]> depRanges = new ArrayList<>(pending.size());

            for (int[] wi : pending) {
               depRanges.add(this.depWindows[i].getBounds(wi));
            }

            this.deps[i].ensureComputedRanges(depRanges);
         }

         if (this.batchSize > 0 && this.batchFunction != null) {
            this.computeBatched(pending);
         } else {
            for (int[] windowIndex : pending) {
               this.computeSingle(windowIndex);
            }
         }
      }
   }

   private void computeSingle(int[] windowIndex) {
      List<FloatTensor> args = new ArrayList<>(this.deps.length);

      for (int i = 0; i < this.deps.length; i++) {
         int[][] bounds = this.depWindows[i].getBounds(windowIndex);
         int[] depStart = new int[bounds.length];
         int[] depEnd = new int[bounds.length];

         for (int d = 0; d < bounds.length; d++) {
            depStart[d] = bounds[d][0];
            depEnd[d] = bounds[d][1];
         }

         args.add(this.deps[i].getSlice(depStart, depEnd));
      }

      FloatTensor result = this.function.apply(windowIndex, args);
      this.validateOutputShape(result, windowIndex);
      this.store.cacheWindow(this.id, windowIndex, result);
   }

   private void computeBatched(List<int[]> windowIndices) {
      int from = 0;

      while (from < windowIndices.size()) {
         int to = Math.min(from + this.batchSize, windowIndices.size());
         List<int[]> batch = windowIndices.subList(from, to);
         List<List<FloatTensor>> args = new ArrayList<>(this.deps.length);

         for (int i = 0; i < this.deps.length; i++) {
            List<FloatTensor> depArgs = new ArrayList<>(batch.size());

            for (int[] windowIndex : batch) {
               int[][] bounds = this.depWindows[i].getBounds(windowIndex);
               int[] depStart = new int[bounds.length];
               int[] depEnd = new int[bounds.length];

               for (int d = 0; d < bounds.length; d++) {
                  depStart[d] = bounds[d][0];
                  depEnd[d] = bounds[d][1];
               }

               depArgs.add(this.deps[i].getSlice(depStart, depEnd));
            }

            args.add(depArgs);
         }

         List<FloatTensor> outputs = this.batchFunction.apply(batch, args);

         for (int k = 0; k < batch.size(); k++) {
            FloatTensor result = outputs.get(k);
            int[] windowIndex = batch.get(k);
            this.validateOutputShape(result, windowIndex);
            this.store.cacheWindow(this.id, windowIndex, result);
         }

         from = to;
      }
   }

   private void validateOutputShape(FloatTensor result, int[] windowIndex) {
      int n = this.outputWindow.size.length;
      if (result.shape.length != n) {
         throw new IllegalStateException("Function for tensor '" + this.id + "' returned shape with " + result.shape.length + " dims, expected " + n);
      }

      for (int d = 0; d < n; d++) {
         if (result.shape[d] != this.outputWindow.size[d]) {
            throw new IllegalStateException(
               "Function for tensor '" + this.id + "' returned shape[" + d + "]=" + result.shape[d] + ", expected " + this.outputWindow.size[d]
            );
         }
      }
   }

   static int[][] buildRange(int[] start, int[] end) {
      int n = start.length;
      int[][] range = new int[n][2];

      for (int d = 0; d < n; d++) {
         range[d][0] = start[d];
         range[d][1] = end[d];
      }

      return range;
   }

   static void iterateWindows(int[] lo, int[] hi, InfiniteTensor.WindowConsumer action) {
      int n = lo.length;

      for (int d = 0; d < n; d++) {
         if (lo[d] > hi[d]) {
            return;
         }
      }

      int[] current = (int[])lo.clone();

      while (true) {
         action.accept((int[])current.clone());

         for (int d = n - 1; d >= 0; d--) {
            current[d]++;
            if (current[d] <= hi[d]) {
               break;
            }

            current[d] = lo[d];
            if (d == 0) {
               return;
            }
         }
      }
   }

   @FunctionalInterface
   interface WindowConsumer {
      void accept(int[] var1);
   }
}

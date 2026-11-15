package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;

public class MemoryTileStore {
    private final Map<String, LinkedHashMap<List<Integer>, FloatTensor>> windowCaches = new HashMap<>();
    private final Map<String, long[]> cacheSizes = new HashMap<>();
    private final Map<String, InfiniteTensor> tensors = new HashMap<>();
    private final AtomicLong totalComputedWindowCount = new AtomicLong(0L);

    public InfiniteTensor getOrCreate(
            String id,
            Integer[] shape,
            TensorFunction function,
            TensorWindow outputWindow,
            InfiniteTensor[] deps,
            TensorWindow[] depWindows,
            long cacheLimitBytes) {
        if (this.tensors.containsKey(id)) {
            return this.tensors.get(id);
        }

        InfiniteTensor tensor =
                new InfiniteTensor(id, shape, outputWindow, function, null, 0, deps, depWindows, this, cacheLimitBytes);
        this.register(id, tensor);
        return tensor;
    }

    public InfiniteTensor getOrCreateBatched(
            String id,
            Integer[] shape,
            BatchTensorFunction batchFunction,
            TensorWindow outputWindow,
            InfiniteTensor[] deps,
            TensorWindow[] depWindows,
            long cacheLimitBytes,
            int batchSize) {
        if (this.tensors.containsKey(id)) {
            return this.tensors.get(id);
        }

        InfiniteTensor tensor =
                new InfiniteTensor(
                        id,
                        shape,
                        outputWindow,
                        null,
                        batchFunction,
                        batchSize,
                        deps,
                        depWindows,
                        this,
                        cacheLimitBytes);
        this.register(id, tensor);
        return tensor;
    }

    private void register(String id, InfiniteTensor tensor) {
        this.tensors.put(id, tensor);
        this.windowCaches.put(id, new LinkedHashMap<>(16, 0.75F, true));
        this.cacheSizes.put(id, new long[] {0L});
    }

    void cacheWindow(String id, int[] windowIndex, FloatTensor output) {
        List<Integer> key = toKey(windowIndex);
        LinkedHashMap<List<Integer>, FloatTensor> cache = this.windowCaches.get(id);
        long[] size = this.cacheSizes.get(id);
        if (cache.containsKey(key)) {
            cache.get(key);
        } else {
            cache.put(key, output);
            size[0] += output.byteSize();
            this.totalComputedWindowCount.incrementAndGet();
        }
    }

    public long getTotalComputedWindowCount() {
        return this.totalComputedWindowCount.get();
    }

    void evictIfNeeded(String id, long limitBytes) {
        if (limitBytes != Long.MAX_VALUE) {
            LinkedHashMap<List<Integer>, FloatTensor> cache = this.windowCaches.get(id);
            long[] size = this.cacheSizes.get(id);
            if (cache != null) {
                Iterator<Entry<List<Integer>, FloatTensor>> it = cache.entrySet().iterator();

                while (size[0] > limitBytes && cache.size() > 1 && it.hasNext()) {
                    Entry<List<Integer>, FloatTensor> entry = it.next();
                    size[0] -= entry.getValue().byteSize();
                    it.remove();
                }
            }
        }
    }

    FloatTensor getCachedWindow(String id, int[] windowIndex) {
        LinkedHashMap<List<Integer>, FloatTensor> cache = this.windowCaches.get(id);
        return cache == null ? null : cache.get(toKey(windowIndex));
    }

    boolean isWindowCached(String id, int[] windowIndex) {
        LinkedHashMap<List<Integer>, FloatTensor> cache = this.windowCaches.get(id);
        return cache != null && cache.containsKey(toKey(windowIndex));
    }

    public void clearAllCaches() {
        for (Entry<String, LinkedHashMap<List<Integer>, FloatTensor>> e : this.windowCaches.entrySet()) {
            e.getValue().clear();
            this.cacheSizes.get(e.getKey())[0] = 0L;
        }
    }

    public void removeTensor(String id) {
        this.tensors.remove(id);
        this.windowCaches.remove(id);
        this.cacheSizes.remove(id);
    }

    private static List<Integer> toKey(int[] windowIndex) {
        List<Integer> key = new ArrayList<>(windowIndex.length);

        for (int v : windowIndex) {
            key.add(v);
        }

        return key;
    }
}

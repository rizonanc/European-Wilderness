package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Serializes acquisition AND execution: no session can be evicted during a native run.
 * Capacity is a session count, not a promise about driver/activation memory usage. */
final class GpuSessionCache<K, V> {
    @FunctionalInterface
    interface Factory<V> {
        V create() throws Exception;
    }

    @FunctionalInterface
    interface Work<V, T> {
        T run(V value) throws Exception;
    }

    @FunctionalInterface
    interface Disposer<K, V> {
        void close(K key, V value) throws Exception;
    }

    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(4, .75f, true);
    private final Disposer<K, V> disposer;
    private boolean memoryLimited;
    private long hits, created, evicted, memoryFallbacks;
    private volatile String snapshot = "resident=0 hits=0 created=0 evicted=0 memoryFallbacks=0 memoryLimited=false";

    GpuSessionCache(Disposer<K, V> disposer) {
        this.disposer = disposer;
    }

    synchronized <T> T execute(
            K key, int requestedCapacity, Factory<V> factory, Work<V, T> work, Predicate<Throwable> memoryFailure)
            throws Exception {
        int capacity = memoryLimited ? 1 : Math.max(1, Math.min(3, requestedCapacity));
        try {
            try {
                return work.run(acquire(key, capacity, factory));
            } catch (Exception failure) {
                // Only a memory failure in multi-session mode gets one clean retry.
                // A numerical/input/provider error must never silently change execution.
                if (capacity == 1 || !memoryFailure.test(failure)) throw failure;
                memoryLimited = true;
                memoryFallbacks++;
                try {
                    clear();
                    return work.run(acquire(key, 1, factory));
                } catch (Exception retryFailure) {
                    try {
                        clear();
                    } catch (Exception closeFailure) {
                        retryFailure.addSuppressed(closeFailure);
                    }
                    retryFailure.addSuppressed(failure);
                    throw retryFailure;
                }
            }
        } finally {
            publishSnapshot();
        }
    }

    private V acquire(K key, int capacity, Factory<V> factory) throws Exception {
        V value = entries.get(key);
        if (value != null) {
            while (entries.size() > capacity) evictOldest();
            hits++;
            return value;
        }
        while (entries.size() >= capacity) evictOldest();
        value = factory.create();
        if (value == null) throw new IllegalStateException("Session factory returned null");
        entries.put(key, value);
        created++;
        return value;
    }

    private void evictOldest() throws Exception {
        Map.Entry<K, V> entry = entries.entrySet().iterator().next();
        K key = entry.getKey();
        V value = entry.getValue();
        entries.remove(key);
        evicted++;
        disposer.close(key, value);
    }

    synchronized void remove(K key) throws Exception {
        try {
            V value = entries.remove(key);
            if (value != null) disposer.close(key, value);
            // Closing a complete pipeline allows a new world's models to try again.
            if (entries.isEmpty()) memoryLimited = false;
        } finally {
            publishSnapshot();
        }
    }

    private void clear() throws Exception {
        Exception failure = null;
        while (!entries.isEmpty()) {
            try {
                evictOldest();
            } catch (Exception closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    // Commands on the server thread cannot wait for a GPU run holding the cache lock.
    String statistics() {
        return snapshot;
    }

    private void publishSnapshot() {
        snapshot = "resident="
                + entries.size()
                + " hits="
                + hits
                + " created="
                + created
                + " evicted="
                + evicted
                + " memoryFallbacks="
                + memoryFallbacks
                + " memoryLimited="
                + memoryLimited;
    }
}

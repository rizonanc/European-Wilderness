package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand;
import java.util.*;
import java.util.concurrent.*;

/** Bounded resident plans, with one computation per key and no IO under the LRU lock. */
final class WaterPlanCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> ready = new LinkedHashMap<>(16, .75F, true);
    private final ConcurrentHashMap<K, PendingPlan> pending = new ConcurrentHashMap<>();

    private final class PendingPlan extends FutureTask<V> {
        final InferenceDemand demand;

        PendingPlan(Callable<V> loader) {
            this(InferenceDemand.current(), loader);
        }

        private PendingPlan(InferenceDemand demand, Callable<V> loader) {
            super(() -> InferenceDemand.call(demand, loader));
            this.demand = demand;
        }
    }

    private final java.util.concurrent.atomic.LongAdder hits = new java.util.concurrent.atomic.LongAdder(),
            loads = new java.util.concurrent.atomic.LongAdder(),
            joins = new java.util.concurrent.atomic.LongAdder();

    WaterPlanCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Cache capacity must be positive");
        this.capacity = capacity;
    }

    V get(K key, Callable<V> load) throws Exception {
        synchronized (ready) {
            V v = ready.get(key);
            if (v != null) {
                hits.increment();
                return v;
            }
        }
        PendingPlan task = new PendingPlan(() -> {
            synchronized (ready) {
                V v = ready.get(key);
                if (v != null) return v;
            }
            loads.increment();
            V value = Objects.requireNonNull(load.call());
            synchronized (ready) {
                ready.put(key, value);
                while (ready.size() > capacity)
                    ready.remove(ready.keySet().iterator().next());
            }
            return value;
        });
        PendingPlan flight = pending.putIfAbsent(key, task);
        boolean own = flight == null;
        if (own) {
            flight = task;
            task.run();
        } else joins.increment();
        try (var inherited = flight.demand.inheritPriorityFrom(InferenceDemand.current())) {
            return flight.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw new IllegalStateException(e.getCause());
        } finally {
            if (own) pending.remove(key, flight);
        }
    }

    synchronized int pendingCount() {
        return pending.size();
    }

    int size() {
        synchronized (ready) {
            return ready.size();
        }
    }

    boolean contains(K key) {
        synchronized (ready) {
            return ready.containsKey(key);
        }
    }

    V peek(K key) {
        synchronized (ready) {
            return ready.get(key);
        }
    }

    String stats() {
        return "resident="
                + size()
                + "/"
                + capacity
                + " hits="
                + hits.sum()
                + " loads="
                + loads.sum()
                + " joins="
                + joins.sum();
    }

    List<V> snapshot() {
        synchronized (ready) {
            return List.copyOf(ready.values());
        }
    }
}

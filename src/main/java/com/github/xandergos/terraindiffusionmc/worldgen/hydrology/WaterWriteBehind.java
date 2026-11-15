package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Arrays;

/** queue regenerable water plans. Full or closed queues write synchronously. */
final class WaterWriteBehind implements AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WaterWriteBehind.class);

    private record Entry(WorldWaterUnit unit, WorldWaterTile tile) {}

    private final WorldWaterStore store;
    private final ArrayBlockingQueue<Entry> queue;
    private final Thread worker;
    private final Object admission = new Object();
    private volatile boolean closed;
    private final AtomicLong accepted = new AtomicLong(),
            flushed = new AtomicLong(),
            saturated = new AtomicLong(),
            duplicate = new AtomicLong(),
            dropped = new AtomicLong();

    WaterWriteBehind(WorldWaterStore store, int capacity) {
        this.store = store;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.worker = new Thread(this::run, "terrain-water-write-behind");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    void unit(WorldWaterUnit unit) {
        offer(new Entry(unit, null));
    }

    void tile(WorldWaterTile tile) {
        offer(new Entry(null, tile));
    }

    private void offer(Entry entry) {
        synchronized (admission) {
            if (!closed) {
                accepted.incrementAndGet();
                if (queue.offer(entry)) return;
                saturated.incrementAndGet();
            }
        }
        saveNow(entry);
    }

    private void saveNow(Entry entry) {
        try {
            if (entry.unit() != null) store.save(entry.unit());
            else store.save(entry.tile());
            flushed.incrementAndGet();
        } catch (IOException failure) {
            if (verifiedDuplicate(entry)) duplicate.incrementAndGet();
            else dropped.incrementAndGet();
            LOG.debug("Water write-behind could not persist a plan: {}", failure.toString());
        } catch (RuntimeException failure) {
            // Unexpected in-process failure (e.g. allocation failure during GZIP). The store
            // already recorded TD-HYD-004/IO_WRITE; the entry is regenerable, the worker
            // stays alive so later entries keep flowing (bounded queue otherwise lingers).
            dropped.incrementAndGet();
            LOG.warn("Water write-behind dropped a regenerable plan after unexpected failure", failure);
        }
    }

    private boolean verifiedDuplicate(Entry entry) {
        try {
            if (entry.unit() != null) {
                var expected = entry.unit();
                var saved = store.loadUnit(expected.cell);
                return saved != null
                        && saved.revision == expected.revision
                        && Arrays.equals(saved.indices, expected.indices)
                        && Arrays.equals(saved.levels, expected.levels)
                        && Arrays.equals(saved.lakes, expected.lakes)
                        && saved.falls.equals(expected.falls)
                        && saved.depthVariation == expected.depthVariation
                        && Arrays.equals(saved.currents, expected.currents);
            }
            var expected = entry.tile();
            var saved = store.loadTile(expected.cell);
            return saved != null
                    && saved.revision == expected.revision
                    && Arrays.equals(saved.air, expected.air)
                    && Arrays.equals(saved.water, expected.water)
                    && saved.falls.equals(expected.falls)
                    && Arrays.equals(saved.currents, expected.currents);
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private void run() {
        try {
            while (!closed || !queue.isEmpty()) {
                var entry = queue.poll(200, TimeUnit.MILLISECONDS);
                if (entry != null) {
                    try {
                        saveNow(entry);
                    } catch (Throwable fatal) {
                        dropped.incrementAndGet();
                        throw fatal;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable fatal) {
            com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.failed(
                    com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.Code.SCH_WORKER,
                    "WATER_WRITE_BEHIND",
                    fatal);
            LOG.warn("Water write-behind writer stopped; saves fall back to synchronous", fatal);
        } finally {
            synchronized (admission) {
                closed = true;
                dropped.addAndGet(queue.size());
                queue.clear();
            }
        }
    }

    long flushedCount() {
        return flushed.get();
    }

    long saturatedCount() {
        return saturated.get();
    }

    long duplicateCount() {
        return duplicate.get();
    }

    long droppedCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        synchronized (admission) {
            closed = true;
        }
        // queued water plans can be recomputed after shutdown.
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker.interrupt();
        // Whatever remains is regenerable; on-disk data stays consistent (write-once files).
        synchronized (admission) {
            dropped.addAndGet(queue.size());
            queue.clear();
        }
    }
}

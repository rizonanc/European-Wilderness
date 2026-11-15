package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainRepository;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TilePrefetchService {
    private static final Logger LOG = LoggerFactory.getLogger(TilePrefetchService.class);
    private final Object lock = new Object();
    private final TilePrefetchService.TileWork work;
    private final BooleanSupplier sessionActive;
    private final BooleanSupplier foregroundBusy;
    private final BooleanSupplier manualBusy;
    private final PrefetchMetrics metrics = new PrefetchMetrics();
    private final Thread worker;
    private List<TerrainTileKey> desired = List.of();
    private final Set<TerrainTileKey> attempted = new LinkedHashSet<>();
    private TerrainTileKey activeKey;
    private boolean tickHealthy;
    private boolean closed;
    private long nextStageNanos;

    public TilePrefetchService(
            com.github.xandergos.terraindiffusionmc.worldgen.hydrology.WorldWaterController water,
            WorldSession ticket,
            BooleanSupplier foregroundBusy,
            BooleanSupplier manualBusy) {
        this(
                new TileWork() {
                    public boolean ready(TerrainTileKey key) {
                        return water.isPrepared(key.blockStartX(), key.blockStartZ());
                    }

                    public void prepare(TerrainTileKey key) {
                        throw new UnsupportedOperationException("Water uses incremental preparation");
                    }

                    @Override
                    public boolean prepareNext(TerrainTileKey key, BooleanSupplier allowed) throws Exception {
                        return water.prepareAheadStep(key.blockStartX(), key.blockStartZ(), allowed);
                    }
                },
                ticket::isActive,
                foregroundBusy,
                manualBusy);
    }

    public TilePrefetchService(
            TerrainRepository repository,
            WorldSession ticket,
            BooleanSupplier foregroundBusy,
            BooleanSupplier manualBusy) {
        this(
                new TilePrefetchService.TileWork() {
                    @Override
                    public boolean ready(TerrainTileKey key) {
                        return repository.peek(key).isPresent();
                    }

                    @Override
                    public void prepare(TerrainTileKey key) throws Exception {
                        repository.get(key);
                    }
                },
                ticket::isActive,
                foregroundBusy,
                manualBusy);
    }

    public TilePrefetchService(
            TilePrefetchService.TileWork work,
            BooleanSupplier sessionActive,
            BooleanSupplier foregroundBusy,
            BooleanSupplier manualBusy) {
        this.work = Objects.requireNonNull(work);
        this.sessionActive = Objects.requireNonNull(sessionActive);
        this.foregroundBusy = Objects.requireNonNull(foregroundBusy);
        this.manualBusy = Objects.requireNonNull(manualBusy);
        this.worker = new Thread(this::run, "terrain-tile-prefetch");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void updateCandidates(List<TerrainTileKey> candidates, boolean healthy) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null");
        }

        LinkedHashSet<TerrainTileKey> unique = new LinkedHashSet<>(candidates);
        if (unique.size() > 4) {
            throw new IllegalArgumentException("at most four candidate keys");
        }

        synchronized (this.lock) {
            if (!this.closed) {
                List<TerrainTileKey> replacement = List.copyOf(unique);
                if (!replacement.equals(this.desired)) {
                    this.desired = replacement;
                    this.attempted.clear();
                    if (this.activeKey != null) {
                        this.attempted.add(this.activeKey);
                    }
                }

                this.tickHealthy = healthy;
                this.lock.notifyAll();
            }
        }
    }

    public void signalStateChanged() {
        synchronized (this.lock) {
            this.lock.notifyAll();
        }
    }

    public PrefetchMetrics.Snapshot metrics() {
        return this.metrics.snapshot();
    }

    public int queued() {
        synchronized (this.lock) {
            return (int) this.desired.stream().filter(k -> !this.attempted.contains(k)).count();
        }
    }

    public boolean inFlight() {
        synchronized (this.lock) {
            return this.activeKey != null;
        }
    }

    public boolean awaitIdle(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this.lock) {
            while (this.activeKey != null
                    || !this.closed && this.sessionActive.getAsBoolean() && this.queuedLocked() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }

                TimeUnit.NANOSECONDS.timedWait(this.lock, remaining);
            }

            return true;
        }
    }

    public void shutdown() {
        synchronized (this.lock) {
            this.closed = true;
            this.desired = List.of();
            this.attempted.clear();
            this.lock.notifyAll();
        }

        this.worker.interrupt();
    }

    private int queuedLocked() {
        int count = 0;

        for (TerrainTileKey key : this.desired) {
            if (!this.attempted.contains(key)) {
                count++;
            }
        }

        return count;
    }

    private void run() {
        while (true) {
            TerrainTileKey key;
            synchronized (this.lock) {
                while (true) {
                    if (this.closed) {
                        return;
                    }

                    if (!this.sessionActive.getAsBoolean()) {
                        this.desired = List.of();
                        this.attempted.clear();
                        this.lock.notifyAll();
                        return;
                    }

                    if (this.tickHealthy && this.queuedLocked() > 0 && !this.manualBusy.getAsBoolean()) {
                        if (!this.foregroundBusy.getAsBoolean()) {
                            long remaining = nextStageNanos - System.nanoTime();
                            if (remaining > 0) {
                                try {
                                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                                } catch (InterruptedException interrupted) {
                                    if (closed) return;
                                }
                                continue;
                            }
                            key =
                                    this.desired.stream()
                                            .filter(k -> !this.attempted.contains(k))
                                            .findFirst()
                                            .orElseThrow();
                            this.attempted.add(key);
                            this.activeKey = key;
                            break;
                        }

                        this.metrics.deferForeground();
                    }

                    try {
                        this.lock.wait();
                    } catch (InterruptedException e) {
                        if (this.closed) {
                            return;
                        }
                    }
                }
            }

            boolean deferred = false;
            long stageStart = System.nanoTime();
            boolean ran = false;

            try {
                if (this.sessionActive.getAsBoolean()) {
                    if (this.work.ready(key)) {
                        this.metrics.readySkip();
                    } else if (this.mayBeginWork()
                            && !this.foregroundBusy.getAsBoolean()
                            && !this.manualBusy.getAsBoolean()) {
                        this.metrics.start();
                        ran = true;
                        try (var ignored =
                                com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.scope(
                                        com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.Priority
                                                .NEAR)) {
                            if (this.work.prepareNext(key, () -> mayContinueWork(key))) this.metrics.complete();
                            else deferred = true;
                        }
                    } else {
                        deferred = true;
                        if (this.foregroundBusy.getAsBoolean()) {
                            this.metrics.deferForeground();
                        }
                    }
                }
            } catch (Exception e) {
                this.metrics.failure();
                if (this.sessionActive.getAsBoolean() && !this.closed) {
                    DiagnosticErrors.emit(
                            DiagnosticErrors.Code.SCH_TASK,
                            DiagnosticErrors.context("OPTIONAL_PREFETCH", String.valueOf(key)),
                            e,
                            DiagnosticErrors.cancelled(e)
                                    ? DiagnosticErrors.Outcome.CANCELLED
                                    : DiagnosticErrors.Outcome.DEGRADED,
                            "Optional preparation stopped; mandatory generation retained",
                            false);
                }
            } finally {
                synchronized (this.lock) {
                    // Leave GPU/CPU idle between stages as well as checking server load.
                    if (ran)
                        nextStageNanos =
                                System.nanoTime()
                                        + Math.min(
                                                TimeUnit.SECONDS.toNanos(2),
                                                Math.max(
                                                        TimeUnit.MILLISECONDS.toNanos(100),
                                                        System.nanoTime() - stageStart));
                    if (deferred && !this.closed) {
                        this.attempted.remove(key);
                    }

                    this.activeKey = null;
                    this.lock.notifyAll();
                }
            }
        }
    }

    private boolean mayBeginWork() {
        synchronized (this.lock) {
            return !this.closed && this.tickHealthy && this.sessionActive.getAsBoolean();
        }
    }

    private boolean mayContinueWork(TerrainTileKey key) {
        synchronized (lock) {
            return !closed
                    && tickHealthy
                    && sessionActive.getAsBoolean()
                    && desired.contains(key)
                    && !foregroundBusy.getAsBoolean()
                    && !manualBusy.getAsBoolean();
        }
    }

    public interface TileWork {
        boolean ready(TerrainTileKey var1);

        void prepare(TerrainTileKey var1) throws Exception;

        default boolean prepareNext(TerrainTileKey key, BooleanSupplier allowed) throws Exception {
            prepare(key);
            return true;
        }
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainRepository;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PreparationService {
    private static final Logger LOG = LoggerFactory.getLogger(PreparationService.class);
    public static final int MAX_TILES_PER_JOB = 1024;
    private final TerrainRepository repository;
    private final WorldSession ticket;
    private final ExecutorService worker;
    private final Object stateLock = new Object();
    private boolean running;
    private List<TerrainTileKey> jobKeys = List.of();
    private int completed;
    private int skipped;
    private int failed;
    private long startedAt;
    private long finishedAt;
    private PreparationProgress.State terminalState;
    private String lastError;
    private String currentKey;
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    public PreparationService(TerrainRepository repository, WorldSession ticket) {
        if (repository != null && ticket != null) {
            this.repository = repository;
            this.ticket = ticket;
            this.worker =
                    Executors.newSingleThreadExecutor(task -> {
                        Thread thread = new Thread(task, "terrain-preparation");
                        thread.setDaemon(true);
                        return thread;
                    });
        } else {
            throw new IllegalArgumentException("repository and ticket must not be null");
        }
    }

    public synchronized void start(List<TerrainTileKey> keys) {
        if (this.running) {
            throw new IllegalStateException("A preparation job is already running; cancel it first");
        }

        if (keys != null && !keys.isEmpty()) {
            LinkedHashSet<TerrainTileKey> unique = new LinkedHashSet<>(keys);
            if (unique.size() > 1024) {
                throw new IllegalArgumentException("too many tiles: " + unique.size() + " (max 1024); split the area");
            }

            for (TerrainTileKey key : unique) {
                if (key.tileSize() != this.repository.tileSize()) {
                    throw new IllegalArgumentException(
                            "key " + key + " does not match repository tile size " + this.repository.tileSize());
                }
            }

            synchronized (this.stateLock) {
                this.jobKeys = new ArrayList<>(unique);
                this.completed = 0;
                this.skipped = 0;
                this.failed = 0;
                this.startedAt = System.currentTimeMillis();
                this.finishedAt = 0L;
                this.terminalState = null;
                this.lastError = null;
                this.currentKey = null;
                this.running = true;
                this.cancelRequested.set(false);
            }

            this.worker.submit(this::runJob);
        } else {
            throw new IllegalArgumentException("keys must be non-empty");
        }
    }

    public void cancel() {
        this.cancelRequested.set(true);
    }

    public PreparationProgress progress() {
        synchronized (this.stateLock) {
            if (!this.running && this.terminalState == null) {
                return PreparationProgress.idle();
            }

            PreparationProgress.State state = this.running ? PreparationProgress.State.RUNNING : this.terminalState;
            return new PreparationProgress(
                    state,
                    this.jobKeys.size(),
                    this.completed,
                    this.skipped,
                    this.failed,
                    this.startedAt,
                    this.finishedAt,
                    this.lastError,
                    this.currentKey);
        }
    }

    public boolean awaitDone(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this.stateLock) {
            while (this.running) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }

                this.stateLock.wait(TimeUnit.NANOSECONDS.toMillis(remaining));
            }

            return true;
        }
    }

    public void shutdown() {
        this.cancel();
        this.worker.shutdownNow();

        try {
            if (!this.worker.awaitTermination(5L, TimeUnit.SECONDS)) {
                LOG.warn("Preparation worker did not stop in time; an inference call may still run");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void runJob() {
        try {
            for (TerrainTileKey key : this.snapshotKeys()) {
                if (this.cancelRequested.get()) {
                    this.finish(PreparationProgress.State.CANCELLED, null);
                    return;
                }

                if (!this.ticket.isActive()) {
                    LOG.info("Preparation stopping: world session retired");
                    this.finish(PreparationProgress.State.CANCELLED, "world session retired");
                    return;
                }

                this.setCurrent(key.toString());

                try {
                    if (this.isAlreadyStored(key)) {
                        this.incrementSkipped();
                    } else {
                        this.repository.get(key);
                        this.incrementCompleted();
                    }
                } catch (Exception tileFailed) {
                    if (!this.cancelRequested.get() && this.ticket.isActive()) {
                        LOG.warn("Preparation failed on {}: {}", key, tileFailed.getMessage());
                        synchronized (this.stateLock) {
                            this.failed++;
                        }

                        this.finish(PreparationProgress.State.FAILED, tileFailed.getMessage());
                        return;
                    }

                    if (tileFailed instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }

                    this.finish(PreparationProgress.State.CANCELLED, null);
                    return;
                }
            }

            this.finish(PreparationProgress.State.DONE, null);
        } catch (RuntimeException unexpected) {
            this.finish(PreparationProgress.State.FAILED, unexpected.getMessage());
        }
    }

    private List<TerrainTileKey> snapshotKeys() {
        synchronized (this.stateLock) {
            return new ArrayList<>(this.jobKeys);
        }
    }

    private boolean isAlreadyStored(TerrainTileKey key) {
        try {
            return this.repository.store().load(key).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void incrementCompleted() {
        synchronized (this.stateLock) {
            this.completed++;
            this.currentKey = null;
        }
    }

    private void incrementSkipped() {
        synchronized (this.stateLock) {
            this.skipped++;
            this.currentKey = null;
        }
    }

    private void setCurrent(String key) {
        synchronized (this.stateLock) {
            this.currentKey = key;
        }
    }

    private void finish(PreparationProgress.State state, String error) {
        synchronized (this.stateLock) {
            this.terminalState = state;
            this.lastError = error;
            this.finishedAt = System.currentTimeMillis();
            this.running = false;
            this.currentKey = null;
            this.stateLock.notifyAll();
        }

        LOG.info("Preparation {}: {}", state, this.progress());
    }
}

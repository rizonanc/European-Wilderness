package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import java.util.concurrent.atomic.AtomicLong;

public final class GenerationMetrics {
    private static final AtomicLong INFERENCE_REGIONS = new AtomicLong();
    private static final AtomicLong REPO_RAM_HITS = new AtomicLong();
    private static final AtomicLong REPO_DISK_HITS = new AtomicLong();
    private static final AtomicLong REPO_COMPUTED = new AtomicLong();
    private static final AtomicLong TILES_STORED = new AtomicLong();
    private static final AtomicLong FULL_CHUNKS = new AtomicLong();

    private GenerationMetrics() {}

    public static void countInferenceRegion() {
        INFERENCE_REGIONS.incrementAndGet();
    }

    public static void countRepoRamHit() {
        REPO_RAM_HITS.incrementAndGet();
    }

    public static void countRepoDiskHit() {
        REPO_DISK_HITS.incrementAndGet();
    }

    public static void countRepoComputed() {
        REPO_COMPUTED.incrementAndGet();
    }

    public static void countTileStored() {
        TILES_STORED.incrementAndGet();
    }

    public static void countFullChunk() {
        FULL_CHUNKS.incrementAndGet();
    }

    public static GenerationMetrics.Snapshot snapshot() {
        return new GenerationMetrics.Snapshot(
                INFERENCE_REGIONS.get(),
                REPO_RAM_HITS.get(),
                REPO_DISK_HITS.get(),
                REPO_COMPUTED.get(),
                TILES_STORED.get(),
                FULL_CHUNKS.get());
    }

    public record Snapshot(
            long inferenceRegions,
            long repoRamHits,
            long repoDiskHits,
            long repoComputed,
            long tilesStored,
            long fullChunksGenerated) {
        @Override
        public String toString() {
            return "inferenceRegions="
                    + this.inferenceRegions
                    + " repoRamHits="
                    + this.repoRamHits
                    + " repoDiskHits="
                    + this.repoDiskHits
                    + " repoComputed="
                    + this.repoComputed
                    + " tilesStored="
                    + this.tilesStored
                    + " fullChunksGenerated="
                    + fullChunksGenerated;
        }
    }
}

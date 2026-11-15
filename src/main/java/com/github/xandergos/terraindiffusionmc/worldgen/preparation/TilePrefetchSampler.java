package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

public final class TilePrefetchSampler {
    private final long seed;
    private final String fingerprint;
    private final int tileSize;
    private final BiConsumer<List<TerrainTileKey>, Boolean> sink;
    private final TilePrefetchPolicy.LoadGate gate = new TilePrefetchPolicy.LoadGate();
    private int ticks;
    private Integer lastX;
    private Integer lastZ;
    private boolean stopped;
    private final boolean completeWater;

    public TilePrefetchSampler(
            long seed, String fingerprint, int tileSize, BiConsumer<List<TerrainTileKey>, Boolean> sink) {
        this(seed, fingerprint, tileSize, sink, false);
    }

    public TilePrefetchSampler(
            long seed,
            String fingerprint,
            int tileSize,
            BiConsumer<List<TerrainTileKey>, Boolean> sink,
            boolean completeWater) {
        this.seed = seed;
        this.fingerprint = Objects.requireNonNull(fingerprint);
        this.tileSize = tileSize;
        this.sink = Objects.requireNonNull(sink);
        this.completeWater = completeWater;
    }

    public void tick(long elapsedMillis, Integer x, Integer z, boolean active) {
        if (!this.stopped) {
            if (!active) {
                this.stop();
            } else {
                boolean wasHealthy = this.gate.mayStart();
                this.gate.acceptTick(elapsedMillis);
                if (wasHealthy && !this.gate.mayStart()) {
                    this.sink.accept(List.of(), false);
                }

                if (x != null && z != null) {
                    if (++this.ticks >= 20) {
                        this.ticks = 0;
                        List<TilePrefetchPolicy.TileCoord> coords = this.completeWater
                                ? WaterPrefetchPolicy.candidates(
                                        x, z, lastX == null ? 0 : x - lastX, lastZ == null ? 0 : z - lastZ, tileSize)
                                : this.lastX == null
                                        ? List.of()
                                        : TilePrefetchPolicy.candidates(
                                                x, z, x - this.lastX, z - this.lastZ, this.tileSize);
                        this.lastX = x;
                        this.lastZ = z;
                        if (!this.gate.mayStart()) {
                            coords = List.of();
                        }

                        this.sink.accept(
                                coords.stream()
                                        .map(c -> TerrainTileKey.ofTile(
                                                this.seed, c.x(), c.z(), this.tileSize, this.fingerprint))
                                        .toList(),
                                this.gate.mayStart());
                    }
                } else {
                    if (this.lastX != null) {
                        this.sink.accept(List.of(), this.gate.mayStart());
                    }

                    this.lastX = null;
                    this.lastZ = null;
                    this.ticks = 0;
                }
            }
        }
    }

    public void stop() {
        if (!this.stopped) {
            this.stopped = true;
            this.sink.accept(List.of(), false);
        }
    }

    public boolean tickHealthy() {
        return this.gate.mayStart();
    }
}

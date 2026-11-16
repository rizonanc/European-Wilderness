package com.github.xandergos.terraindiffusionmc.pipeline;

public final class ExplorerPreview {
    private volatile Long previewSeed;

    public synchronized void setPreviewSeed(long seed) {
        this.previewSeed = seed;
    }

    public synchronized void clear() {
        this.previewSeed = null;
    }

    public Long previewSeedOrNull() {
        return this.previewSeed;
    }

    public boolean isPreviewActive() {
        return this.previewSeed != null;
    }
}

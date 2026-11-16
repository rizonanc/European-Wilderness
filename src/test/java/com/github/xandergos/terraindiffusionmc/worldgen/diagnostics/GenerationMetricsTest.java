package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GenerationMetricsTest {
    @Test
    void testFullChunkEventsHaveTheirOwnCounter() {
        var before = GenerationMetrics.snapshot();
        GenerationMetrics.countFullChunk();
        var after = GenerationMetrics.snapshot();
        assertEquals(before.fullChunksGenerated() + 1, after.fullChunksGenerated());
        assertEquals(before.inferenceRegions(), after.inferenceRegions());
    }
}

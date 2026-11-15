package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import static org.junit.jupiter.api.Assertions.*;

import com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PrefetchDemandTest {
    @Test
    void rawPrefetchDoesNotPretendToBeForeground() throws Exception {
        var priority = new AtomicReference<InferenceDemand.Priority>();
        var done = new CountDownLatch(1);
        var prefetch = new TilePrefetchService(
                new TilePrefetchService.TileWork() {
                    public boolean ready(TerrainTileKey key) {
                        return false;
                    }

                    public void prepare(TerrainTileKey key) {
                        priority.set(InferenceDemand.currentPriority());
                        done.countDown();
                    }
                },
                () -> true,
                () -> false,
                () -> false);
        try {
            prefetch.updateCandidates(List.of(TerrainTileKey.ofTile(7, 0, 0, 16, "priority")), true);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(InferenceDemand.Priority.NEAR, priority.get());
            assertEquals(InferenceDemand.Priority.FOREGROUND, InferenceDemand.currentPriority());
        } finally {
            prefetch.shutdown();
        }
    }
}

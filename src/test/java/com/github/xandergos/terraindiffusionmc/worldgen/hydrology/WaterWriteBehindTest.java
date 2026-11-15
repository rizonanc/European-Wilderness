package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaterWriteBehindTest {
    @TempDir
    Path temp;

    private static final WorldWaterGrid GRID = WorldWaterGrid.DEFAULT;

    private static WorldWaterStore store(Path root) {
        return new WorldWaterStore(root, 777L, "fp-test", 256, GRID);
    }

    private static WorldWaterUnit unit(int x, int z) {
        var cell = new WorldWaterGrid.Cell(x, z);
        // Valid sparse columns for unitSide=1024, COLLAR=8: strictly increasing packed
        // indices inside the collar, levels >= 62, lake flags 0/1.
        int side = GRID.unitSide();
        int p1 = 500 * side + 500, p2 = 500 * side + 501, p3 = 501 * side + 500;
        return new WorldWaterUnit(
                GRID, cell, new int[] {p1, p2, p3}, new short[] {62, 62, 63}, new byte[] {0, 1, 0}, List.of(), 2);
    }

    private static WorldWaterTile tile(int x, int z) {
        var cell = new WorldWaterGrid.Cell(x, z);
        int n = GRID.core() * GRID.core();
        int[] air = new int[n], water = new int[n];
        for (int i = 0; i < n; i++) {
            air[i] = 55 + (i % 7);
            water[i] = i % 5 == 0 ? 62 : PreparedWaterArea.NONE;
        }
        return new WorldWaterTile(GRID, cell, air, water, List.of(), 2);
    }

    @Test
    void queuedPlansLandOnDiskAndRoundTrip() throws Exception {
        var s = store(temp);
        var writer = new WaterWriteBehind(s, 4);
        var u = unit(2, 3);
        var t = tile(-4, -7);
        writer.unit(u);
        writer.tile(t);
        writer.close();
        assertTrue(s.hasUnit(u.cell), "unit file after close");
        assertTrue(s.hasTile(t.cell), "tile file after close");
        var loaded = s.loadUnit(u.cell);
        assertNotNull(loaded);
        assertArrayEquals(u.indices, loaded.indices);
        assertArrayEquals(u.levels, loaded.levels);
        assertArrayEquals(u.lakes, loaded.lakes);
        var loadedTile = s.loadTile(t.cell);
        assertNotNull(loadedTile);
        assertArrayEquals(t.air, loadedTile.air);
        assertArrayEquals(t.water, loadedTile.water);
        assertEquals(0, writer.droppedCount());
        assertEquals(2, writer.flushedCount());
    }

    @Test
    void offerAfterCloseWritesSynchronouslyWithoutQueue() throws Exception {
        var s = store(temp);
        var writer = new WaterWriteBehind(s, 4);
        writer.close();
        var u = unit(-9, 12);
        writer.unit(u);
        assertTrue(s.hasUnit(u.cell), "synchronous fallback after close");
        assertEquals(1, writer.flushedCount());
        assertEquals(0, writer.saturatedCount());
    }

    @Test
    void duplicatePlanIsToleratedAndCountedNotFailed() throws Exception {
        var s = store(temp);
        var writer = new WaterWriteBehind(s, 4);
        var u = unit(5, -6);
        writer.unit(u);
        writer.close();
        assertTrue(s.hasUnit(u.cell));
        // Second attempt for the same deterministic plan: write-once guard fires,
        // the writer treats the existing identical file as flushed, not as an error.
        assertDoesNotThrow(() -> writer.unit(u));
        writer.close();
        assertEquals(1, writer.duplicateCount());
        assertEquals(0, writer.droppedCount());
    }
}

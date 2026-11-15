package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WaterWriteBehindFailureTest {
    @TempDir Path temp;
    private static final WorldWaterGrid GRID = WorldWaterGrid.DEFAULT;

    private static WorldWaterStore store(Path root) {
        return new WorldWaterStore(root, 777L, "fp-test", 256, GRID);
    }

    private static WorldWaterUnit unit(int x, int z) {
        var cell = new WorldWaterGrid.Cell(x, z);
        int side = GRID.unitSide();
        int p1 = 500 * side + 500, p2 = 500 * side + 501, p3 = 501 * side + 500;
        return new WorldWaterUnit(
                GRID, cell, new int[] {p1, p2, p3}, new short[] {62, 62, 63}, new byte[] {0, 1, 0}, List.of(), 2);
    }

    @Test
    void diskFailureIsCountedAndNeverEscapesToCaller() throws Exception {
        // Root is a regular file: every write attempt fails inside createDirectories.
        Path broken = temp.resolve("not-a-directory");
        Files.writeString(broken, "x");
        var s = store(broken);
        var writer = new WaterWriteBehind(s, 4);
        assertDoesNotThrow(() -> writer.unit(unit(3, 4)));
        writer.close();
        assertTrue(writer.droppedCount() >= 1, "failure must be counted, not silently lost");
        assertEquals(0, writer.flushedCount());
    }

    @Test
    void saturationStormNeverLosesAnAcceptedEntry() throws Exception {
        var s = store(temp);
        var writer = new WaterWriteBehind(s, 2); // tiny queue forces saturation mid-storm
        var units = new ArrayList<WorldWaterUnit>();
        for (int i = 0; i < 100; i++) units.add(unit(i % 10, (i / 10) + 20));
        for (var u : units) writer.unit(u);
        writer.close();
        assertEquals(units.size(), writer.flushedCount(), "every accepted entry must reach disk");
        assertEquals(0, writer.droppedCount(), "saturated entries fall back to synchronous writes");
        for (var u : units) assertTrue(s.hasUnit(u.cell), "unit " + u.cell + " on disk");
    }

    @Test
    void closeIsBoundedEvenWithFullQueue() throws Exception {
        var s = store(temp);
        var writer = new WaterWriteBehind(s, 16);
        for (int i = 0; i < 16; i++) writer.unit(unit(i % 10, (i / 10) + 50));
        long started = System.nanoTime();
        writer.close();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(elapsedMs < 10_000, "shutdown must stay bounded, took " + elapsedMs + " ms");
        assertEquals(16, writer.flushedCount() + writer.droppedCount(), "no entry may linger unaccounted");
    }
}

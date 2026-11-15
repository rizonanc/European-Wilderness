package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkyApiAdapterTest {
    public static final class Api {
        boolean running;
        int starts, pauses;
        Object[] selection;

        public int version() {
            return 0;
        }

        public boolean isRunning(String world) {
            assertEquals("minecraft:overworld", world);
            return running;
        }

        public boolean startTask(String world, String shape, double x, double z, double rx, double rz, String pattern) {
            starts++;
            selection = new Object[] {world, shape, x, z, rx, rz, pattern};
            running = true;
            return true;
        }

        public boolean pauseTask(String world) {
            pauses++;
            running = false;
            return true;
        }
    }

    @Test
    void testExplicitRequestUsesPublicApi() throws Exception {
        var fake = new Api();
        var adapter = new ChunkyApiAdapter(fake);
        assertEquals(0, fake.starts);
        assertTrue(adapter.startChunks("minecraft:overworld", -301, 815, 1024));
        assertArrayEquals(
                new Object[] {"minecraft:overworld", "circle", -301d, 815d, 16384d, 16384d, "region"}, fake.selection);
        assertFalse(adapter.startChunks("minecraft:overworld", 1000, 2000, 2048));
        assertEquals(1, fake.starts);
        assertEquals(0, fake.pauses);
        assertTrue(adapter.pause("minecraft:overworld"));
        assertEquals(1, fake.pauses);
        assertTrue(adapter.startChunks("minecraft:overworld", 0, 0, 2048));
        assertEquals(32768d, fake.selection[4]);
        adapter.pause("minecraft:overworld");
        assertTrue(adapter.startChunks("minecraft:overworld", 0, 0, 1));
        assertEquals(16d, fake.selection[4]);
    }

    @Test
    void invalidRadiusNeverInvokesTaskCreation() throws Exception {
        var fake = new Api();
        var adapter = new ChunkyApiAdapter(fake);
        for (int radius : new int[] {-1, 0, 2049, Integer.MAX_VALUE})
            assertThrows(
                    IllegalArgumentException.class, () -> adapter.startChunks("minecraft:overworld", 0, 0, radius));
        assertEquals(0, fake.starts);
    }
}

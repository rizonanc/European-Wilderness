package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class LodPreparationFrontierTest {
    @Test
    void testPreparesMissingCores() {
        var frontier = new LodPreparationFrontier(512);
        Set<LodPreparationFrontier.Point> ready = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            var point = frontier.next(100, 100, 8192, (x, z) -> ready.contains(new LodPreparationFrontier.Point(x, z)));
            assertNotNull(point);
            assertEquals(0, Math.floorMod(point.x(), 512));
            assertEquals(0, Math.floorMod(point.z(), 512));
            assertTrue(ready.add(point), "each unprepared core must be offered once before rescanning");
        }
        var moved = frontier.next(110, 110, 8192, (x, z) -> ready.contains(new LodPreparationFrontier.Point(x, z)));
        assertNotNull(moved);
        assertFalse(ready.contains(moved));
    }

    @Test
    void testMovementReprioritizes() {
        var frontier = new LodPreparationFrontier(512);
        assertEquals(new LodPreparationFrontier.Point(-512, -512), frontier.next(-1, -1, 32768, (x, z) -> false));
        assertEquals(new LodPreparationFrontier.Point(4096, -4608), frontier.next(4100, -4100, 32768, (x, z) -> false));
    }

    @Test
    void testFullyPreparedSmallDiskEventuallyIdles() {
        var frontier = new LodPreparationFrontier(512);
        int[] lookups = {0};
        for (int i = 0; i < 20; i++)
            assertNull(frontier.next(0, 0, 64, (x, z) -> {
                lookups[0]++;
                assertTrue(Math.abs(x) <= 1024 && Math.abs(z) <= 1024);
                return true;
            }));
        int before = lookups[0];
        assertNull(frontier.next(0, 0, 64, (x, z) -> {
            fail("finished frontier should not repeatedly probe disk");
            return false;
        }));
        assertTrue(before > 0);
        assertThrows(IllegalArgumentException.class, () -> new LodPreparationFrontier(33));
    }
}

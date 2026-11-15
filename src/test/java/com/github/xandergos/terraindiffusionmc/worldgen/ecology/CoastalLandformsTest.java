package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CoastalLandformsTest {
    @Test
    void testCoastalProvinceCutsDeepInlets() {
        float deepestInlet = 80, highestSkerry = -18;
        for (int z = -1800; z <= 1800; z += 20) {
            for (int x = -1800; x <= 1800; x += 20) {
                deepestInlet = Math.min(deepestInlet, CoastalTerrain.fjordsAndSkerries(43, x, z, 80, 2));
                highestSkerry = Math.max(highestSkerry, CoastalTerrain.fjordsAndSkerries(43, x, z, -18, 2));
            }
        }
        assertTrue(deepestInlet < -120);
        assertTrue(highestSkerry > 25);
        assertEquals(-600f, CoastalTerrain.fjordsAndSkerries(43, 50, -80, -600, 2));
        assertEquals(850f, CoastalTerrain.fjordsAndSkerries(43, 50, -80, 850, 2));
    }
}

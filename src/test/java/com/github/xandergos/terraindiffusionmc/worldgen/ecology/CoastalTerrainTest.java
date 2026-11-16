package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CoastalTerrainTest {
    @Test
    void shorelineHasShallowShelvesAndRockySectors() {
        int beaches = 0, rocky = 0;
        for (int x = -5000; x <= 5000; x += 31) {
            float seabed = CoastalTerrain.height(23, x, 10, -80);
            assertTrue(seabed >= -80 && seabed < 0);
            assertEquals(-900F, CoastalTerrain.height(23, x, 10, -900));
            short biome = CoastalTerrain.biome(23, x, 10, 40, 2, (short) 229);
            if (biome == 18) {
                beaches++;
                assertTrue(seabed > -80);
            }
            if (biome == 35) rocky++;
        }
        assertTrue(beaches > 0);
        assertTrue(rocky > 0);
    }
}

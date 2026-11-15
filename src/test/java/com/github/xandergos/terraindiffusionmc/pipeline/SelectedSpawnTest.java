package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.world.SpawnChoice;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectedSpawnTest {
    @Test
    void choosesNearestDryForestGroundAndRejectsUnsuitableTerrain() {
        short[][] height = new short[97][97], biome = new short[97][97];
        for (int i = 0; i < 97; i++) {
            Arrays.fill(height[i], (short) 300);
            Arrays.fill(biome[i], (short) 208);
        }
        var data = new LocalTerrainProvider.HeightmapData(height, biome, 97, 97);
        var requested = new SpawnChoice(100, -100, "forest", 6);
        var spawn = SpawnSelector.findNearby(requested, data, 2, 300, (x, z) -> x != 100 || z != -100);
        assertNotNull(spawn);
        assertEquals(84, spawn.getY());
        assertTrue(spawn.getX() != 100 || spawn.getZ() != -100);
        assertNull(SpawnSelector.findNearby(requested, data, 2, 300, (x, z) -> false));
        assertNull(SpawnSelector.findNearby(new SpawnChoice(100, -100, "coast", 6), data, 2, 300, (x, z) -> true));
    }
}

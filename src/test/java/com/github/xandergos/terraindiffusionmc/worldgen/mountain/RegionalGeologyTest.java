package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class RegionalGeologyTest {
    @Test
    void testColdShelvesGenerateSnow() {
        var rocks = EnumSet.noneOf(RegionalGeology.Rock.class);
        var surfaces = EnumSet.noneOf(MountainSurfaceMaterials.Material.class);
        for (int z = -4096; z < 4096; z += 128)
            for (int x = -4096; x < 4096; x += 128) {
                rocks.add(RegionalGeology.region(310, x, z));
                surfaces.add(RegionalGeology.glacialSurface(310, x, z, 600, 0, 1));
                assertEquals(
                        MountainSurfaceMaterials.Material.NONE, RegionalGeology.glacialSurface(310, x, z, 250, 20, 1));
                assertEquals(
                        MountainSurfaceMaterials.Material.NONE, RegionalGeology.glacialSurface(310, x, z, 600, 0, 8));
            }
        assertEquals(EnumSet.allOf(RegionalGeology.Rock.class), rocks);
        assertEquals(
                EnumSet.of(
                        MountainSurfaceMaterials.Material.NONE,
                        MountainSurfaceMaterials.Material.SNOW,
                        MountainSurfaceMaterials.Material.MORAINE),
                surfaces);
        assertFalse(RegionalGeology.glacial(5, 300));
        assertTrue(RegionalGeology.glacial(5, 600));
    }
}

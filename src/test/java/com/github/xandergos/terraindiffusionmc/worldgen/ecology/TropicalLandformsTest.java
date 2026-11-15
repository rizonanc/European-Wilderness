package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TropicalLandformsTest {
    @Test
    void islandsRiseInOpenOceanAndSometimesHaveSatellites() {
        int groups = 0;
        int archipelagos = 0;
        for (int z = -10; z < 10; z++)
            for (int x = -10; x < 10; x++) {
                var group = TropicalLandforms.islands(42, x, z);
                if (group.isEmpty()) continue;
                groups++;
                if (group.size() > 1) archipelagos++;
                for (var island : group) {
                    assertTrue(island.height(-800, island.x(), island.z()) > 0);
                    assertEquals(50, island.height(50, island.x(), island.z()));
                    assertEquals(-800, island.height(-800, island.x() + 600, island.z()));
                }
            }
        assertTrue(groups > 0 && groups < 80);
        assertTrue(archipelagos > 0);
    }

    @Test
    void largeIslandsHaveDistinctCoastsAndKeepTheirEntireFootprintInsideTheirCell() {
        var profiles = new java.util.HashSet<java.util.List<Integer>>();
        var forms = new java.util.HashSet<TropicalLandforms.Form>();
        for (int z = -12; z <= 12; z++)
            for (int x = -12; x <= 12; x++) {
                var group = TropicalLandforms.islands(42, x, z, true);
                assertEquals(group, TropicalLandforms.islands(42, x, z, true));
                if (group.isEmpty() || group.getFirst().form() == TropicalLandforms.Form.SMALL) continue;
                var main = group.getFirst();
                forms.add(main.form());
                assertTrue(main.radius() >= 480);
                var coast = new java.util.ArrayList<Integer>();
                for (int i = 0; i < 16; i++) {
                    double angle = Math.TAU * i / 16;
                    int reach = 0;
                    for (int r = 16; r < 1600; r += 16) {
                        if (main.height(
                                        -800,
                                        main.x() + (int) (r * Math.cos(angle)),
                                        main.z() + (int) (r * Math.sin(angle)))
                                > 0) reach = r;
                    }
                    coast.add(reach);
                }
                assertTrue(java.util.Collections.max(coast) - java.util.Collections.min(coast) > 100);
                profiles.add(coast);
                int edge = TropicalLandforms.ISLAND_CELL;
                for (var island : group) {
                    assertEquals(-800, island.height(-800, x * edge, island.z()));
                    assertEquals(-800, island.height(-800, (x + 1) * edge, island.z()));
                    assertEquals(-800, island.height(-800, island.x(), z * edge));
                    assertEquals(-800, island.height(-800, island.x(), (z + 1) * edge));
                    assertEquals(80, island.height(80, island.x(), island.z()));
                }
            }
        assertEquals(java.util.Set.of(TropicalLandforms.Form.SANDY, TropicalLandforms.Form.ROCKY), forms);
        assertTrue(profiles.size() >= 20);
    }

    @Test
    void oasisHasADeepCenterAndDryRim() {
        var oasis = TropicalLandforms.oasis(42, 0, 0);
        assertTrue(oasis.distance(oasis.x(), oasis.z()) < 0.01);
        assertTrue(oasis.distance(oasis.x() + 50, oasis.z()) > 1);
        assertEquals(-5, TropicalLandforms.oasisOffset(0));
        assertTrue(TropicalLandforms.oasisOffset(0.8) > 0);
    }
}

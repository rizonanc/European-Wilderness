package com.github.xandergos.terraindiffusionmc.compat.roadweaver;

import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.PreparedWaterArea;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.GenerationProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RoadWeaverTerrainTest {
    @Test
    void testRoadCostPrefersGradualClimbs() {
        assertEquals(0d, RoadWeaverTerrain.mountainGradeCost(8, 2, 0));
        double steep = RoadWeaverTerrain.mountainGradeCost(8, 6, 0);
        assertTrue(steep > RoadWeaverTerrain.mountainGradeCost(8, 6, 8));
        assertEquals(steep, RoadWeaverTerrain.mountainGradeCost(-8, -6, 0));
    }

    @Test
    void bridgeDepthUsesTheLocalWaterSurface() {
        var session = WorldSession.activate(GenerationProfile.defaultsFor(2, "road-test", "cpu", "cpu"));
        try {
            session.attachRiverOverlay(
                    new WorldSession.RiverOverlay() {
                        @Override
                        public int carvedHeight(int x, int z, int raw) {
                            return raw;
                        }

                        @Override
                        public int waterY(int x, int z) {
                            return x == -16 && z == 15 ? 200 : PreparedWaterArea.NONE;
                        }
                    });
            int seaLevel = 63;
            int riverSurface = RoadWeaverTerrain.waterFirstAir(session, -16, 15, seaLevel);
            assertEquals(201, riverSurface);
            assertEquals(4, seaLevel - RoadWeaverTerrain.floorRelativeToSea(197, riverSurface, seaLevel));
            assertEquals(seaLevel, RoadWeaverTerrain.waterFirstAir(session, -15, 15, seaLevel));
            assertEquals(61, RoadWeaverTerrain.floorRelativeToSea(61, seaLevel, seaLevel));
        } finally {
            WorldSession.deactivate(session);
        }
        assertThrows(
                WorldSession.RetiredSessionException.class, () -> RoadWeaverTerrain.riverFirstAir(session, -16, 15));
    }
}

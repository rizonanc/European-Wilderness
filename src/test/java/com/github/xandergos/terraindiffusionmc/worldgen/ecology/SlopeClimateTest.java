package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlopeClimateTest {
    @Test void northSlopesAreCoolerAndWetterThanSouthSlopes() {
        float north = SlopeClimate.exposure(100, 140, 120, 120, 15);
        float south = SlopeClimate.exposure(140, 100, 120, 120, 15);
        assertTrue(SlopeClimate.temperature(north) < SlopeClimate.temperature(south));
        assertTrue(SlopeClimate.precipitation(north) > SlopeClimate.precipitation(south));
        assertEquals(0, SlopeClimate.exposure(120, 120, 150, 90, 15));
        assertEquals(0, SlopeClimate.exposure(120, 120, 120, 120, 15));
    }
}

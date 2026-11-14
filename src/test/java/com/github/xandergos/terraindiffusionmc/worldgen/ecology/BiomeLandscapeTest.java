package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.BiomeBalance;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeLandscapeTest {
    @Test void dryLandDesertMaskTriplesInBlockCoordinates() {
        int deserts = 0;
        for (int z = -12000; z < 12000; z += 701) {
            for (int x = -12000; x < 12000; x += 727) {
                short old = biome(x, z, 18, 260, 100, (short)8, false);
                short expanded = biome(3*x, 3*z, 18, 260, 100, (short)8, true);
                assertEquals(old == 5, expanded == 5);
                if (expanded == 5) deserts++;
                assertEquals(44, biome(x, z, 18, 260, -10, (short)44, true));
            }
        }
        assertTrue(deserts > 0);
    }

    @Test void wetForestGivesUpAboutFivePercentOfItsAreaToFields() {
        int oldFields = 0, newFields = 0, samples = 0;
        for (int z = -45000; z < 45000; z += 241) {
            for (int x = -45000; x < 45000; x += 257) {
                if (biome(x, z, 12, 950, 100, (short)8, false) == 229) oldFields++;
                if (biome(x, z, 12, 950, 100, (short)8, true) == 229) newFields++;
                samples++;
            }
        }
        double converted = (newFields - oldFields) / (double)(samples - oldFields);
        assertEquals(0.05, converted, 0.006);
    }

    @Test void raisingAmountExpandsCoastsDesertsAndFields() {
        var low = new BiomeBalance(0, 0, 0, 200, 0);
        var high = new BiomeBalance(200, 200, 200, 0, 200);
        assertTrue(low.adjustElevation(-20) > 0);
        assertTrue(high.adjustElevation(20) < 0);
        assertTrue(low.adjustElevation(1600) < 1600);
        assertTrue(high.adjustElevation(1600) > 1600);
        assertEquals(-800, high.adjustElevation(-800));

        int expandedDesert = 0, expandedField = 0;
        for (int z = -10000; z <= 10000; z += 167) {
            for (int x = -10000; x <= 10000; x += 173) {
                boolean lessDesert = biome(x, z, 18, 260, 100, (short)8, true, low) == 5;
                boolean moreDesert = biome(x, z, 18, 260, 100, (short)8, true, high) == 5;
                assertFalse(lessDesert && !moreDesert);
                if (!lessDesert && moreDesert) expandedDesert++;
                if (EuropeanBiomeClassifier.withOpenFields(43, x, z, (short)208, high) == 229) expandedField++;
                assertEquals(208, EuropeanBiomeClassifier.withOpenFields(43, x, z, (short)208, low));
            }
        }
        assertTrue(expandedDesert > 100);
        assertTrue(expandedField > 100);
    }

    private static short biome(int x, int z, float temp, float rain, float height, short upstream, boolean expanded) {
        return biome(x, z, temp, rain, height, upstream, expanded, BiomeBalance.DEFAULT);
    }

    private static short biome(int x, int z, float temp, float rain, float height, short upstream, boolean expanded, BiomeBalance balance) {
        float[] halo = new float[9];
        Arrays.fill(halo, height);
        return EuropeanBiomeClassifier.classify(43, new float[]{height}, new float[]{temp, 0, rain, 0},
            new short[]{upstream}, halo, 1, 1, 15, x, z, expanded, balance)[0];
    }
}

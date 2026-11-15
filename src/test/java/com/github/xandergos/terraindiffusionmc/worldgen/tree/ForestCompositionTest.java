package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanEcology;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForestCompositionTest {
    private static EuropeanEcology.ForestCondition climate() {
        return EuropeanEcology.evaluate(42, 9, 900, 200, 0, Double.POSITIVE_INFINITY, 0, 0);
    }

    @Test
    void testBlendsCrossOld96BlockStandBorders() {
        var ecology = climate();
        for (int border = -768; border <= 768; border += 96)
            for (int z = -512; z <= 512; z += 37) {
                var a = ForestComposition.at(42, border - 1, z, ecology);
                var b = ForestComposition.at(42, border, z, ecology);
                assertTrue(Math.abs(a.oak() - b.oak()) < .01);
                assertTrue(Math.abs(a.birch() - b.birch()) < .01);
                assertTrue(Math.abs(a.spruce() - b.spruce()) < .01);
                assertEquals(1, b.oak() + b.birch() + b.spruce() + b.pine(), 1e-9);
            }
    }

    @Test
    void testEachStandContainsMixedSpecies() {
        for (int sx = -2; sx <= 2; sx++)
            for (int sz = -2; sz <= 2; sz++) {
                var species = java.util.EnumSet.noneOf(TreeSpecies.class);
                int count = 0;
                for (int cz = sz * 6; cz < (sz + 1) * 6; cz++)
                    for (int cx = sx * 6; cx < (sx + 1) * 6; cx++)
                        for (var candidate : TreeCandidates.rawAdultRootsForChunk(42, cx, cz)) {
                            species.add(
                                    ForestComposition.at(42, candidate.x(), candidate.z(), climate())
                                            .choose(candidate.speciesRoll()));
                            count++;
                        }
                assertTrue(count > 100);
                assertTrue(species.size() >= 3, "Monoculture stand " + sx + "," + sz + ": " + species);
            }
    }

    @Test
    void testSpeciesFieldDependsOnSeedAndLocation() {
        var ecology = climate();
        assertEquals(ForestComposition.at(42, 17, -19, ecology), ForestComposition.at(42, 17, -19, ecology));
        assertNotEquals(ForestComposition.at(42, 17, -19, ecology), ForestComposition.at(43, 17, -19, ecology));
        assertNotEquals(ForestComposition.at(42, 17, -19, ecology), ForestComposition.at(42, 700, 800, ecology));
    }
}

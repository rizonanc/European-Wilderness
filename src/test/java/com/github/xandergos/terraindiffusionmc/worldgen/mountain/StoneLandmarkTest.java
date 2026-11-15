package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoneLandmarkTest {
    @Test
    void archHasTwoFeetAndAnOpenPassage() {
        assertFalse(StoneLandmark.solid(StoneLandmark.Shape.ARCH, 0, 0, 0, 16));
        assertTrue(StoneLandmark.solid(StoneLandmark.Shape.ARCH, -5, 0, 0, 16));
        assertTrue(StoneLandmark.solid(StoneLandmark.Shape.ARCH, 5, 0, 0, 16));
        assertTrue(StoneLandmark.solid(StoneLandmark.Shape.ARCH, 0, 0, 15, 16));
        assertTrue(StoneLandmark.solid(StoneLandmark.Shape.PLATEAU, 4, 0, 15, 16));
        assertFalse(StoneLandmark.solid(StoneLandmark.Shape.SPIRE, 4, 0, 15, 16));
    }
}

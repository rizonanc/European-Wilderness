package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxyWaterDiagnosticsTest {
    @Test void missingTopFaceIsDecodedIndependentlyFromOtherFacesAndModelFlags() {
        long allPresent = 0x0032000000000000L;
        for (int face = 0; face < 6; face++) assertTrue(VoxyWaterDiagnostics.facePresent(allPresent,face));
        long missingTop = allPresent | 0xFF00L;
        for (int face = 0; face < 6; face++) assertEquals(face != 1,VoxyWaterDiagnostics.facePresent(missingTop,face));
        long allAbsent = 0x0012FFFFFFFFFFFFL;
        for (int face = 0; face < 6; face++) assertFalse(VoxyWaterDiagnostics.facePresent(allAbsent,face));
    }

    @Test void reservedFaceBitsCannotBeMistakenForModelFlags() {
        assertThrows(IllegalArgumentException.class,()->VoxyWaterDiagnostics.facePresent(-1,-1));
        assertThrows(IllegalArgumentException.class,()->VoxyWaterDiagnostics.facePresent(-1,6));
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OptionalBridgeFailureTest {
    @Test
    void testApiMismatchDisablesOnlyOptionalBridge() throws Exception {
        var supported = WorldGenCompat.class.getDeclaredField("supported");
        supported.setAccessible(true);
        var installed = WorldGenCompat.class.getDeclaredField("installed");
        installed.setAccessible(true);
        var problem = WorldGenCompat.class.getDeclaredField("problem");
        problem.setAccessible(true);
        boolean oldSupported = supported.getBoolean(null), oldInstalled = installed.getBoolean(null);
        Object oldProblem = problem.get(null);
        try {
            assertDoesNotThrow(() -> WorldGenCompat.unavailable(new NoSuchMethodException("fixture API")));
            assertFalse(WorldGenCompat.present());
            assertFalse(WorldGenCompat.safeToStream());
            assertTrue(WorldGenCompat.problem().contains("NoSuchMethodException"));
        } finally {
            supported.setBoolean(null, oldSupported);
            installed.setBoolean(null, oldInstalled);
            problem.set(null, oldProblem);
        }
    }
}

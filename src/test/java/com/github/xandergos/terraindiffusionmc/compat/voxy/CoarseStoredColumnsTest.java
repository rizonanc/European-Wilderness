package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CoarseStoredColumnsTest {
    @Test
    void diskOwnershipWithoutVoxyDataDoesNotCreateAProtectedRectangularVoid() {
        var columns = new CoarseStoredColumns();
        assertFalse(columns.ready(367, -512));
        assertTrue(columns.contains(367, -512));
        assertEquals(1, columns.prepare(2, -64, 1871, (x, y, z) -> null, v -> v == 0));
        assertTrue(columns.ready(367, -512));
        assertFalse(columns.contains(367, -512));
    }

    @Test
    void exactEvidenceProtectsTheWholeColumnIncludingAirAboveAndBelowIt() {
        var columns = new CoarseStoredColumns();
        columns.ready(-1, -513);
        long[] raw = new long[32768];
        raw[(5 << 10) + 31 * 32 + 31] = 99;
        columns.prepare(1, -64, 1871, (x, y, z) -> y == 0 ? raw : null, v -> v == 0);
        assertTrue(columns.contains(-1, -513));
        assertFalse(columns.contains(-17, -513));
        assertEquals(99, raw[(5 << 10) + 31 * 32 + 31]);
    }

    @Test
    void testQueueDeduplicatesAndReadFailureLeavesOwnershipProtected() {
        var columns = new CoarseStoredColumns();
        for (int i = 0; i < 10; i++) columns.ready(0, 0);
        assertThrows(
                IllegalStateException.class,
                () ->
                        columns.prepare(
                                2,
                                -64,
                                1871,
                                (x, y, z) -> {
                                    throw new IllegalStateException();
                                },
                                v -> v == 0));
        assertTrue(columns.contains(0, 0));
        assertFalse(columns.ready(0, 0));
        assertEquals(1, columns.prepare(2, -64, 1871, (x, y, z) -> null, v -> v == 0));
    }

    @Test
    void testInvalidationDuringProbeDiscardsTheOldSnapshot() throws Exception {
        var columns = new CoarseStoredColumns();
        assertFalse(columns.ready(-513, 1040));
        var started = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var old =
                    executor.submit(() ->
                            columns.prepare(
                                    1,
                                    0,
                                    511,
                                    (x, y, z) -> {
                                        started.countDown();
                                        try {
                                            assertTrue(resume.await(5, TimeUnit.SECONDS));
                                        } catch (InterruptedException e) {
                                            throw new AssertionError(e);
                                        }
                                        return null;
                                    },
                                    v -> v == 0));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) columns.invalidate(-513, 1040);
            resume.countDown();
            assertEquals(1, old.get(5, TimeUnit.SECONDS));
            assertFalse(columns.knownContains(-513, 1040));
            assertFalse(columns.ready(-513, 1040));
            long[] data = new long[32768];
            data[0] = 99;
            assertEquals(1, columns.prepare(10, 0, 511, (x, y, z) -> data, v -> v == 0));
            assertTrue(columns.knownContains(-1024, 1024));
        }
    }
}

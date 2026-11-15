package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CoarseVirtualHistoryTest {
    @Test
    void oldVirtualSkyIsStillKnownAfterMoreSectionsThanThePreviousLruCouldHold() {
        var history = new CoarseVirtualHistory();
        assertFalse(history.contains(1234));
        for (int i = 0; i < 200000; i++) history.mark(i * 0x9e3779b97f4a7c15L);
        for (int i = 0; i < 200000; i++)
            assertTrue(history.contains(i * 0x9e3779b97f4a7c15L), "Lost old geometry " + i);
        assertEquals(4 * 1024 * 1024, history.storageBytes());
    }

    @Test
    void saturationMayRequestExtraRebuildsButNeverForgetsGeometryOrGrows() {
        var history = new CoarseVirtualHistory(6);
        for (int i = 0; i < 10000; i++) history.mark(i);
        for (int i = 0; i < 10000; i++) assertTrue(history.contains(i));
        assertEquals(8, history.storageBytes());
    }

    @Test
    void parallelMarksDoNotLoseBits() throws Exception {
        var history = new CoarseVirtualHistory();
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int n = 0; n < 4; n++) {
                int id = n;
                jobs.add(workers.submit(() -> {
                    for (int i = 0; i < 20000; i++) history.mark(((long) id << 60) ^ (i * 1234567891L));
                }));
            }
            for (var job : jobs) job.get();
        }
        for (int n = 0; n < 4; n++)
            for (int i = 0; i < 20000; i++) assertTrue(history.contains(((long) n << 60) ^ (i * 1234567891L)));
    }
}

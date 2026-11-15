package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.nio.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CoarseOwnershipGuardTest {
    @TempDir Path temp;

    private void save(int x, int z, int index) throws Exception {
        byte[] bytes = new byte[8192];
        ByteBuffer.wrap(bytes).putInt(index * 4, 0x201);
        Files.write(temp.resolve("r." + x + "." + z + ".mca"), bytes);
    }

    @Test
    void testRenderRequestsNeverReadFiles() throws Exception {
        var guard = new LodChunkGuard(temp);
        Files.write(temp.resolve("r.0.0.mca"), new byte[1]);
        for (int i = 0; i < 100; i++) assertFalse(guard.renderRegionReady(0, 0));
        assertEquals(0, guard.failures());
        assertTrue(guard.savedRealColumn(8, 8));
        assertEquals(1, guard.prepareRenderRegions(32));
        assertEquals(1, guard.failures());
        assertTrue(guard.renderRegionReady(0, 0));
        assertTrue(guard.savedRealColumn(8, 8));
    }

    @Test
    void savedChunksAndAirAreProtectedEvenIfTheyNeverLoadedThisSession() throws Exception {
        save(-1, -2, 31 * 32 + 31);
        Path file = temp.resolve("r.-1.-2.mca");
        byte[] unchanged = Files.readAllBytes(file);
        var guard = new LodChunkGuard(temp);
        assertFalse(guard.renderRegionReady(-512, -1024));
        assertEquals(1, guard.prepareRenderRegions(1));
        assertTrue(guard.renderRegionReady(-512, -1024));
        assertTrue(guard.savedRealColumn(-1, -513));
        assertFalse(guard.savedRealColumn(-17, -513));
        assertFalse(guard.knownRealColumn(-1, -513), "Saved ownership does not invent a chunk-load event");
        assertArrayEquals(unchanged, Files.readAllBytes(file));
    }

    @Test
    void missingRegionAllowsPreviewOnlyAfterBackgroundCheckAndLoadedChunksStillWin() {
        var guard = new LodChunkGuard(temp);
        assertFalse(guard.renderRegionReady(512, 0));
        assertEquals(1, guard.prepareRenderRegions(32));
        assertTrue(guard.renderRegionReady(1023, 511));
        assertFalse(guard.savedRealColumn(520, 8));
        guard.markLoaded(32, 0);
        assertTrue(guard.knownRealColumn(520, 8));
        assertEquals(0, guard.failures());
    }

    @Test
    void testOwnershipReadsAreBatched() {
        var guard = new LodChunkGuard(temp);
        for (int i = 0; i < 5; i++) assertFalse(guard.renderRegionReady(i * 512, 0));
        assertEquals(2, guard.prepareRenderRegions(2));
        for (int i = 0; i < 2; i++) assertTrue(guard.renderRegionReady(i * 512, 0));
        for (int i = 2; i < 5; i++) assertFalse(guard.renderRegionReady(i * 512, 0));
        assertEquals(3, guard.prepareRenderRegions(32));
        assertEquals(0, guard.prepareRenderRegions(32));
        for (int i = 0; i < 5; i++) assertFalse(guard.savedRealColumn(i * 512, 0));
    }

    @Test
    void unreadableHeaderStaysProtectedButCanRecoverThroughAnotherBackgroundRead() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var guard = new LodChunkGuard(temp, clock::get);
        Files.write(temp.resolve("r.0.0.mca"), new byte[1]);
        assertFalse(guard.renderRegionReady(0, 0));
        guard.prepareRenderRegions(1);
        assertTrue(guard.savedRealColumn(0, 0));
        assertTrue(guard.renderRegionReady(0, 0));
        save(0, 0, 1);
        clock.addAndGet(2_000_000_000L);
        assertFalse(guard.renderRegionReady(0, 0));
        assertEquals(1, guard.failures(), "Render request still performs no IO");
        assertTrue(guard.savedRealColumn(0, 0));
        assertEquals(1, guard.prepareRenderRegions(1));
        assertTrue(guard.renderRegionReady(0, 0));
        assertFalse(guard.savedRealColumn(0, 0));
        assertTrue(guard.savedRealColumn(16, 0));
    }

    @Test
    void testRenderRequestQueueHasABound() {
        var guard = new LodChunkGuard(temp);
        for (int i = 0; i < 10000; i++) assertFalse(guard.renderRegionReady(i * 512, 0));
        assertTrue(guard.renderStatistics().contains("pending=8192"));
        assertEquals(0, guard.failures());
        assertEquals(32, guard.prepareRenderRegions(32));
        assertFalse(guard.renderRegionReady(9999 * 512, 0));
        assertTrue(guard.renderStatistics().contains("pending=8161"));
    }

    @Test
    void testBackgroundDiskCacheLockNeverBlocksLoadedChunkProtection() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var guard =
                new LodChunkGuard(
                        temp,
                        () -> {
                            entered.countDown();
                            try {
                                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(failure);
                            }
                            return 1L;
                        });
        assertFalse(guard.renderRegionReady(0, 0));
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var disk = threads.submit(() -> guard.prepareRenderRegions(1));
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                threads.submit(() -> guard.markLoaded(0, 0)).get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(guard.knownRealColumn(0, 0));
                assertFalse(guard.renderRegionReady(512, 0));
            } finally {
                release.countDown();
            }
            assertEquals(1, disk.get(2, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}

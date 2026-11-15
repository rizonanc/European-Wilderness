package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LodChunkGuardTest {
    @TempDir Path temp;

    private Path region(int x, int z) {
        return temp.resolve("r." + x + "." + z + ".mca");
    }

    private void save(int rx, int rz, int... indices) throws Exception {
        byte[] bytes = new byte[8192];
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        for (int index : indices) buffer.putInt(index * 4, 0x0201);
        Files.write(region(rx, rz), bytes);
    }

    @Test
    void testOneRealSixteenChunkProtectsOnlyItsQuadrant() throws Exception {
        save(0, 0, 0);
        byte[] original = Files.readAllBytes(region(0, 0));
        var guard = new LodChunkGuard(temp);
        assertEquals(14, guard.writableMask(0, 0));
        assertFalse(LodChunkGuard.includes(14, 0, 0));
        for (int y = 0; y < 2; y++)
            for (int z = 0; z < 2; z++)
                for (int x = 0; x < 2; x++) assertEquals(x != 0 || z != 0, LodChunkGuard.includes(14, x, z));
        assertArrayEquals(original, Files.readAllBytes(region(0, 0)));
        try (var files = Files.list(temp)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void allSavedLoadedAndMixedMasksAreProtectedWithNegativeCoordinates() throws Exception {
        save(-1, -1, 30 * 32 + 30, 31 * 32 + 31);
        var guard = new LodChunkGuard(temp);
        assertEquals(6, guard.writableMask(-1, -1));
        guard.markLoaded(-1, -2);
        assertEquals(4, guard.writableMask(-1, -1));
        guard.markLoaded(-2, -1);
        assertEquals(0, guard.writableMask(-1, -1));
        assertEquals(
                9, guard.unseenMask(-1, -1), "Disk-only protection is applied by the worker, without IO in unseenMask");
    }

    @Test
    void missingRegionsAllowPreviewButCorruptOrUnexpectedPathsFailClosed() throws Exception {
        var guard = new LodChunkGuard(temp);
        assertEquals(15, guard.writableMask(0, 0));
        assertEquals(0, guard.failures());
        Files.write(region(1, 0), new byte[128]);
        assertEquals(0, guard.writableMask(16, 0));
        assertEquals(1, guard.failures());
        assertTrue(guard.uncertain());
        Files.createDirectory(region(2, 0));
        assertEquals(0, guard.writableMask(32, 0));
    }

    @Test
    void testHeaderCacheRefreshes() throws Exception {
        var clock = new AtomicLong(1_000_000_000);
        var guard = new LodChunkGuard(temp, clock::get);
        assertEquals(15, guard.writableMask(0, 0));
        save(0, 0, 0);
        assertEquals(15, guard.writableMask(0, 0));
        guard.markLoaded(1, 1);
        assertEquals(7, guard.writableMask(0, 0));
        clock.addAndGet(2_000_000_000L);
        assertEquals(6, guard.writableMask(0, 0));
    }

    @Test
    void testRememberedChunkLimitStopsWrites() {
        var guard = new LodChunkGuard(temp);
        for (int i = 0; i < 4097; i++) guard.markLoaded(i * 32, 0);
        assertTrue(guard.overflow());
        assertEquals(0, guard.unseenMask(-1, -1));
    }

    @Test
    void testATemporarilyIncompleteHeaderCanRecover() throws Exception {
        var clock = new AtomicLong(1_000_000_000);
        var guard = new LodChunkGuard(temp, clock::get);
        Files.write(region(0, 0), new byte[128]);
        assertEquals(0, guard.writableMask(0, 0));
        assertTrue(guard.uncertain());
        save(0, 0, 0);
        clock.addAndGet(2_000_000_000L);
        assertEquals(14, guard.writableMask(0, 0));
        assertFalse(guard.uncertain());
    }
}

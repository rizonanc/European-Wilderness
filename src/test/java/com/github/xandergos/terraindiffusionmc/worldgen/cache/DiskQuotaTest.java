package com.github.xandergos.terraindiffusionmc.worldgen.cache;

import static org.junit.jupiter.api.Assertions.*;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DiskQuotaTest {
    @TempDir
    Path temp;

    static TerrainTileKey key(int x) {
        return TerrainTileKey.ofTile(7, x, -1, 16, "phase1");
    }

    static TerrainTile tile(TerrainTileKey key, boolean climate) {
        short[] height = new short[256], biome = new short[256];
        Arrays.fill(height, (short) 600);
        Arrays.fill(biome, (short) 2);
        return new TerrainTile(key, height, biome, climate ? new float[1024] : null);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 1000, 10000})
    void warmChecksNeverEnumerateOrStatTheDirectory(int count) throws Exception {
        for (int i = 0; i < count; i++) Files.write(temp.resolve(i + ".ewt"), new byte[] {1});
        var store = new DiskTerrainStore(temp);
        assertEquals(count, store.usedBytes());
        var before = store.quotaStatistics();
        for (int i = 0; i < 1000; i++) {
            try (var lease = store.tryReserve(2, count + 2)) {
                assertNotNull(lease);
            }
            assertEquals(count, store.usedBytes());
        }
        var after = store.quotaStatistics();
        assertEquals(1, after.scans());
        assertEquals(before.sizeQueries(), after.sizeQueries());
        assertEquals(0, after.reservedBytes());
    }

    @Test
    void replacementDeletionAndRepeatedCloseAccountExactly() throws Exception {
        var store = new DiskTerrainStore(temp);
        var key = key(0);
        store.store(key, tile(key, false));
        assertEquals(Files.size(temp.resolve(key.fileName())), store.usedBytes());
        long small = store.usedBytes();
        store.store(key, tile(key, true));
        assertTrue(store.usedBytes() > small);
        store.store(key, tile(key, false));
        assertEquals(small, store.usedBytes());
        var lease = store.tryReserve(100, small + 100);
        assertNotNull(lease);
        assertNull(store.tryReserve(1, small + 100));
        lease.close();
        lease.close();
        assertEquals(0, store.quotaStatistics().reservedBytes());
        assertTrue(store.delete(key));
        assertFalse(store.delete(key));
        assertEquals(0, store.usedBytes());
    }

    @Test
    void testReopenReconcilesExternalChanges() throws Exception {
        var store = new DiskTerrainStore(temp);
        var key = key(0);
        store.store(key, tile(key, false));
        Path orphan = temp.resolve(key.fileName() + ".tmp-crash");
        Files.write(orphan, new byte[123]);
        Files.delete(temp.resolve(key.fileName()));
        var reopened = new DiskTerrainStore(temp);
        assertEquals(123, reopened.usedBytes());
        assertEquals(123, store.usedBytes(), "same root shares the reconciled account");
        Files.delete(orphan);
        assertEquals(0, reopened.reconcileUsedBytes());
    }

    @Test
    void testTwoStoresShareReservations() throws Exception {
        var first = new DiskTerrainStore(temp);
        var second = new DiskTerrainStore(temp.resolve("."));
        var key = key(1);
        long limit = DiskTerrainStore.worstCaseEncodedSize(key);
        try (var lease = first.tryReserve(limit, limit)) {
            assertNotNull(lease);
            assertNull(second.tryReserve(1, limit));
            first.store(key, tile(key, true), lease);
            assertEquals(0, second.quotaStatistics().reservedBytes());
            assertEquals(Files.size(temp.resolve(key.fileName())), second.usedBytes());
        }
    }

    @Test
    void concurrentWritesAndDeletesMatchAuthoritativeReconciliation() throws Exception {
        var store = new DiskTerrainStore(temp);
        store.usedBytes();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final int x = i;
                jobs.add(pool.submit(() -> {
                    var key = key(x);
                    store.store(key, tile(key, true));
                    store.store(key, tile(key, false));
                    if ((x & 1) == 0) store.delete(key);
                    return null;
                }));
            }
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
        }
        long incremental = store.usedBytes();
        assertEquals(incremental, store.reconcileUsedBytes());
        assertEquals(0, store.quotaStatistics().reservedBytes());
    }

    @Test
    void failedWriteCleansTemporaryAndReleasesReservation() throws Exception {
        var store = new DiskTerrainStore(temp);
        var key = key(0);
        Files.createDirectory(temp.resolve(key.fileName()));
        Files.write(temp.resolve(key.fileName()).resolve("occupied"), new byte[1]);
        try (var lease = store.tryReserve(100000, 1000000)) {
            assertThrows(IOException.class, () -> store.store(key, tile(key, false), lease));
        }
        assertEquals(0, store.quotaStatistics().reservedBytes());
        assertEquals(store.reconcileUsedBytes(), store.usedBytes());
        try (var files = Files.list(temp)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().contains(".tmp-")));
        }
    }

    @Test
    void testRepositoryRejectsBeforeInference() throws Exception {
        var store = new DiskTerrainStore(temp);
        var calls = new AtomicInteger();
        var key = key(0);
        var low = new TerrainRepository(
                store,
                k -> {
                    calls.incrementAndGet();
                    return tile(k, false);
                },
                16,
                10000,
                1);
        assertThrows(TerrainRepository.DiskQuotaExceededException.class, () -> low.get(key));
        assertEquals(0, calls.get());
        var retry = new TerrainRepository(
                store,
                k -> {
                    if (calls.incrementAndGet() == 1) throw new IOException("retry");
                    return tile(k, false);
                },
                16,
                10000,
                1000000);
        assertThrows(Exception.class, () -> retry.get(key));
        assertEquals(0, store.quotaStatistics().reservedBytes());
        assertEquals(tile(key, false), retry.get(key));
        assertEquals(2, calls.get());
    }

    @Test
    void testUnreadableAccountDoesNotPublishZeroAfterScanFailure() throws Exception {
        Path notDirectory = temp.resolve("file");
        Files.write(notDirectory, new byte[1]);
        var store = new DiskTerrainStore(notDirectory);
        assertThrows(IOException.class, store::usedBytes);
        assertFalse(store.quotaStatistics().initialized());
        assertThrows(IOException.class, () -> store.tryReserve(10, 1000));
    }
}

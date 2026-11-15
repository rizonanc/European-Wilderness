package com.github.xandergos.terraindiffusionmc.worldgen.cache;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.CRC32;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class ColdDiskLoadingTest {
    @TempDir Path temp;

    @AfterEach
    void retire() {
        WorldSession.deactivate(WorldSession.current());
    }

    static TerrainTileKey key(int x) {
        return DiskQuotaTest.key(x);
    }

    static TerrainTile tile(TerrainTileKey key) {
        return DiskQuotaTest.tile(key, false);
    }

    static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Test read gate timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        }
    }

    TerrainRepository repo(DiskTerrainStore store) {
        return new TerrainRepository(
                store,
                k -> {
                    throw new AssertionError("A validated disk hit must not infer");
                },
                16,
                1 << 20,
                1 << 24);
    }

    static void waitForRepoWait(Thread thread) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            if ((thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING)
                    && Arrays.stream(thread.getStackTrace())
                            .anyMatch(e -> e.getClassName().endsWith("TerrainRepository"))) return;
            Thread.sleep(1);
        }
        fail("Reader never joined repository wait");
    }

    @Test
    void concurrentGetAndPeekPublishOneDecodedObjectAndWarmRamDoesNoIo() throws Exception {
        var key = key(0);
        new DiskTerrainStore(temp).store(key, tile(key));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var store =
                new DiskTerrainStore(
                        temp,
                        p -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            await(release);
                            return Files.readAllBytes(p);
                        });
        var repo = repo(store);
        assertTrue(repo.peekRam(key).isEmpty());
        assertEquals(0, calls.get());
        var workers = Executors.newFixedThreadPool(16);
        var threads = Collections.synchronizedList(new ArrayList<Thread>());
        try {
            List<Future<TerrainTile>> jobs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final boolean peek = (i & 1) == 0;
                jobs.add(
                        workers.submit(() -> {
                            threads.add(Thread.currentThread());
                            return peek ? repo.peek(key).orElseThrow() : repo.get(key);
                        }));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (threads.size() < 16 && System.nanoTime() < end) Thread.sleep(1);
            for (var thread : threads) waitForRepoWait(thread);
            assertEquals(1, calls.get());
            release.countDown();
            TerrainTile shared = jobs.getFirst().get(5, TimeUnit.SECONDS);
            for (var job : jobs) assertSame(shared, job.get(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) {
                assertSame(shared, repo.get(key));
                assertSame(shared, repo.peek(key).orElseThrow());
            }
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentKeysCanReadIndependently() throws Exception {
        var a = key(0);
        var b = key(1);
        var writer = new DiskTerrainStore(temp);
        writer.store(a, tile(a));
        writer.store(b, tile(b));
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var repo =
                repo(
                        new DiskTerrainStore(
                                temp,
                                p -> {
                                    entered.countDown();
                                    await(release);
                                    return Files.readAllBytes(p);
                                }));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> repo.get(a));
            var second = pool.submit(() -> repo.get(b));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "different keys must not share a global IO monitor");
            release.countDown();
            assertEquals(tile(a), first.get(5, TimeUnit.SECONDS));
            assertEquals(tile(b), second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void cancellingTheInitiatingConsumerDoesNotCancelSharedDiskRead() throws Exception {
        var key = key(0);
        new DiskTerrainStore(temp).store(key, tile(key));
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var repo =
                repo(
                        new DiskTerrainStore(
                                temp,
                                p -> {
                                    calls.incrementAndGet();
                                    entered.countDown();
                                    await(release);
                                    return Files.readAllBytes(p);
                                }));
        var pool = Executors.newFixedThreadPool(2);
        var waiterThread = new AtomicReference<Thread>();
        try {
            var initiating = pool.submit(() -> repo.get(key));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var waiter =
                    pool.submit(() -> {
                        waiterThread.set(Thread.currentThread());
                        return repo.get(key);
                    });
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (waiterThread.get() == null && System.nanoTime() < end) Thread.sleep(1);
            waitForRepoWait(waiterThread.get());
            assertTrue(initiating.cancel(true));
            release.countDown();
            assertEquals(tile(key), waiter.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void interruptedWaiterLeavesTheOwnerAndOtherConsumersAlive() throws Exception {
        var key = key(0);
        new DiskTerrainStore(temp).store(key, tile(key));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var repo =
                repo(
                        new DiskTerrainStore(
                                temp,
                                p -> {
                                    entered.countDown();
                                    await(release);
                                    return Files.readAllBytes(p);
                                }));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var owner = pool.submit(() -> repo.get(key));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var failure = new AtomicReference<Throwable>();
            var waiter =
                    new Thread(() -> {
                        try {
                            assertThrows(InterruptedException.class, () -> repo.get(key));
                        } catch (Throwable e) {
                            failure.set(e);
                        }
                    });
            waiter.start();
            waitForRepoWait(waiter);
            waiter.interrupt();
            waiter.join(5000);
            assertFalse(waiter.isAlive());
            assertNull(failure.get());
            release.countDown();
            assertEquals(tile(key), owner.get(5, TimeUnit.SECONDS));
            assertTrue(repo.peekRam(key).isPresent());
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedFlightCanRetryAndCorruptDataNeverBecomesAValidPresenceHit() throws Exception {
        var key = key(0);
        var writer = new DiskTerrainStore(temp);
        writer.store(key, tile(key));
        var calls = new AtomicInteger();
        var store =
                new DiskTerrainStore(
                        temp,
                        p -> {
                            if (calls.incrementAndGet() == 1) throw new IllegalStateException("retry");
                            return Files.readAllBytes(p);
                        });
        var repo = repo(store);
        assertThrows(IllegalStateException.class, () -> repo.get(key));
        assertEquals(tile(key), repo.get(key));
        assertEquals(2, calls.get());
        byte[] bad = Files.readAllBytes(temp.resolve(key.fileName()));
        bad[bad.length - 1] ^= 1;
        Files.write(temp.resolve(key.fileName()), bad);
        var cold = repo(new DiskTerrainStore(temp));
        assertTrue(cold.peek(key).isEmpty());
        assertTrue(cold.peekRam(key).isEmpty());
        writer.store(key, tile(key));
        assertEquals(tile(key), cold.get(key));
    }

    @Test
    void missingReadAndIoFailureRetainTheInferenceFallback() throws Exception {
        var key = key(0);
        var calls = new AtomicInteger();
        var store =
                new DiskTerrainStore(
                        temp,
                        p -> {
                            throw new IOException("unreadable");
                        });
        var repo =
                new TerrainRepository(
                        store,
                        k -> {
                            calls.incrementAndGet();
                            return tile(k);
                        },
                        16,
                        1 << 20,
                        1 << 24);
        assertTrue(repo.peek(key).isEmpty());
        assertEquals(tile(key), repo.get(key));
        assertEquals(1, calls.get());
        assertEquals(tile(key), new DiskTerrainStore(temp).load(key).orElseThrow());
    }

    @Test
    void oldVersionIsValidatedWhileUnknownTruncatedAndForeignFilesAreMisses() throws Exception {
        var key = key(0);
        var store = new DiskTerrainStore(temp);
        var body = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(body)) {
            out.writeInt(1);
            out.writeUTF(key.fingerprint());
            out.writeLong(key.seed());
            out.writeInt(key.tileX());
            out.writeInt(key.tileZ());
            out.writeInt(16);
            out.writeInt(2);
            for (int layer = 1; layer <= 2; layer++) {
                out.writeByte(layer);
                out.writeInt(256);
                for (int i = 0; i < 256; i++) out.writeShort(layer == 1 ? 600 : 2);
            }
        }
        Path file = temp.resolve(key.fileName());
        writeEnvelope(file, body.toByteArray());
        assertEquals(tile(key), store.load(key).orElseThrow());
        var bytes = body.toByteArray();
        bytes[3] = 99;
        writeEnvelope(file, bytes);
        assertTrue(store.load(key).isEmpty());
        Files.write(file, new byte[] {1, 2, 3});
        assertTrue(store.load(key).isEmpty());
        var foreign = key(2);
        store.store(foreign, tile(foreign));
        Files.copy(temp.resolve(foreign.fileName()), file, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(store.load(key).isEmpty());
    }

    static void writeEnvelope(Path path, byte[] body) throws Exception {
        var crc = new CRC32();
        crc.update(body);
        try (var out = new DataOutputStream(Files.newOutputStream(path))) {
            out.write(new byte[] {69, 87, 84, 49});
            out.writeLong(crc.getValue());
            out.writeInt(body.length);
            out.write(body);
        }
    }

    @Test
    void retiredReadIsDiscardedAndReopenedWorldLoadsTheSameBytes() throws Exception {
        var key = key(0);
        new DiskTerrainStore(temp).store(key, tile(key));
        var session = WorldSession.activate(GenerationProfile.defaultsFor(2, "model", "cpu", "cpu"), 7);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var repo =
                new TerrainRepository(
                        new DiskTerrainStore(
                                temp,
                                p -> {
                                    entered.countDown();
                                    await(release);
                                    return Files.readAllBytes(p);
                                }),
                        k -> {
                            throw new AssertionError();
                        },
                        16,
                        1 << 20,
                        1 << 24,
                        session);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var old = pool.submit(() -> repo.get(key));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            WorldSession.deactivate(session);
            release.countDown();
            assertThrows(ExecutionException.class, () -> old.get(5, TimeUnit.SECONDS));
            var next = WorldSession.activate(session.profile(), 7);
            var reopened =
                    new TerrainRepository(
                            new DiskTerrainStore(temp),
                            k -> {
                                throw new AssertionError();
                            },
                            16,
                            1 << 20,
                            1 << 24,
                            next);
            assertEquals(tile(key), reopened.get(key));
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void saturatedReadQueueMakesProgressWithoutNestedWorkerDependencies() throws Exception {
        var writer = new DiskTerrainStore(temp);
        for (int i = 0; i < 80; i++) writer.store(key(i), tile(key(i)));
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var repo =
                repo(
                        new DiskTerrainStore(
                                temp,
                                p -> {
                                    entered.countDown();
                                    await(release);
                                    return Files.readAllBytes(p);
                                }));
        var pool = Executors.newFixedThreadPool(80);
        try {
            List<Future<TerrainTile>> jobs = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                int x = i;
                jobs.add(pool.submit(() -> repo.get(key(x))));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (int i = 0; i < 80; i++) assertEquals(tile(key(i)), jobs.get(i).get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void nonAtomicCommitValidationDoesNotJoinAnOlderSharedRead() throws Exception {
        var key = key(0);
        var original = tile(key);
        var replacement = DiskQuotaTest.tile(key, true);
        new DiskTerrainStore(temp).store(key, original);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new AtomicInteger();
        var store =
                new DiskTerrainStore(
                        temp,
                        p -> {
                            byte[] data = Files.readAllBytes(p);
                            if (reads.incrementAndGet() == 1) {
                                entered.countDown();
                                await(release);
                            }
                            return data;
                        },
                        (source, target, options) -> {
                            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
                                throw new AtomicMoveNotSupportedException(
                                        source.toString(), target.toString(), "forced test fallback");
                            return Files.move(source, target, options);
                        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var old = pool.submit(() -> store.load(key));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.submit(
                            () -> {
                                store.store(key, replacement);
                                return null;
                            })
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, reads.get(), "commit must validate new bytes without waiting for the old flight");
            release.countDown();
            assertEquals(original, old.get(5, TimeUnit.SECONDS).orElseThrow());
            assertEquals(replacement, store.load(key).orElseThrow());
            assertEquals(Files.size(temp.resolve(key.fileName())), store.usedBytes());
        } finally {
            release.countDown();
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void nonAtomicCorruptCommitIsRejectedAndQuotaRecovers() throws Exception {
        var key = key(0);
        var store =
                new DiskTerrainStore(
                        temp,
                        Files::readAllBytes,
                        (source, target, options) -> {
                            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
                                throw new AtomicMoveNotSupportedException(
                                        source.toString(), target.toString(), "forced test fallback");
                            var result = Files.move(source, target, options);
                            byte[] data = Files.readAllBytes(target);
                            data[data.length - 1] ^= 1;
                            Files.write(target, data);
                            return result;
                        });
        assertThrows(IOException.class, () -> store.store(key, tile(key)));
        assertFalse(Files.exists(temp.resolve(key.fileName())));
        assertEquals(0, store.usedBytes());
        assertEquals(0, store.quotaStatistics().reservedBytes());
    }
}

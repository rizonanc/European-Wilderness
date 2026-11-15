package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuSessionCacheTest {
    static final class Session {
        boolean closed;
        final int id;

        Session(int id) {
            this.id = id;
        }
    }

    static final class Fixture {
        int creates;
        final List<Integer> closed = new ArrayList<>();
        final GpuSessionCache<String, Session> cache =
                new GpuSessionCache<>((k, v) -> {
                    assertFalse(v.closed);
                    v.closed = true;
                    closed.add(v.id);
                });

        Session run(String key, int capacity) throws Exception {
            return cache.execute(
                    key,
                    capacity,
                    () -> new Session(++creates),
                    s -> {
                        assertFalse(s.closed);
                        return s;
                    },
                    e -> e.getMessage().equals("memory"));
        }
    }

    @Test
    void testResidentSessionsCloseOnce() throws Exception {
        var f = new Fixture();
        for (int i = 0; i < 60; i++) for (String key : List.of("coarse", "base", "decoder")) f.run(key, 3);
        assertEquals(3, f.creates);
        assertTrue(f.closed.isEmpty());
        var legacy = new Fixture();
        for (int i = 0; i < 60; i++) for (String key : List.of("coarse", "base", "decoder")) legacy.run(key, 1);
        assertEquals(180, legacy.creates);
        assertEquals(179, legacy.closed.size());
        legacy.cache.remove("decoder");
        for (String key : List.of("coarse", "base", "decoder")) {
            f.cache.remove(key);
            f.cache.remove(key);
        }
        assertEquals(List.of(1, 2, 3), f.closed);
        assertTrue(f.cache.statistics().contains("hits=177"));
    }

    @Test
    void legacyCapacityAndLruEvictionIncludingShrinkingAreSupported() throws Exception {
        var f = new Fixture();
        f.run("a", 2);
        f.run("b", 2);
        f.run("a", 2);
        f.run("c", 2);
        assertEquals(List.of(2), f.closed);
        var a = f.run("a", 1);
        assertFalse(a.closed);
        assertEquals(List.of(2, 3), f.closed);
        f.run("b", 1);
        assertTrue(a.closed);
        assertEquals(4, f.creates);
    }

    @Test
    void testCreationMemoryFailureReleasesIdleSessions() throws Exception {
        var f = new Fixture();
        f.run("a", 3);
        AtomicInteger attempts = new AtomicInteger();
        f.cache.execute(
                "b",
                3,
                () -> {
                    if (attempts.getAndIncrement() == 0) throw new Exception("memory");
                    return new Session(++f.creates);
                },
                s -> s,
                e -> e.getMessage().equals("memory"));
        assertEquals(2, attempts.get());
        assertEquals(List.of(1), f.closed);
        f.run("c", 3);
        assertEquals(List.of(1, 2), f.closed);
        assertTrue(f.cache.statistics().contains("memoryLimited=true"));
        f.cache.remove("c");
        f.run("a", 3);
        f.run("b", 3);
        assertTrue(f.cache.statistics().contains("resident=2"));
    }

    @Test
    void runMemoryFailureRecreatesCurrentSessionOnlyOnce() throws Exception {
        var f = new Fixture();
        f.run("a", 3);
        AtomicInteger runs = new AtomicInteger();
        Session result =
                f.cache.execute(
                        "b",
                        3,
                        () -> new Session(++f.creates),
                        s -> {
                            if (runs.getAndIncrement() == 0) throw new Exception("memory");
                            return s;
                        },
                        e -> true);
        assertEquals(3, result.id);
        assertEquals(List.of(1, 2), f.closed);
        assertEquals(2, runs.get());
    }

    @Test
    void testSecondFailureIsReportedWithOriginal() throws Exception {
        var f = new Fixture();
        AtomicInteger runs = new AtomicInteger();
        Exception failure =
                assertThrows(
                        Exception.class,
                        () ->
                                f.cache.execute(
                                        "a",
                                        3,
                                        () -> new Session(++f.creates),
                                        s -> {
                                            runs.incrementAndGet();
                                            throw new Exception("memory");
                                        },
                                        e -> true));
        assertEquals(2, runs.get());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of(1, 2), f.closed);
        assertTrue(f.cache.statistics().contains("resident=0"));
        var ordinary = new Fixture();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ordinary.cache.execute(
                                "a",
                                3,
                                () -> new Session(++ordinary.creates),
                                s -> {
                                    throw new IllegalArgumentException("shape");
                                },
                                e -> false));
        assertEquals(1, ordinary.creates);
        assertTrue(ordinary.closed.isEmpty());
    }

    @Test
    void evictionCannotRaceNativeRunOrClose() throws Exception {
        var f = new Fixture();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var running =
                    pool.submit(() ->
                            f.cache.execute(
                                    "a",
                                    1,
                                    () -> new Session(++f.creates),
                                    s -> {
                                        entered.countDown();
                                        assertTrue(release.await(3, TimeUnit.SECONDS));
                                        assertFalse(s.closed);
                                        return s;
                                    },
                                    e -> false));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> assertNotNull(f.cache.statistics()));
            var closingStarted = new CountDownLatch(1);
            var closing =
                    pool.submit(() -> {
                        closingStarted.countDown();
                        f.cache.remove("a");
                        return true;
                    });
            assertTrue(closingStarted.await(3, TimeUnit.SECONDS));
            assertFalse(closing.isDone());
            release.countDown();
            var session = running.get(3, TimeUnit.SECONDS);
            assertTrue(closing.get(3, TimeUnit.SECONDS));
            assertTrue(session.closed);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void testActualNativeSessionsReuse() throws Exception {
        var created = new AtomicInteger();
        var closed = new AtomicInteger();
        var cache =
                new GpuSessionCache<String, ai.onnxruntime.OrtSession>((k, s) -> {
                    s.close();
                    closed.incrementAndGet();
                });
        try (var inputs = new OnnxInputBuffers(1024)) {
            for (int i = 0; i < 12; i++)
                for (String stage : List.of("coarse", "base", "decoder")) {
                    float[] values = {i, -i, Float.intBitsToFloat(0x80000000)};
                    float[] actual =
                            cache.execute(
                                    stage,
                                    3,
                                    () -> {
                                        created.incrementAndGet();
                                        return OnnxInputBuffersTest.session();
                                    },
                                    session -> {
                                        try (var lease =
                                                        inputs.prepare(
                                                                ai.onnxruntime.OrtEnvironment.getEnvironment(),
                                                                new Object[][] {{"x", values, new long[] {3}}});
                                                var result = session.run(lease.feed())) {
                                            float[] output = new float[3];
                                            ((ai.onnxruntime.OnnxTensor) result.get(0)).getFloatBuffer().get(output);
                                            return output;
                                        }
                                    },
                                    e -> false);
                    for (int n = 0; n < 3; n++)
                        assertEquals(Float.floatToRawIntBits(values[n]), Float.floatToRawIntBits(actual[n]));
                }
        } finally {
            for (String stage : List.of("coarse", "base", "decoder")) cache.remove(stage);
        }
        assertEquals(3, created.get());
        assertEquals(3, closed.get());
    }

    @Test
    void memoryDetectionIsSpecificAndWalksCauses() {
        assertTrue(OnnxModel.isGpuMemoryExhaustion(new RuntimeException(new Exception("HRESULT 8007000E"))));
        assertTrue(OnnxModel.isGpuMemoryExhaustion(new Exception("CUDA failure 2: out of memory")));
        assertFalse(OnnxModel.isGpuMemoryExhaustion(new Exception("invalid input shape")));
        assertFalse(OnnxModel.isGpuMemoryExhaustion(new Exception("Java heap space")));
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real renderer + existing Voxy WorldSection; only the environment flag is stubbed. */
class CoarseRendererOwnershipTest {
    @TempDir Path temp;

    private void exercise(boolean realChunk) throws Exception {
        var loader = new CoarseRawAbiTest.RawLoader();
        var section = loader.loadClass("me.cortex.voxy.common.world.WorldSection");
        var renderer = loader.loadClass("com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseVoxyRenderer");
        Object source =
                section.getMethod("_createRawUntrackedUnsafeSection", int.class, int.class, int.class, int.class)
                        .invoke(null, 4, 4, 2, 0);
        section.getMethod("acquire").invoke(source);
        section.getMethod("_unsafeSetNonEmptyChildren", byte.class).invoke(source, (byte) 255);
        long[] data = (long[]) section.getMethod("_unsafeGetRawDataArray").invoke(source);
        Arrays.fill(data, 0, 1024, 99L << 27);
        var unchanged = data.clone();
        long position = section.getField("key").getLong(source);
        Object engine = new Object();
        var cache = new CoarseLodCache(32768, 64);
        cache.put(CoarseLodTest.patch(0, 0));
        cache.put(CoarseLodTest.patch(0, -1));
        var guard = new LodChunkGuard(temp);
        long[] colors = new long[13];
        for (int i = 1; i < colors.length; i++) colors[i] = (long) i << 27;
        BooleanSupplier active = () -> true;
        var provider =
                (CoarseRenderHooks.Provider)
                        renderer.getConstructor(
                                        Object.class,
                                        CoarseLodCache.class,
                                        CoarseCoverage.class,
                                        LodChunkGuard.class,
                                        BooleanSupplier.class,
                                        Map.class,
                                        int.class,
                                        int.class,
                                        CoarseLodCorrections.class)
                                .newInstance(
                                        engine,
                                        cache,
                                        new CoarseCoverage(),
                                        guard,
                                        active,
                                        Map.of((short) 229, colors),
                                        -64,
                                        1871,
                                        new CoarseLodCorrections(1024));
        renderer.getMethod("range", int.class, int.class, int.class).invoke(provider, 2048, 0, 32768);
        Object preview = null;
        try {
            assertSame(source, provider.render(engine, position, source), "Defer until background ownership check");
            assertEquals(0, guard.failures());
            assertEquals(1, guard.prepareRenderRegions(32));
            var storedField = renderer.getDeclaredField("storedColumns");
            storedField.setAccessible(true);
            Object stored = storedField.get(provider);
            var storedType = stored.getClass();
            var ready = storedType.getDeclaredMethod("ready", int.class, int.class);
            ready.setAccessible(true);
            ready.invoke(stored, 2048, 0);
            Class<?> reader =
                    loader.loadClass("com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseStoredColumns$Reader");
            Object absent =
                    java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {reader}, (p, m, a) -> null);
            var prepare =
                    storedType.getDeclaredMethod(
                            "prepare", int.class, int.class, int.class, reader, java.util.function.LongPredicate.class);
            prepare.setAccessible(true);
            prepare.invoke(stored, 1, -64, 1871, absent, (java.util.function.LongPredicate) v -> v == 0);
            var invalidate = renderer.getDeclaredMethod("invalidateStoredColumns", int.class, int.class);
            invalidate.setAccessible(true);
            invalidate.invoke(provider, 128, 0);
            assertFalse(
                    (boolean) ready.invoke(stored, 2048, 0),
                    "chunk coordinates must invalidate the corresponding block region");
            prepare.invoke(stored, 1, -64, 1871, absent, (java.util.function.LongPredicate) v -> v == 0);
            assertSame(
                    source, provider.render(engine, position, source), "Border ownership is prepared off the mesher");
            assertEquals(4, guard.prepareRenderRegions(32));
            if (realChunk) guard.markLoaded(128, 0);
            preview = provider.render(engine, position, source);
            if (realChunk) assertSame(source, preview, "fully real section renders itself: no virtual copy, no wall");
            else assertNotSame(source, preview);
            assertNotNull(preview);
            long[] output = (long[]) section.getMethod("_unsafeGetRawDataArray").invoke(preview);
            assertEquals(realChunk ? 99L << 27 : 0, output[0]);
            assertEquals(realChunk ? 99L << 27 : 0, output[1]);
            assertEquals(0, output[1024]);
            assertEquals(
                    realChunk ? 255 : 0,
                    Byte.toUnsignedInt((byte) section.getMethod("getNonEmptyChildren").invoke(preview)));
            assertEquals(realChunk ? 255 : 0, Byte.toUnsignedInt(provider.children(engine, source, (byte) 255)));
            assertArrayEquals(unchanged, data);
            assertEquals(1, section.getMethod("getRefCount").invoke(source));
            assertTrue((boolean) renderer.getMethod("active").invoke(provider));
            var queueField = renderer.getDeclaredField("waiting");
            queueField.setAccessible(true);
            var queue = (CoarseUpdateQueue) queueField.get(provider);
            for (var task : queue.select(8, k -> true, k -> true)) queue.complete(task);
            if (!realChunk) {
                var historyField = renderer.getDeclaredField("virtual");
                historyField.setAccessible(true);
                var history = (CoarseVirtualHistory) historyField.get(provider);
                for (int i = 0; i < 200000; i++) history.mark(((long) (i % 5) << 60) | ((long) i << 4));
                renderer.getMethod("exactChanged", int.class, int.class).invoke(provider, 64, 0);
                assertTrue(
                        queue.select(256, k -> true, k -> true).stream()
                                .anyMatch(task -> task.key() == position && task.forced()),
                        "Old virtual sky still receives cleanup after exceeding the previous 65536-entry LRU");
            }
        } finally {
            if (preview != null && preview != source) section.getMethod("release").invoke(preview);
            assertEquals(0, section.getMethod("release").invoke(source));
        }
    }

    @Test
    void testRendererClearsStaleSheetAndChildFlags() throws Exception {
        exercise(false);
    }

    @Test
    void testRendererPreservesLoadedChunkAndItsAir() throws Exception {
        exercise(true);
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoarseBorderReadinessTest {
    @TempDir
    Path temp;

    @Test
    void neighborReadsHaveOneSectionHaloBeyondVisibleRadiusWithoutPublishingExtraTerrain() throws Exception {
        var loader = new CoarseRawAbiTest.RawLoader();
        var section = loader.loadClass("me.cortex.voxy.common.world.WorldSection");
        var renderer = loader.loadClass("com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseVoxyRenderer");
        var cache = new CoarseLodCache(32768, 64);
        cache.put(CoarseLodTest.patch(0, 0));
        var guard = new LodChunkGuard(temp);
        guard.renderRegionReady(512, 0);
        guard.prepareRenderRegions(32);
        long[] palette = new long[13];
        for (int i = 1; i < 13; i++) palette[i] = (long) i << 27;
        Object engine = new Object();
        var provider = (CoarseRenderHooks.Provider) renderer.getConstructor(
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
                        (BooleanSupplier) () -> true,
                        Map.of((short) 229, palette),
                        -64,
                        1871,
                        new CoarseLodCorrections(1024));
        renderer.getMethod("range", int.class, int.class, int.class).invoke(provider, 256, 256, 64);
        Object source = section.getMethod(
                        "_createRawUntrackedUnsafeSection", int.class, int.class, int.class, int.class)
                .invoke(null, 4, 1, 0, 0);
        section.getMethod("acquire").invoke(source);
        long position = section.getField("key").getLong(source);
        Object output = null;
        try {
            assertSame(
                    source,
                    provider.render(engine, position, source),
                    "Primary section outside requested range stays original");
            output = provider.neighbor(engine, position, source);
            assertNotSame(source, output);
            var values = (long[]) section.getMethod("_unsafeGetRawDataArray").invoke(output);
            assertTrue(
                    Arrays.stream(values).anyMatch(v -> v != 0),
                    "Mesher sees neural ground, not empty air across the radius edge");
            assertTrue(Arrays.stream(
                            (long[]) section.getMethod("_unsafeGetRawDataArray").invoke(source))
                    .allMatch(v -> v == 0));
        } finally {
            if (output != null && output != source) section.getMethod("release").invoke(output);
            section.getMethod("release").invoke(source);
        }
    }

    @Test
    void incompletePatchEdgeDoesNotPublishASolidColumnAgainstMissingNeighbor() throws Exception {
        var loader = new CoarseRawAbiTest.RawLoader();
        var section = loader.loadClass("me.cortex.voxy.common.world.WorldSection");
        var renderer = loader.loadClass("com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseVoxyRenderer");
        var cache = new CoarseLodCache(32768, 64);
        cache.put(CoarseLodTest.patch(0, 0));
        var guard = new LodChunkGuard(temp);
        int x = 63, z = 8;
        guard.renderRegionReady(x * 512, z * 512);
        guard.prepareRenderRegions(32);
        long[] palette = new long[13];
        for (int i = 1; i < 13; i++) palette[i] = (long) i << 27;
        var provider = (CoarseRenderHooks.Provider) renderer.getConstructor(
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
                        new Object(),
                        cache,
                        new CoarseCoverage(),
                        guard,
                        (BooleanSupplier) () -> true,
                        Map.of((short) 229, palette),
                        -64,
                        1871,
                        new CoarseLodCorrections(1024));
        var engineField = renderer.getDeclaredField("engine");
        engineField.setAccessible(true);
        Object engine = engineField.get(provider);
        renderer.getMethod("range", int.class, int.class, int.class).invoke(provider, 32500, 4200, 8192);
        Object source = section.getMethod(
                        "_createRawUntrackedUnsafeSection", int.class, int.class, int.class, int.class)
                .invoke(null, 4, x, 0, z);
        section.getMethod("acquire").invoke(source);
        long position = section.getField("key").getLong(source);
        Object output = provider.render(engine, position, source);
        try {
            assertSame(source, output, "No neural column until its same-level horizontal neighbors can be meshed");
            assertFalse(
                    (boolean) renderer.getMethod("ready", long.class).invoke(provider, position),
                    "Don't churn remesh notifications before the border is ready");
        } finally {
            if (output != source) section.getMethod("release").invoke(output);
            section.getMethod("release").invoke(source);
        }
    }
}

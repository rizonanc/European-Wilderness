package com.github.xandergos.terraindiffusionmc.pipeline;

import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.GenerationMetrics;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.CoastalTerrain;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanBiomeClassifier;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.SlopeClimate;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.TropicalLandforms;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainRegionCache;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainSurfaceRegion;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.WorldHeightProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.BiomeBalance;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.GenerationProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.PreparedFallback;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainRepository;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TileAssembler;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalTerrainProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);
    private static final float NATIVE_RESOLUTION = 30.0F;
    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 0.041666668F, 3, 2.0F, 0.5F);
    private static final FastNoiseLite ELEV_NOISE_FINE = makeFnl(88888, 0.16666667F, 2, 2.0F, 0.6F);
    private static final InferenceThread INFERENCE = new InferenceThread("terrain-diffusion-inference");
    private static volatile LocalTerrainProvider INSTANCE;
    private static volatile long instanceSeed;
    private static final AtomicInteger FOREGROUND_DEMANDS = new AtomicInteger();
    private final WorldPipeline pipeline;
    private static final Object INIT_LOCK = new Object();
    private static final ExplorerPreview PREVIEW = new ExplorerPreview();
    private static volatile WorldPipeline previewPipeline;
    private static volatile long previewPipelineSeed;

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static boolean foregroundDemandActive() {
        return FOREGROUND_DEMANDS.get() > 0 || INFERENCE.foregroundPending();
    }

    public static String inferenceTimings() {
        return INFERENCE.timings();
    }

    public static InferenceThread.Snapshot inferenceSnapshot() {
        return INFERENCE.snapshot();
    }

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
    }

    public static synchronized void init(long seed) {
        init(
                seed,
                GenerationProfile.defaultsFor(
                        WorldScaleManager.getCurrentScale(),
                        ModelAssetManager.getModelRevision(),
                        TerrainDiffusionConfig.inferenceDevice(),
                        OnnxModel.getResolvedInferenceProvider()));
    }

    public static synchronized void init(long seed, GenerationProfile profile) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) {
            throw new IllegalStateException("PipelineModels failed to load");
        }

        try {
            INFERENCE
                    .submit(() -> {
                        if (INSTANCE == null) {
                            INSTANCE = new LocalTerrainProvider(seed, models);
                        } else if (instanceSeed != seed) {
                            INSTANCE.pipeline.setSeed(seed);
                        }

                        instanceSeed = seed;
                        return null;
                    })
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while binding world session", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to bind world session", e.getCause());
        }

        WorldSession.activate(profile, seed);
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) {
            return INSTANCE;
        }

        synchronized (INIT_LOCK) {
            if (INSTANCE != null) {
                return INSTANCE;
            }

            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) {
                throw new IllegalStateException("PipelineModels failed to load");
            }

            INSTANCE = new LocalTerrainProvider(0L, models);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    public static synchronized void shutdown() {
        WorldSession retiring = WorldSession.current();
        if (retiring != null) {
            LOG.info(
                    "Retiring terrain session {} on {}",
                    retiring.id(),
                    Thread.currentThread().getName());
        }

        WorldSession.deactivate(retiring);
        INFERENCE.discardStale();
        PREVIEW.clear();
        previewPipeline = null;
    }

    public static long getSeed() {
        return instanceSeed;
    }

    public static Long getPreviewSeedOrNull() {
        return PREVIEW.previewSeedOrNull();
    }

    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        return submitToInferenceThread(
                () -> resolveReadPipeline().get(i1, j1, i2, j2, withClimate),
                estimateRegionBytes(i1, j1, i2, j2, withClimate ? 20 : 4));
    }

    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(
                () -> resolveReadPipeline().getCoarseSlice(ci0, cj0, ci1, cj1),
                estimateRegionBytes(ci0, cj0, ci1, cj1, 16));
    }

    static FloatTensor getSpawnCoarse(int i0, int j0, int i1, int j1, WorldSession ticket) throws Exception {
        return INFERENCE
                .submit(InferenceDemand.current(), ticket::isActive, estimateRegionBytes(i0, j0, i1, j1, 16), () -> {
                    WorldSession.requireActive(ticket);
                    if (instanceSeed != ticket.worldSeed()) throw new IllegalStateException("Spawn seed is not bound");
                    return getInstance().pipeline.getCoarseSlice(i0, j0, i1, j1);
                })
                .get();
    }

    /** Bound to the active world's pipeline, never the explorer's preview seed. */
    public static FloatTensor prepareCoarseLodStep(
            int ci0, int cj0, int ci1, int cj1, WorldSession ticket, java.util.function.BooleanSupplier allowed)
            throws Exception {
        WorldSession.requireActive(ticket);
        try (var scope = InferenceDemand.scope(InferenceDemand.Priority.DISTANT)) {
            try {
                return INFERENCE
                        .submitOptional(
                                InferenceDemand.current(),
                                () -> ticket.isActive() && allowed.getAsBoolean(),
                                estimateRegionBytes(ci0, cj0, ci1, cj1, 16),
                                () -> {
                                    WorldSession.requireActive(ticket);
                                    if (INSTANCE == null || instanceSeed != ticket.worldSeed())
                                        throw new IllegalStateException("Coarse LOD seed is not bound");
                                    FloatTensor result = INSTANCE.pipeline.prepareCoarseLodStep(ci0, cj0, ci1, cj1);
                                    WorldSession.requireActive(ticket);
                                    return result;
                                })
                        .get();
            } catch (java.util.concurrent.CancellationException deferred) {
                return null;
            } catch (java.util.concurrent.ExecutionException failure) {
                if (failure.getCause() instanceof java.util.concurrent.CancellationException) return null;
                throw failure;
            }
        }
    }

    /** prepares one preemptible window. Returns 1 for progress, 0 when complete, or -1 when deferred. */
    public static int prepareCoarseLodWindowStep(
            int ci0, int cj0, int ci1, int cj1, WorldSession ticket, java.util.function.BooleanSupplier allowed)
            throws Exception {
        WorldSession.requireActive(ticket);
        try (var scope = InferenceDemand.scope(InferenceDemand.Priority.DISTANT)) {
            try {
                return INFERENCE
                        .submitOptional(
                                InferenceDemand.current(),
                                () -> ticket.isActive() && allowed.getAsBoolean(),
                                estimateRegionBytes(ci0, cj0, ci1, cj1, 16),
                                () -> {
                                    WorldSession.requireActive(ticket);
                                    if (INSTANCE == null || instanceSeed != ticket.worldSeed())
                                        throw new IllegalStateException("Coarse LOD seed is not bound");
                                    int status = INSTANCE.pipeline.prepareCoarseLodWindow(ci0, cj0, ci1, cj1);
                                    WorldSession.requireActive(ticket);
                                    return status;
                                })
                        .get();
            } catch (java.util.concurrent.CancellationException deferred) {
                return -1;
            } catch (java.util.concurrent.ExecutionException failure) {
                if (failure.getCause() instanceof java.util.concurrent.CancellationException) return -1;
                throw failure;
            }
        }
    }

    public static void setPreviewSeed(long seed) {
        PREVIEW.setPreviewSeed(seed);
    }

    public static long generatePreviewSeed() {
        long seed = new Random().nextLong();
        PREVIEW.setPreviewSeed(seed);
        return seed;
    }

    public static void clearPreviewSeed() {
        PREVIEW.clear();
        previewPipeline = null;
    }

    private static WorldPipeline resolveReadPipeline() {
        Long previewSeed = PREVIEW.previewSeedOrNull();
        if (previewSeed == null) {
            return getInstance().pipeline;
        }

        if (previewPipeline == null || previewPipelineSeed != previewSeed) {
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) {
                throw new IllegalStateException("PipelineModels failed to load");
            }

            previewPipeline = new WorldPipeline(previewSeed, models);
            previewPipelineSeed = previewSeed;
        }

        return previewPipeline;
    }

    private static <T> T submitToInferenceThread(Callable<T> task, long bytes) throws Exception {
        var ticket = WorldSession.current();
        var preview = PREVIEW.previewSeedOrNull();
        return INFERENCE
                .submit(
                        InferenceDemand.current(),
                        () -> WorldSession.current() == ticket
                                && (ticket == null || ticket.isActive())
                                && java.util.Objects.equals(preview, PREVIEW.previewSeedOrNull()),
                        bytes,
                        task)
                .get();
    }

    /** Estimate queued result payload, not ORT working memory; arrays are created only by the single inference owner. */
    static long estimateRegionBytes(int i1, int j1, int i2, int j2, int bytesPerCell) {
        long rows = (long) i2 - i1, columns = (long) j2 - j1;
        if (rows <= 0 || columns <= 0 || bytesPerCell < 1)
            throw new IllegalArgumentException("Invalid inference region");
        return Math.addExact(4096, Math.multiplyExact(Math.multiplyExact(rows, columns), bytesPerCell));
    }

    public LocalTerrainProvider.HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        FOREGROUND_DEMANDS.incrementAndGet();

        try {
            return this.fetchHeightmapForeground(i1, j1, i2, j2);
        } finally {
            FOREGROUND_DEMANDS.decrementAndGet();
        }
    }

    private LocalTerrainProvider.HeightmapData fetchHeightmapForeground(int i1, int j1, int i2, int j2) {
        WorldSession session = WorldSession.current();
        WorldSession.requireActive(session);
        if (session.automaticWater() != null) session.automaticWater().ensureRegion(j1, i1, j2, i2);
        TerrainRepository repository = session.repository();
        if (repository == null) {
            throw new IllegalStateException("No tile repository bound to the active world session");
        }

        try {
            long worldSeed = session.worldSeed();
            String fingerprint = session.fingerprint();
            TileAssembler.AssembledRegion region = PreparedFallback.regionFor(
                    repository, worldSeed, fingerprint, PreparedFallback.mode(), i1, j1, i2, j2);
            LocalTerrainProvider.HeightmapData data = withMountainSurface(
                    region,
                    session,
                    j1,
                    i1,
                    WorldHeightProfile.surfaceCapYForScale(session.profile().worldScale()));
            WorldSession.requireActive(session);
            return data;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Terrain region failed: (" + i1 + "," + j1 + ")-(" + i2 + "," + j2 + ")", e);
        }
    }

    static LocalTerrainProvider.HeightmapData withMountainSurface(
            TileAssembler.AssembledRegion region, WorldSession session, int startX, int startZ, int surfaceCapY)
            throws Exception {
        long seed = session.worldSeed();
        String fingerprint = session.fingerprint();
        int scale = session.profile().worldScale();
        MountainRegionCache cache = session.mountainRegions();
        MountainRegionCache.Key key = new MountainRegionCache.Key(
                seed, fingerprint, scale, startX, startZ, region.width(), region.height(), surfaceCapY);
        MountainRegionCache.SurfaceComputer transform = () -> MountainSurfaceRegion.transform(
                seed,
                startX,
                startZ,
                region.heights(),
                scale,
                surfaceCapY,
                session.profile().mountainRelief());
        MountainSurfaceRegion surface =
                region.complete() ? cache.getOrCompute(key, session, transform) : cache.computeUncached(key, transform);
        WorldSession.requireActive(session);
        return new LocalTerrainProvider.HeightmapData(
                region.heights(), region.biomes(), region.width(), region.height(), null, surface, region.complete());
    }

    public short fetchBiomeId(int blockX, int blockZ) {
        FOREGROUND_DEMANDS.incrementAndGet();

        try {
            return this.fetchBiomeIdForeground(blockX, blockZ);
        } finally {
            FOREGROUND_DEMANDS.decrementAndGet();
        }
    }

    private short fetchBiomeIdForeground(int blockX, int blockZ) {
        WorldSession session = WorldSession.current();
        WorldSession.requireActive(session);
        if (session.automaticWater() != null) session.automaticWater().ensureColumn(blockX, blockZ);
        TerrainRepository repository = session.repository();
        if (repository == null) {
            throw new IllegalStateException("No tile repository bound to the active world session");
        }

        TerrainTileKey key = TerrainTileKey.forBlock(
                blockX, blockZ, repository.tileSize(), session.worldSeed(), session.fingerprint());

        try {
            short biome = PreparedFallback.biomeFor(repository, key, blockX, blockZ, PreparedFallback.mode());
            WorldSession.requireActive(session);
            return biome;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Terrain cell failed: (" + blockX + "," + blockZ + ")", e);
        }
    }

    public static LocalTerrainProvider.HeightmapData computeRegionDirect(
            int i1, int j1, int i2, int j2, WorldSession ticket) throws Exception {
        WorldSession.requireActive(ticket);
        var requestContext =
                DiagnosticErrors.context("TERRAIN_REGION", "blocks=(" + j1 + "," + i1 + ")..(" + j2 + "," + i2 + ")");
        try (var diagnostic = DiagnosticErrors.scope(requestContext)) {
            return INFERENCE
                    .<LocalTerrainProvider.HeightmapData>submit(
                            InferenceDemand.current(),
                            ticket::isActive,
                            estimateRegionBytes(i1, j1, i2, j2, 24),
                            () -> {
                                WorldSession.requireActive(ticket);
                                LocalTerrainProvider provider = getInstance();
                                int scale = WorldScaleManager.getCurrentScale();
                                long computedWindowCountBefore = provider.pipeline.getTotalComputedWindowCount();
                                GenerationProfile profile = ticket.profile();
                                LocalTerrainProvider.HeightmapData data = scale <= 1
                                        ? provider.handle1x(i1, j1, i2, j2, profile)
                                        : provider.handleUpsampled(i1, j1, i2, j2, scale, profile);
                                long computedWindowCountAfter = provider.pipeline.getTotalComputedWindowCount();
                                int regionWidth = j2 - j1;
                                int regionHeight = i2 - i1;
                                if (LOG.isDebugEnabled())
                                    LOG.debug(
                                            "European Wilderness ({}) finished generating region {}x{} ({} newly computed windows)",
                                            new Object[] {
                                                OnnxModel.getResolvedInferenceProvider(),
                                                regionWidth,
                                                regionHeight,
                                                computedWindowCountAfter - computedWindowCountBefore
                                            });
                                GenerationMetrics.countInferenceRegion();
                                WorldSession.requireActive(ticket);
                                return data;
                            })
                    .get();
        } catch (Exception failure) {
            try (var diagnostic = DiagnosticErrors.scope(requestContext)) {
                failed(Code.WGN_REQUEST, "TERRAIN_REGION", failure);
            }
            throw failure;
        }
    }

    public static long totalComputedWindows() {
        LocalTerrainProvider provider = INSTANCE;
        return provider == null ? 0L : provider.pipeline.getTotalComputedWindowCount();
    }

    private LocalTerrainProvider.HeightmapData handle1x(int i1, int j1, int i2, int j2, GenerationProfile profile) {
        int H = i2 - i1;
        int W = j2 - j1;
        float[] elevPadded = this.pipeline.get(i1 - 1, j1 - 1, i2 + 1, j2 + 1, false)[0];
        float[][] out = this.pipeline.get(i1, j1, i2, j2, true);
        float[] elevFlat = out[0];
        float[] climate = java.util.Arrays.copyOf(out[1], 4 * H * W);
        clampPrecipitation(climate, H * W);
        adjustElevation(elevFlat, profile, j1, i1, W);
        adjustElevation(elevPadded, profile, j1 - 1, i1 - 1, W + 2);
        int shift = profile.climateShift().orElse(0);
        if (shift != 0) for (int i = 0; i < H * W; i++) climate[i] += shift;
        if (profile.slopeClimate()) applySlopeClimate(climate, elevPadded, W, H, 30F / profile.worldScale());
        short[] biomeFlat = BiomeClassifier.classify(elevFlat, climate, i1, j1, elevPadded, H, W, 30.0F);
        biomeFlat = EuropeanBiomeClassifier.classify(
                instanceSeed,
                elevFlat,
                climate,
                biomeFlat,
                elevPadded,
                H,
                W,
                30.0F,
                j1,
                i1,
                profile.largerLandscapes(),
                profile.biomeBalance());
        if (profile.climateShift().isPresent()) {
            for (int i = 0; i < H * W; i++)
                biomeFlat[i] = CoastalTerrain.biome(
                        instanceSeed, j1 + i % W, i1 + i / W, elevFlat[i], profile.worldScale(), biomeFlat[i]);
        }
        applyIslands(profile, j1, i1, W, H, elevFlat, climate, biomeFlat);
        return buildHeightmapData(elevFlat, biomeFlat, H, W, climate);
    }

    private LocalTerrainProvider.HeightmapData handleUpsampled(
            int i1, int j1, int i2, int j2, int scale, GenerationProfile profile) {
        int H = i2 - i1;
        int W = j2 - j1;
        float pixelSizeM = 30.0F / scale;
        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);
        int i1p = i1n - 2;
        int j1p = j1n - 2;
        int i2p = i2n + 2;
        int j2p = j2n + 2;
        int nH = i2p - i1p;
        int nW = j2p - j1p;
        float[][] out = this.pipeline.get(i1p, j1p, i2p, j2p, true);
        float[] elevNativeFlat = out[0];
        float[] climateNativeFlat = out[1];
        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);

        int padUp = 2 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1 = padUp + offsetI;
        int cropJ1 = padUp + offsetJ;
        boolean roi = !Boolean.getBoolean("terrain.diffusion.roi.disable");
        float[] elevSmooth, elevPadded;
        if (roi) {
            elevPadded = LaplacianUtils.bilinearResizeRoi(
                    elevNative2D, nH * scale, nW * scale, cropI1 - 1, cropJ1 - 1, H + 2, W + 2);
            elevSmooth = new float[H * W];
            for (int r = 0; r < H; r++) System.arraycopy(elevPadded, (r + 1) * (W + 2) + 1, elevSmooth, r * W, W);
        } else {
            float[][] elevUp = LaplacianUtils.bilinearResize(elevNative2D, nH * scale, nW * scale);
            elevSmooth = cropFlat(elevUp, cropI1, cropJ1, H, W, nH * scale, nW * scale);
            elevPadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, H + 2, W + 2, nH * scale, nW * scale);
        }
        float[] climate =
                upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, scale, nH * scale, nW * scale);
        clampPrecipitation(climate, H * W);
        adjustElevation(elevSmooth, profile, j1, i1, W);
        adjustElevation(elevPadded, profile, j1 - 1, i1 - 1, W + 2);
        int shift = profile.climateShift().orElse(0);
        if (shift != 0) for (int i = 0; i < H * W; i++) climate[i] += shift;
        if (profile.slopeClimate()) applySlopeClimate(climate, elevPadded, W, H, 30F / profile.worldScale());
        float[] elevOut = this.addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);
        short[] biomeFlat = BiomeClassifier.classify(elevSmooth, climate, i1, j1, elevPadded, H, W, pixelSizeM);
        biomeFlat = EuropeanBiomeClassifier.classify(
                instanceSeed,
                elevSmooth,
                climate,
                biomeFlat,
                elevPadded,
                H,
                W,
                pixelSizeM,
                j1,
                i1,
                profile.largerLandscapes(),
                profile.biomeBalance());
        if (profile.climateShift().isPresent()) {
            for (int i = 0; i < H * W; i++)
                biomeFlat[i] = CoastalTerrain.biome(
                        instanceSeed, j1 + i % W, i1 + i / W, elevOut[i], profile.worldScale(), biomeFlat[i]);
        }
        applyIslands(profile, j1, i1, W, H, elevOut, climate, biomeFlat);
        return buildHeightmapData(elevOut, biomeFlat, H, W, climate);
    }

    private static void applyIslands(
            GenerationProfile profile,
            int x,
            int z,
            int width,
            int height,
            float[] elevations,
            float[] climate,
            short[] biomes) {
        if (!profile.tropicalLandforms()) return;
        int cell = TropicalLandforms.ISLAND_CELL;
        for (int cz = Math.floorDiv(z, cell); cz <= Math.floorDiv(z + height - 1, cell); cz++) {
            for (int cx = Math.floorDiv(x, cell); cx <= Math.floorDiv(x + width - 1, cell); cx++) {
                var group = TropicalLandforms.islands(instanceSeed, cx, cz, profile.largeIslands());
                if (group.isEmpty()) continue;
                for (int wz = Math.max(z, cz * cell); wz < Math.min(z + height, (cz + 1) * cell); wz++) {
                    for (int wx = Math.max(x, cx * cell); wx < Math.min(x + width, (cx + 1) * cell); wx++) {
                        int index = (wz - z) * width + wx - x;
                        if (elevations[index] >= 0) continue;
                        var island = TropicalLandforms.islandColumn(
                                group, elevations[index], wx, wz, climate[index], profile.worldScale());
                        if (island == null) continue;
                        elevations[index] = island.height();
                        biomes[index] = island.biome();
                    }
                }
            }
        }
    }

    private static void applySlopeClimate(float[] climate, float[] elevation, int width, int height, float step) {
        int stride = width + 2;
        int rain = 2 * width * height;
        for (int z = 0; z < height; z++)
            for (int x = 0; x < width; x++) {
                int here = (z + 1) * stride + x + 1;
                if (elevation[here] < 200) continue;
                float exposure = SlopeClimate.exposure(
                        elevation[here - stride],
                        elevation[here + stride],
                        elevation[here + 1],
                        elevation[here - 1],
                        step);
                int cell = z * width + x;
                climate[cell] += SlopeClimate.temperature(exposure);
                climate[rain + cell] *= SlopeClimate.precipitation(exposure);
            }
    }

    static void clampPrecipitation(float[] climate, int cells) {
        // Clamp after resampling so valid precipitation values retain their original interpolation.
        for (int i = 2 * cells; i < 3 * cells; i++) {
            if (climate[i] < 0 && Float.isFinite(climate[i])) climate[i] = 0;
        }
    }

    private static void adjustElevation(float[] heights, GenerationProfile profile, int x, int z, int width) {
        BiomeBalance balance = profile.biomeBalance();
        boolean regional = profile.climateShift().isPresent();
        if (!regional && !profile.newLandforms() && balance.mountains() == 100 && balance.oceans() == 100) return;
        for (int i = 0; i < heights.length; i++) {
            float height = balance.adjustElevation(heights[i]);
            int wx = x + i % width, wz = z + i / width;
            if (regional) height = CoastalTerrain.height(instanceSeed, wx, wz, height);
            heights[i] = profile.newLandforms()
                    ? CoastalTerrain.fjordsAndSkerries(instanceSeed, wx, wz, height, profile.worldScale())
                    : height;
        }
    }

    private float[] addElevationNoise(
            float[] elevSmooth, float[] elevPadded, int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = (float[]) elevSmooth.clone();
        float normFactor = 40.0F * pixelSizeM / 30.0F;
        float ampC = 100.0F * pixelSizeM / 30.0F;
        float ampF = 70.0F * pixelSizeM / 30.0F;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (!(e < 0.0F)) {
                    float grad = slopeGradient[idx];
                    float sf = Math.min(1.0F, grad / normFactor);
                    sf = sf * sf * (float) Math.sqrt(sf);
                    float nx = j1 + c;
                    float ny = i1 + r;
                    elevOut[idx] = e
                            + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                            + ELEV_NOISE_FINE.GetNoise(nx, ny) * ampF * sf;
                }
            }
        }

        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        float[] SOBEL_X = new float[] {-1.0F, 0.0F, 1.0F, -2.0F, 0.0F, 2.0F, -1.0F, 0.0F, 1.0F};
        float[] SOBEL_Y = new float[] {-1.0F, -2.0F, -1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 2.0F, 1.0F};
        float[] result = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0.0F;
                float dy = 0.0F;

                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k / 3) * pW + c + k % 3];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }

                dx /= 8.0F;
                dy /= 8.0F;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }

        return result;
    }

    static float[] upsampleClimate(
            float[] climNative, int nH, int nW, int cropI1, int cropJ1, int H, int W, int scale, int upH, int upW) {
        if (climNative == null) {
            return null;
        }

        float[] result = new float[4 * H * W];

        for (int ch = 0; ch < 4; ch++) {
            float[][] chNative = new float[nH][nW];

            for (int r = 0; r < nH; r++) {
                System.arraycopy(climNative, ch * nH * nW + r * nW, chNative[r], 0, nW);
            }

            if (!Boolean.getBoolean("terrain.diffusion.roi.disable")) {
                LaplacianUtils.bilinearResizeRoiInto(chNative, upH, upW, cropI1, cropJ1, H, W, result, ch * H * W);
            } else {
                float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
                for (int r = 0; r < H; r++)
                    for (int c = 0; c < W; c++) result[ch * H * W + r * W + c] = chUp[cropI1 + r][cropJ1 + c];
            }
        }

        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];

        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));

            for (int c = 0; c < W; c++) {
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
            }
        }

        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];

        for (int r = 0; r < H; r++) {
            System.arraycopy(flat, r * W, a[r], 0, W);
        }

        return a;
    }

    private static LocalTerrainProvider.HeightmapData buildHeightmapData(
            float[] elevFlat, short[] biomeFlat, int H, int W, float[] climate) {
        long count = (long) H * W;
        if (H <= 0
                || W <= 0
                || elevFlat == null
                || biomeFlat == null
                || count != elevFlat.length
                || count != biomeFlat.length
                || (climate != null && climate.length != 4 * count)) {
            var failure = new IllegalArgumentException("Invalid heightmap dimensions or channel lengths");
            failed(Code.WGN_INVARIANT, "HEIGHTMAP_ASSEMBLY", failure);
            throw failure;
        }
        short[][] heightmap = new short[H][W];
        short[][] biomeIds = new short[H][W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float e = elevFlat[r * W + c];
                PipelineChecks.finite(e, "HEIGHTMAP_QUANTIZATION");
                heightmap[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIds[r][c] = biomeFlat[r * W + c];
            }
        }

        float[] climateCopy = null;
        if (climate != null && climate.length == 4 * H * W) {
            climateCopy = (float[]) climate.clone();
        }

        return new LocalTerrainProvider.HeightmapData(heightmap, biomeIds, W, H, climateCopy);
    }

    public static final class HeightmapData {
        public final short[][] heightmap;
        public final short[][] biomeIds;
        public final int width;
        public final int height;
        public final float[] climate;
        private final MountainSurfaceRegion surface;
        private final boolean complete;

        public HeightmapData(short[][] heightmap, short[][] biomeIds, int width, int height) {
            this(heightmap, biomeIds, width, height, null, (MountainSurfaceRegion) null, true);
        }

        public HeightmapData(short[][] heightmap, short[][] biomeIds, int width, int height, float[] climate) {
            this(heightmap, biomeIds, width, height, climate, (MountainSurfaceRegion) null, true);
        }

        public HeightmapData(
                short[][] heightmap, short[][] biomeIds, int width, int height, float[] climate, int[][] surfaceY) {
            this(
                    heightmap,
                    biomeIds,
                    width,
                    height,
                    climate,
                    surfaceY == null ? null : MountainSurfaceRegion.copyOf(surfaceY, width, height),
                    true);
        }

        private HeightmapData(
                short[][] heightmap,
                short[][] biomeIds,
                int width,
                int height,
                float[] climate,
                MountainSurfaceRegion surface,
                boolean complete) {
            this.heightmap = heightmap;
            this.biomeIds = biomeIds;
            this.width = width;
            this.height = height;
            this.climate = climate;
            this.surface = surface;
            this.complete = complete;
        }

        public boolean complete() {
            return this.complete;
        }

        public boolean hasSurfaceY() {
            return this.surface != null;
        }

        public int surfaceYAt(int localX, int localZ, int scale) {
            return this.surface != null
                    ? this.surface.surfaceYAt(localX, localZ)
                    : HeightConverter.convertToMinecraftHeight(this.heightmap[localZ][localX], scale);
        }
    }
}

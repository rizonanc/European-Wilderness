package com.github.xandergos.terraindiffusionmc;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.explorer.ExplorerServer;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.ModelAssetManager;
import com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel;
import com.github.xandergos.terraindiffusionmc.pipeline.PipelineModels;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.WorldSessionBinding;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.GenerationMetrics;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.PreparedWaterCommands;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.PreparedWaterController;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.RiverWaterFeature;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.WorldWaterController;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainSurfaceFeature;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.PassCampDecisionStore;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.WorldHeightProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.preparation.PreparationProgress;
import com.github.xandergos.terraindiffusionmc.worldgen.preparation.PreparationService;
import com.github.xandergos.terraindiffusionmc.worldgen.preparation.TilePrefetchSampler;
import com.github.xandergos.terraindiffusionmc.worldgen.preparation.TilePrefetchService;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.GenerationProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.GenerationProfileState;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.PerformanceProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.ProfileCompatibility;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSessionLifetime;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainRepository;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.UpstreamTerrainAdapter;
import com.github.xandergos.terraindiffusionmc.worldgen.tree.EuropeanTreeFeature;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopped;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.StartTick;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.Load;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent.OpenUrl;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.GenerationStep.Feature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TerrainDiffusionMc implements ModInitializer {
    public static final String MOD_ID = "terrain-diffusion-mc";
    private static final Logger LOG = LoggerFactory.getLogger(TerrainDiffusionMc.class);
    private static final WorldSessionLifetime SESSION_LIFETIME = new WorldSessionLifetime();
    private static volatile TerrainDiffusionMc.PrefetchRuntime PREFETCH;

    public void onInitialize() {
        LOG.info("Initializing European Wilderness");
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(
                        com.github.xandergos.terraindiffusionmc.worldgen.hydrology.BoatCurrentPayload.ID,
                        com.github.xandergos.terraindiffusionmc.worldgen.hydrology.BoatCurrentPayload.CODEC);
        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics.install(
                net.fabricmc.loader.api.FabricLoader.getInstance()
                        .getGameDir()
                        .resolve("logs/european-wilderness-performance.jsonl"));
        var diagnosticVersions = new java.util.LinkedHashMap<String, String>();
        for (String id : new String[] {
            "terrain-diffusion-mc",
            "minecraft",
            "fabricloader",
            "fabric-api",
            "c2me",
            "lithium",
            "voxy",
            "voxyworldgenv2",
            "chunky"
        })
            net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer(id)
                    .ifPresent(mod -> diagnosticVersions.put(
                            id, mod.getMetadata().getVersion().getFriendlyString()));
        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.environment(
                diagnosticVersions,
                () -> java.util.Map.of(
                        "inference",
                        LocalTerrainProvider.inferenceSnapshot(),
                        "onnx",
                        OnnxModel.diagnosticCounters(),
                        "generation",
                        GenerationMetrics.snapshot(),
                        "errors",
                        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.statistics()));
        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.health(() -> {
            var snapshot = LocalTerrainProvider.inferenceSnapshot();
            if (snapshot.oldestForegroundWaitNanos() > java.util.concurrent.TimeUnit.SECONDS.toNanos(30))
                com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.emit(
                        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.Code.SCH_STALL,
                        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.current(
                                "FOREGROUND_WAIT"),
                        null,
                        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.Outcome.DEGRADED,
                        "Required requests retained; inspect queue ages/provider timing; cause not established",
                        false);
            var active = WorldSession.current();
            if (active != null
                    && active.automaticWater() != null
                    && active.automaticWater().diagnosticHoldNanos()
                            > java.util.concurrent.TimeUnit.SECONDS.toNanos(60))
                com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.emit(
                        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.Code.SCH_STALL,
                        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.current(
                                "HYDROLOGY_HOLD"),
                        null,
                        com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.Outcome.DEGRADED,
                        "One materialized water unit held over 60s; no speculative parallel planning added",
                        false);
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents.CHUNK_GENERATE.register((world, chunk) -> {
            if (WorldSessionBinding.ownsSession(world.getRegistryKey())
                    && world.getChunkManager().getChunkGenerator().getBiomeSource()
                            instanceof TerrainDiffusionBiomeSource) GenerationMetrics.countFullChunk();
        });
        com.github.xandergos.terraindiffusionmc.worldgen.hydrology.RiverBlocks.register();
        Registry.register(
                Registries.BIOME_SOURCE,
                Identifier.of("terrain-diffusion-mc", "terrain_diffusion"),
                TerrainDiffusionBiomeSource.CODEC);
        Registry.register(
                Registries.DENSITY_FUNCTION_TYPE,
                Identifier.of("terrain-diffusion-mc", "terrain_diffusion"),
                TerrainDiffusionDensityFunction.CODEC);
        Registry.register(
                Registries.FEATURE, Identifier.of("terrain-diffusion-mc", "european_tree"), new EuropeanTreeFeature());
        Registry.register(
                Registries.FEATURE,
                Identifier.of("terrain-diffusion-mc", "mountain_surface"),
                new MountainSurfaceFeature());
        Registry.register(
                Registries.FEATURE, Identifier.of("terrain-diffusion-mc", "river_water"), new RiverWaterFeature());
        Registry.register(
                Registries.FEATURE,
                Identifier.of("terrain-diffusion-mc", "river_finish"),
                new com.github.xandergos.terraindiffusionmc.worldgen.hydrology.RiverFinishFeature());
        BiomeModifications.addFeature(
                BiomeSelectors.all(),
                Feature.LAKES,
                RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("terrain-diffusion-mc", "river_water")));
        BiomeModifications.addFeature(
                BiomeSelectors.all(),
                Feature.VEGETAL_DECORATION,
                RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("terrain-diffusion-mc", "european_trees")));
        BiomeModifications.addFeature(
                BiomeSelectors.all(),
                Feature.TOP_LAYER_MODIFICATION,
                RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("terrain-diffusion-mc", "mountain_surface")));
        BiomeModifications.addFeature(
                BiomeSelectors.all(),
                Feature.TOP_LAYER_MODIFICATION,
                RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("terrain-diffusion-mc", "river_finish")));
        var tropical = Identifier.of("terrain-diffusion-mc", "tropical_decoration");
        Registry.register(
                Registries.FEATURE,
                tropical,
                new com.github.xandergos.terraindiffusionmc.worldgen.ecology.TropicalDecorationFeature());
        BiomeModifications.addFeature(
                BiomeSelectors.all(),
                Feature.TOP_LAYER_MODIFICATION,
                RegistryKey.of(RegistryKeys.PLACED_FEATURE, tropical));
        com.github.xandergos.terraindiffusionmc.compat.decoration.OptionalBiomeDecorations.register();
        com.github.xandergos.terraindiffusionmc.compat.decoration.AlexMobSpawns.register();
        ModelAssetManager.ensureAssetsReady();
        PipelineModels.load();
        ServerWorldEvents.LOAD.register((Load) (server, world) -> {
            if (WorldSessionBinding.ownsSession(world.getRegistryKey())) {
                stopPrefetch();
                WorldScaleManager.initializeForWorld(world);
                GenerationProfileState savedProfile =
                        world.getPersistentStateManager().getOrCreate(GenerationProfileState.TYPE);
                GenerationProfile profile = savedProfile.toProfile();
                ProfileCompatibility.checkCompatibleOrThrow(
                        profile,
                        ModelAssetManager.getModelRevision(),
                        TerrainDiffusionConfig.inferenceDevice(),
                        OnnxModel.getResolvedInferenceProvider());
                LOG.info("European Wilderness world session: {}", profile.fingerprint());
                LocalTerrainProvider.init(world.getSeed(), profile);
                SESSION_LIFETIME.bind(server);
                Path storeDir = server.getSavePath(WorldSavePath.ROOT).resolve("terrain-data");
                int tileSize = TerrainDiffusionConfig.tileSize();
                long diskQuotaBytes = TerrainDiffusionConfig.diskQuotaMb() * 1024L * 1024L;
                WorldSession owningTicket = WorldSession.current();
                owningTicket.diagnosticDimension(
                        world.getRegistryKey().getValue().toString());
                WorldSession.requireActive(owningTicket);
                TerrainRepository repository = new TerrainRepository(
                        new DiskTerrainStore(storeDir),
                        key -> {
                            WorldSession.requireActive(owningTicket);
                            LocalTerrainProvider.HeightmapData data = LocalTerrainProvider.computeRegionDirect(
                                    key.blockStartZ(),
                                    key.blockStartX(),
                                    key.blockStartZ() + tileSize,
                                    key.blockStartX() + tileSize,
                                    owningTicket);
                            return UpstreamTerrainAdapter.toTile(
                                    key, data, owningTicket.profile().biomeVariety());
                        },
                        tileSize,
                        67108864L,
                        diskQuotaBytes,
                        owningTicket);
                WorldSession session = owningTicket;
                if (session != null) {
                    session.attachRepository(repository);
                    session.attachPassCampDecisions(
                            new PassCampDecisionStore(storeDir.resolve("pass-camp-decisions-v8")));
                    try {
                        if (savedProfile.automaticWater()) {
                            session.attachAutomaticWater(new WorldWaterController(
                                    session,
                                    storeDir.resolve("world-water-v1"),
                                    WorldHeightProfile.surfaceCapYForScale(profile.worldScale())));
                            LOG.info("Automatic rivers, lakes and waterfalls enabled for this world");
                        } else {
                            session.attachPreparedWater(new PreparedWaterController(
                                    session,
                                    storeDir.resolve("prepared-water-v1"),
                                    WorldHeightProfile.surfaceCapYForScale(profile.worldScale())));
                        }
                    } catch (java.io.IOException invalidWater) {
                        throw new IllegalStateException(
                                "Cannot load prepared water; saved areas were left unchanged", invalidWater);
                    }
                    boolean waterAhead =
                            session.automaticWater() != null && TerrainDiffusionConfig.waterPrefetchEnabled();
                    if ((waterAhead
                                    || TerrainDiffusionConfig.prefetchMode()
                                            == TerrainDiffusionConfig.PrefetchMode.ADAPTIVE)
                            && TerrainDiffusionConfig.preparationMode() == PerformanceProfile.PreparationMode.ON_DEMAND
                            && world.getChunkManager().getChunkGenerator().getBiomeSource()
                                    instanceof TerrainDiffusionBiomeSource) {
                        java.util.function.BooleanSupplier busy = () -> LocalTerrainProvider.foregroundDemandActive()
                                || !session.backgroundAllowed()
                                || session.automaticWater() != null
                                        && session.automaticWater().foregroundBusy();
                        java.util.function.BooleanSupplier manual = () -> session.preparation() != null
                                && session.preparation().progress().state() == PreparationProgress.State.RUNNING;
                        TilePrefetchService prefetch = waterAhead
                                ? new TilePrefetchService(session.automaticWater(), session, busy, manual)
                                : new TilePrefetchService(repository, session, busy, manual);
                        TilePrefetchSampler sampler = new TilePrefetchSampler(
                                session.worldSeed(),
                                session.fingerprint(),
                                waterAhead ? session.automaticWater().coreSize() : repository.tileSize(),
                                (keys, healthy) -> {
                                    if (waterAhead) session.automaticWater().prioritizeAhead(keys);
                                    prefetch.updateCandidates(keys, healthy);
                                },
                                waterAhead);
                        PREFETCH = new TerrainDiffusionMc.PrefetchRuntime(session, prefetch, sampler);
                        LOG.info(
                                "Adaptive {} prefetch enabled for session {}",
                                waterAhead ? "terrain and water" : "raw terrain",
                                session.id());
                    }
                }

                LOG.info("Terrain tile store: {} (tile {}, ram budget {} MB, disk quota {} MB, mode {})", new Object[] {
                    storeDir,
                    tileSize,
                    64L,
                    TerrainDiffusionConfig.diskQuotaMb(),
                    TerrainDiffusionConfig.preparationMode()
                });
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register((ServerStopped) server -> {
            if (SESSION_LIFETIME.release(server)) {
                shutdownSessionWork();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping) server -> {
            ExplorerServer.stop();
            if (SESSION_LIFETIME.owns(server)) {
                stopPrefetch();
                WorldSession session = WorldSession.current();
                if (session != null && session.preparation() != null) {
                    session.preparation().cancel();
                }
                if (session != null && session.preparedWater() != null)
                    session.preparedWater().close();
                if (session != null && session.automaticWater() != null)
                    session.automaticWater().close();
            }
        });
        ServerTickEvents.START_SERVER_TICK.register((StartTick) server -> {
            TerrainDiffusionMc.PrefetchRuntime runtime = PREFETCH;
            if (runtime != null && SESSION_LIFETIME.owns(server)) {
                runtime.tickStartNanos = System.nanoTime();
            }
        });
        ServerTickEvents.END_SERVER_TICK.register((EndTick) server -> {
            TerrainDiffusionMc.PrefetchRuntime runtime = PREFETCH;
            if (runtime != null && SESSION_LIFETIME.owns(server) && WorldSession.current() == runtime.ticket) {
                Integer playerX = null;
                Integer playerZ = null;

                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    if (WorldSessionBinding.ownsSession(player.getEntityWorld().getRegistryKey())) {
                        playerX = player.getBlockPos().getX();
                        playerZ = player.getBlockPos().getZ();
                        break;
                    }
                }

                long elapsedMs =
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - runtime.tickStartNanos));
                runtime.sampler.tick(elapsedMs, playerX, playerZ, runtime.ticket.isActive());
                runtime.service.signalStateChanged();
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            PreparedWaterCommands.register(dispatcher);
            dispatcher.register(CommandManager.literal("td-explore").executes(TerrainDiffusionMc::executeExplore));
            dispatcher.register(CommandManager.literal("td-prepare")
                    .then(prepareStartCommand())
                    .then(prepareProbeCommand())
                    .then(prepareStatusCommand())
                    .then(prepareCancelCommand()));
            dispatcher.register(CommandManager.literal("td-stats").executes(TerrainDiffusionMc::executeStats));
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> prepareStartCommand() {
        return CommandManager.literal("start")
                .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 15))
                        .executes(TerrainDiffusionMc::executePrepareStart));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> prepareProbeCommand() {
        return CommandManager.literal("probe")
                .then(CommandManager.argument("tileX", IntegerArgumentType.integer())
                        .then(CommandManager.argument("tileZ", IntegerArgumentType.integer())
                                .executes(TerrainDiffusionMc::executePrepareProbe)));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> prepareStatusCommand() {
        return CommandManager.literal("status").executes(TerrainDiffusionMc::executePrepareStatus);
    }

    private static LiteralArgumentBuilder<ServerCommandSource> prepareCancelCommand() {
        return CommandManager.literal("cancel").executes(TerrainDiffusionMc::executePrepareCancel);
    }

    private static void stopPrefetch() {
        TerrainDiffusionMc.PrefetchRuntime retiring = PREFETCH;
        PREFETCH = null;
        if (retiring != null) {
            retiring.stop();
        }
    }

    private static void shutdownSessionWork() {
        stopPrefetch();
        WorldSession session = WorldSession.current();
        if (session != null && session.preparation() != null) {
            session.preparation().shutdown();
        }
        if (session != null && session.preparedWater() != null)
            session.preparedWater().close();
        if (session != null && session.automaticWater() != null)
            session.automaticWater().close();

        LocalTerrainProvider.shutdown();
    }

    private static int executeExplore(CommandContext<ServerCommandSource> ctx) {
        try {
            int port = ExplorerServer.startIfNotRunning();
            String url = "http://localhost:" + port;
            MutableText link = Text.literal(url)
                    .styled(s -> s.withClickEvent(new OpenUrl(URI.create(url))).withUnderline(true));
            ((ServerCommandSource) ctx.getSource())
                    .sendFeedback(() -> Text.literal("Terrain Explorer: ").append(link), false);
        } catch (Exception e) {
            LOG.error("Failed to start terrain explorer", e);
            ((ServerCommandSource) ctx.getSource())
                    .sendError(Text.literal("Failed to start terrain explorer: " + e.getMessage()));
        }

        return 1;
    }

    private static int executePrepareStart(CommandContext<ServerCommandSource> ctx) {
        try {
            ServerWorld world = ((ServerCommandSource) ctx.getSource()).getWorld();
            if (!WorldSessionBinding.ownsSession(world.getRegistryKey())) {
                ((ServerCommandSource) ctx.getSource())
                        .sendError(Text.literal("td-prepare works on the overworld only"));
                return 0;
            }

            WorldSession session = WorldSession.current();
            if (session != null && session.repository() != null) {
                if (session.preparation() != null
                        && !session.preparation().progress().isFinished()) {
                    ((ServerCommandSource) ctx.getSource())
                            .sendError(Text.literal("A preparation job is already running (see /td-prepare status)"));
                    return 0;
                }

                int radius = IntegerArgumentType.getInteger(ctx, "radius");
                BlockPos spawn = world.getSpawnPoint().getPos();
                TerrainTileKey center = TerrainTileKey.forBlock(
                        spawn.getX(),
                        spawn.getZ(),
                        session.repository().tileSize(),
                        world.getSeed(),
                        session.fingerprint());
                List<TerrainTileKey> keys = new ArrayList<>();

                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        keys.add(TerrainTileKey.ofTile(
                                world.getSeed(),
                                center.tileX() + dx,
                                center.tileZ() + dz,
                                session.repository().tileSize(),
                                session.fingerprint()));
                    }
                }

                PreparationService service = new PreparationService(session.repository(), session);
                session.attachPreparation(service);
                service.start(keys);
                ((ServerCommandSource) ctx.getSource())
                        .sendFeedback(
                                () -> Text.literal("Terrain preparation started: "
                                        + keys.size()
                                        + " tiles around spawn ("
                                        + spawn.getX()
                                        + ", "
                                        + spawn.getZ()
                                        + ")"),
                                false);
                return 1;
            } else {
                ((ServerCommandSource) ctx.getSource()).sendError(Text.literal("No active terrain session"));
                return 0;
            }
        } catch (Exception e) {
            LOG.error("Failed to start terrain preparation", e);
            ((ServerCommandSource) ctx.getSource())
                    .sendError(Text.literal("Failed to start preparation: " + e.getMessage()));
            return 0;
        }
    }

    private static int executePrepareStatus(CommandContext<ServerCommandSource> ctx) {
        WorldSession session = WorldSession.current();
        if (session != null && session.preparation() != null) {
            String status = session.preparation().progress().toString();
            ((ServerCommandSource) ctx.getSource()).sendFeedback(() -> Text.literal(status), false);
            return 1;
        } else {
            ((ServerCommandSource) ctx.getSource()).sendFeedback(() -> Text.literal("No preparation job"), false);
            return 1;
        }
    }

    private static int executePrepareCancel(CommandContext<ServerCommandSource> ctx) {
        WorldSession session = WorldSession.current();
        if (session != null && session.preparation() != null) {
            session.preparation().cancel();
            ((ServerCommandSource) ctx.getSource())
                    .sendFeedback(() -> Text.literal("Preparation cancelling (stops before the next tile)"), false);
            return 1;
        } else {
            ((ServerCommandSource) ctx.getSource()).sendFeedback(() -> Text.literal("No preparation job"), false);
            return 1;
        }
    }

    private static int executePrepareProbe(CommandContext<ServerCommandSource> ctx) {
        try {
            WorldSession session = WorldSession.current();
            if (session != null && session.repository() != null) {
                ServerWorld world = ((ServerCommandSource) ctx.getSource()).getWorld();
                int tileX = IntegerArgumentType.getInteger(ctx, "tileX");
                int tileZ = IntegerArgumentType.getInteger(ctx, "tileZ");
                TerrainTileKey key = TerrainTileKey.ofTile(
                        world.getSeed(), tileX, tileZ, session.repository().tileSize(), session.fingerprint());
                long windowsBefore = LocalTerrainProvider.totalComputedWindows();
                TerrainTile tile = session.repository().get(key);
                long windowsAfter = LocalTerrainProvider.totalComputedWindows();
                short[] heights = tile.heightsMeters();
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                long sum = 0L;
                Map<Short, Integer> biomeCounts = new TreeMap<>();

                for (short h : heights) {
                    if (h < min) {
                        min = h;
                    }

                    if (h > max) {
                        max = h;
                    }

                    sum += h;
                }

                for (short b : tile.biomeIds()) {
                    biomeCounts.merge(b, 1, Integer::sum);
                }

                double mean = heights.length == 0 ? 0.0 : (double) sum / heights.length;
                String report = "Tile "
                        + key
                        + ": hMin="
                        + min
                        + " hMax="
                        + max
                        + " hMean="
                        + String.format("%.1f", mean)
                        + " biomes="
                        + biomeCounts
                        + " pipelineWindowsDelta="
                        + (windowsAfter - windowsBefore)
                        + " ("
                        + GenerationMetrics.snapshot()
                        + ")";
                ((ServerCommandSource) ctx.getSource()).sendFeedback(() -> Text.literal(report), false);
                return 1;
            } else {
                ((ServerCommandSource) ctx.getSource()).sendError(Text.literal("No active terrain session"));
                return 0;
            }
        } catch (Exception e) {
            LOG.error("Tile probe failed", e);
            ((ServerCommandSource) ctx.getSource()).sendError(Text.literal("Probe failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int executeStats(CommandContext<ServerCommandSource> ctx) {
        for (String line : statsReport(WorldSession.current())) {
            ctx.getSource().sendFeedback(() -> Text.literal(line), false);
        }
        return 1;
    }

    public static List<String> statsReport(WorldSession session) {
        String fingerprint = session == null ? "none" : session.fingerprint();
        long windows = LocalTerrainProvider.totalComputedWindows();
        String storeInfo;
        if (session != null && session.repository() != null) {
            try {
                storeInfo = session.repository().store().usedBytes() + " bytes on disk";
            } catch (Exception e) {
                storeInfo = "disk size unknown (" + e.getMessage() + ")";
            }
        } else {
            storeInfo = "no repository bound";
        }

        String report = "session="
                + fingerprint
                + " pipelineWindows="
                + windows
                + " "
                + GenerationMetrics.snapshot()
                + " store="
                + storeInfo;
        TerrainDiffusionMc.PrefetchRuntime runtime = PREFETCH;
        if (runtime != null && runtime.ticket == session) {
            report = report
                    + " prefetch="
                    + runtime.service.metrics()
                    + " queue="
                    + runtime.service.queued()
                    + " inFlight="
                    + runtime.service.inFlight();
        } else {
            report = report + " prefetch=off";
        }

        var lines = new ArrayList<String>();
        lines.add(report);
        lines.add("inference timings: " + LocalTerrainProvider.inferenceTimings());
        lines.add("onnx timings: " + com.github.xandergos.terraindiffusionmc.pipeline.OnnxModel.statistics());
        lines.add(com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics.previewStatus());
        if (session != null && session.automaticWater() != null)
            lines.add(session.automaticWater().performance());
        return List.copyOf(lines);
    }

    private static final class PrefetchRuntime {
        private final WorldSession ticket;
        private final TilePrefetchService service;
        private final TilePrefetchSampler sampler;
        private long tickStartNanos;

        private PrefetchRuntime(WorldSession ticket, TilePrefetchService service, TilePrefetchSampler sampler) {
            this.ticket = ticket;
            this.service = service;
            this.sampler = sampler;
        }

        private void stop() {
            this.sampler.stop();
            this.service.shutdown();
        }
    }
}

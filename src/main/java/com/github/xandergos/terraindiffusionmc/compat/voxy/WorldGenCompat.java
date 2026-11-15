package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import java.lang.reflect.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/** Coordinates with the public 2.2.4 scheduler settings instead of launching a second generator. */
public final class WorldGenCompat {
    private static volatile MinecraftServer server;
    private static Object manager;
    private static Method currentLevel;
    private static Field data, enabled, radius;
    private static volatile boolean supported, installed;
    private static long nextSync;
    private static String problem;
    private static volatile boolean active = true;
    private static volatile int blocks = 1024;

    private WorldGenCompat() {}

    public static boolean supports(String version) {
        return "2.2.4".equals(version);
    }

    public static void install() {
        var mod = FabricLoader.getInstance().getModContainer("voxyworldgenv2");
        if (mod.isEmpty()) return;
        installed = true;
        if (!supports(mod.get().getMetadata().getVersion().getFriendlyString())) {
            problem = "Для прямого потока с VoxyWorldGen нужна версия 2.2.4";
            emit(
                    Code.VOX_VERSION,
                    current("WORLDGEN_BRIDGE_INIT"),
                    new IllegalArgumentException(problem),
                    Outcome.DEGRADED,
                    "Optional streaming disabled; native generation unchanged",
                    false);
            return;
        }
        if (!TerrainDiffusionConfig.worldGenCompat()) {
            problem = "Координация VoxyWorldGen выключена; прямой поток остановлен, чтобы не запускать два генератора";
            return;
        }
        try {
            var type = Class.forName("com.ethan.voxyworldgenv2.core.ChunkGenerationManager");
            manager = type.getMethod("getInstance").invoke(null);
            currentLevel = type.getMethod("getCurrentLevel");
            data = Class.forName("com.ethan.voxyworldgenv2.core.Config").getField("DATA");
            var config = data.getType();
            enabled = config.getField("enabled");
            radius = config.getField("generationRadius");
            supported = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            unavailable(e);
        }
    }

    static void unavailable(Throwable failure) {
        supported = false;
        installed = true;
        problem = "VoxyWorldGen API unavailable: " + failure.getClass().getSimpleName();
        emit(
                Code.VOX_API,
                current("WORLDGEN_BRIDGE_API"),
                failure,
                Outcome.DEGRADED,
                "Only optional direct streaming disabled; native chunk generation unchanged",
                false);
    }

    public static void serverStarted(MinecraftServer value) {
        server = value;
    }

    public static boolean pauseNative() {
        if (!supported || !VoxyCompat.usable() || !VoxyCompat.preparedEnabled() || server == null) return false;
        try {
            Object current = currentLevel.invoke(manager);
            ServerWorld level = current instanceof ServerWorld w ? w : server.getOverworld();
            return level != null
                    && level.getChunkManager().getChunkGenerator().getBiomeSource()
                            instanceof TerrainDiffusionBiomeSource;
        } catch (ReflectiveOperationException e) {
            unavailable(e);
            return false;
        }
    }

    public static void sync() {
        if (!supported || System.nanoTime() < nextSync) return;
        nextSync = System.nanoTime() + 500_000_000L;
        try {
            Object config = data.get(null);
            active = enabled.getBoolean(config);
            blocks = radiusBlocks(radius.getInt(config));
        } catch (ReflectiveOperationException e) {
            unavailable(e);
        }
    }

    public static int radiusBlocks(int chunks) {
        return (int) Math.max(64, Math.min(LodScan.MAX_RADIUS_BLOCKS, (long) chunks * 16));
    }

    public static boolean skipDuplicateBroadcast(ServerWorld level) {
        return !TerrainDiffusionConfig.voxyNativeChunks()
                && supported
                && VoxyCompat.usable()
                && level.getServer() == server
                && level.getServer().getPlayerManager().getPlayerList().size() <= 1
                && level.getChunkManager().getChunkGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource;
    }

    public static boolean present() {
        return supported;
    }

    public static boolean safeToStream() {
        return !installed || supported;
    }

    public static String problem() {
        return problem == null ? "Интерфейс VoxyWorldGen недоступен" : problem;
    }

    public static boolean enabled() {
        return !supported || active;
    }

    public static int radius() {
        return blocks;
    }
}

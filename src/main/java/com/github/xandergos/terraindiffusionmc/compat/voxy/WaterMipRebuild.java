package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.client.ClientPerformance;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/** Explicit bounded migration of existing LOD data, independent of Chunky terrain generation. */
public final class WaterMipRebuild {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "td-water-lod-rebuild");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile boolean cancelled;
    private static volatile String status = "water-mips: пересчёт не запущен";

    private WaterMipRebuild() {}

    public static String status() {
        return status;
    }

    public static boolean running() {
        return RUNNING.get();
    }

    public static String stop() {
        cancelled = true;
        return "Остановка пересчёта LOD воды запрошена.";
    }

    public static String start(Voxy016Api api, MinecraftClient client, int radiusChunks)
            throws ReflectiveOperationException {
        if (client.player == null) return "Нет активного игрока.";
        return startAt(api, client, client.player.getBlockX(), client.player.getBlockZ(), radiusChunks);
    }

    public static String startAt(Voxy016Api api, MinecraftClient client, int centerX, int centerZ, int radiusChunks)
            throws ReflectiveOperationException {
        if (radiusChunks < 1 || radiusChunks > 256) throw new IllegalArgumentException("Radius must be 1..256 chunks");
        var ticket = WorldSession.current();
        var world = client.world;
        var player = client.player;
        if (ticket == null || !ticket.isActive() || world == null || player == null)
            return "Нет активного мира European Wilderness.";
        if (!RUNNING.compareAndSet(false, true)) return "Пересчёт уже выполняется. /td-voxy water-rebuild-stop";
        Voxy016Api.Bound bound;
        try {
            bound = api.acquire(world);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            RUNNING.set(false);
            throw failure;
        }
        if (bound == null) {
            RUNNING.set(false);
            return "Voxy не готов принимать данные.";
        }
        int blocks = Math.multiplyExact(radiusChunks, 16);
        int minX = Math.floorDiv(centerX - blocks, 32), maxX = Math.floorDiv(centerX + blocks, 32);
        int minZ = Math.floorDiv(centerZ - blocks, 32), maxZ = Math.floorDiv(centerZ + blocks, 32);
        int minY = Math.floorDiv(world.getBottomY(), 32), maxY = Math.floorDiv(world.getTopYInclusive(), 32);
        long total = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        cancelled = false;
        status = "water-mips: колонки 0/" + total + ", радиус " + radiusChunks + " чанков";
        WORKER.execute(() -> {
            long checked = 0, changed = 0, columns = 0;
            String end;
            try (bound) {
                scan:
                for (int z = minZ; z <= maxZ; z++)
                    for (int x = minX; x <= maxX; x++) {
                        if (cancelled || !ticket.isActive() || client.world != world || !bound.active()) break scan;
                        // Walk Voxy's existing hierarchy instead of probing every empty sky cube.
                        int ceiling = bound.highestKnownCube(x * 32, z * 32, minY * 32, maxY * 32 + 31);
                        for (int y = minY; y <= ceiling; y++) {
                            while (!ClientPerformance.mayPublish()
                                    && !cancelled
                                    && ticket.isActive()
                                    && client.world == world) LockSupport.parkNanos(5_000_000L);
                            if (cancelled || !ticket.isActive() || client.world != world || !bound.active()) break scan;
                            int updates = bound.repairWaterCube(x, y, z);
                            checked++;
                            changed += updates;
                            if ((checked & 63) == 0 || updates != 0)
                                status = "water-mips: колонки "
                                        + columns
                                        + "/"
                                        + total
                                        + ", секций "
                                        + checked
                                        + ", обновлений LOD "
                                        + changed;
                            if (updates != 0) LockSupport.parkNanos(1_000_000L);
                        }
                        columns++;
                    }
                end = "water-mips: "
                        + (columns == total ? "готово" : "остановлено")
                        + ", колонки "
                        + columns
                        + "/"
                        + total
                        + ", секций "
                        + checked
                        + ", обновлений LOD "
                        + changed;
            } catch (Exception | LinkageError failure) {
                end = "water-mips: ошибка " + failure.getClass().getSimpleName();
                org.slf4j.LoggerFactory.getLogger(WaterMipRebuild.class).warn("Water LOD rebuild failed", failure);
            }
            status = end;
            RUNNING.set(false);
            String message = end;
            org.slf4j.LoggerFactory.getLogger(WaterMipRebuild.class).info("{}", message);
            client.execute(() -> {
                if (client.world == world && client.player != null)
                    client.player.sendMessage(Text.literal(message), false);
            });
        });
        return "Пересчёт LOD воды запущен: центр ("
                + centerX
                + ", "
                + centerZ
                + "), радиус "
                + radiusChunks
                + " чанков ("
                + blocks
                + " блоков). Прогресс: /td-voxy status. Используется только кэш Voxy.";
    }
}

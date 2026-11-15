package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Plans off-thread, then commits on the server thread after a second frontier check. */
public final class PreparedWaterController implements WorldSession.RiverOverlay, AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PreparedWaterController.class);
    private final WorldSession session;
    private final PreparedWaterStore store;
    private final int surfaceCapY;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "prepared-water");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean closed;
    private volatile List<PreparedWaterArea> areas;
    private volatile String status = "Подготовка воды ещё не запускалась";

    public PreparedWaterController(WorldSession session, Path directory, int surfaceCapY) throws IOException {
        this.session = session;
        this.surfaceCapY = surfaceCapY;
        store = new PreparedWaterStore(directory, session.worldSeed(), session.fingerprint());
        areas = store.loadAll();
        // Never activate saved cuts on top of fallback/foreign terrain after cache loss.
        for (var area : areas) {
            TerrainRepository repo = session.repository();
            if (repo == null) throw new IOException("Нет рельефа для сохранённой области воды");
            int ts = repo.tileSize();
            for (int tz = Math.floorDiv(area.z(), ts); tz <= Math.floorDiv(area.z() + area.side() - 1, ts); tz++)
                for (int tx = Math.floorDiv(area.x(), ts); tx <= Math.floorDiv(area.x() + area.side() - 1, ts); tx++) {
                    var key = TerrainTileKey.ofTile(session.worldSeed(), tx, tz, ts, session.fingerprint());
                    if (repo.peek(key).isEmpty())
                        throw new IOException("Потерян сохранённый рельеф области воды: " + key);
                }
        }
        if (!areas.isEmpty()) status = "Загружено областей воды: " + areas.size();
    }

    public record Bounds(int x, int z, int side) {
        public Bounds {
            if (side < 512
                    || side > PreparedWaterArea.MAX_SIDE
                    || side % 512 != 0
                    || Math.floorMod(x, 512) != 0
                    || Math.floorMod(z, 512) != 0) throw new IllegalArgumentException("Invalid prepared-water bounds");
            Math.addExact(x, side);
            Math.addExact(z, side);
            if (Math.abs((long) x) > 29_990_000
                    || Math.abs((long) z) > 29_990_000
                    || Math.abs((long) x + side) > 29_990_000
                    || Math.abs((long) z + side) > 29_990_000)
                throw new IllegalArgumentException("Область слишком близко к границе мира");
        }

        public static Bounds around(int x, int z, int radius) {
            if (radius < 64 || radius > 512) throw new IllegalArgumentException("Радиус должен быть 64..512 блоков");
            int x0 = Math.toIntExact(Math.floorDiv((long) x - radius, 512) * 512);
            int z0 = Math.toIntExact(Math.floorDiv((long) z - radius, 512) * 512);
            int x1 = Math.toIntExact((Math.floorDiv((long) x + radius - 1, 512) + 1) * 512);
            int z1 = Math.toIntExact((Math.floorDiv((long) z + radius - 1, 512) + 1) * 512);
            return new Bounds(x0, z0, Math.max(x1 - x0, z1 - z0));
        }

        public boolean intersects(PreparedWaterArea a) {
            return (long) x + side > a.x()
                    && (long) a.x() + a.side() > x
                    && (long) z + side > a.z()
                    && (long) a.z() + a.side() > z;
        }
    }

    public void start(Bounds bounds, Runnable frontierCheck, Consumer<Runnable> serverExecutor) {
        WorldSession.requireActive(session);
        if (closed) throw new IllegalStateException("Сессия закрыта");
        if (areas.size() >= PreparedWaterStore.MAX_AREAS)
            throw new IllegalStateException("Достигнут лимит: 4 области воды");
        if (areas.stream().anyMatch(bounds::intersects))
            throw new IllegalStateException("Область пересекает уже подготовленную воду");
        frontierCheck.run();
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Подготовка воды уже выполняется");
        status = "Подготовка воды: загрузка рельефа (" + bounds.x() + ", " + bounds.z() + "), размер " + bounds.side();
        try {
            worker.submit(() -> prepare(bounds, frontierCheck, serverExecutor));
        } catch (RuntimeException failure) {
            running.set(false);
            throw failure;
        }
    }

    private void prepare(Bounds bounds, Runnable frontierCheck, Consumer<Runnable> serverExecutor) {
        try {
            TerrainRepository repo = Objects.requireNonNull(session.repository(), "Нет хранилища рельефа");
            int[][] raw = new int[bounds.side()][bounds.side()];
            int ts = repo.tileSize(), tx0 = Math.floorDiv(bounds.x(), ts), tz0 = Math.floorDiv(bounds.z(), ts);
            int tx1 = Math.floorDiv(bounds.x() + bounds.side() - 1, ts),
                    tz1 = Math.floorDiv(bounds.z() + bounds.side() - 1, ts);
            int done = 0, total = (tx1 - tx0 + 1) * (tz1 - tz0 + 1);
            for (int tz = tz0; tz <= tz1; tz++)
                for (int tx = tx0; tx <= tx1; tx++) {
                    requireActive();
                    TerrainTile tile =
                            repo.get(TerrainTileKey.ofTile(session.worldSeed(), tx, tz, ts, session.fingerprint()));
                    int x0 = Math.max(bounds.x(), tx * ts), z0 = Math.max(bounds.z(), tz * ts);
                    int x1 = Math.min(bounds.x() + bounds.side(), (tx + 1) * ts),
                            z1 = Math.min(bounds.z() + bounds.side(), (tz + 1) * ts);
                    for (int z = z0; z < z1; z++)
                        for (int x = x0; x < x1; x++)
                            raw[z - bounds.z()][x - bounds.x()] =
                                    MountainHeightField.sample(
                                                    session.worldSeed(),
                                                    x,
                                                    z,
                                                    tile.heightAt(Math.floorMod(x, ts), Math.floorMod(z, ts)),
                                                    session.profile().worldScale(),
                                                    surfaceCapY,
                                                    session.profile().mountainRelief())
                                            .surfaceY();
                    status = "Подготовка воды: рельеф " + (++done) + " / " + total;
                }
            requireActive();
            status = "Подготовка воды: расчёт рек и озёр";
            PreparedWaterArea area =
                    PreparedWaterArea.plan(
                            session.worldSeed(),
                            bounds.x(),
                            bounds.z(),
                            raw,
                            session.profile().water(),
                            session.profile().worldScale());
            requireActive();
            if (area.wetColumns() == 0) {
                status =
                        "В области нет замкнутых озёр или рек с безопасным выходом. Выберите другое место или больший радиус.";
                return;
            }
            CompletableFuture<Void> commit = new CompletableFuture<>();
            serverExecutor.accept(() -> {
                try {
                    requireActive();
                    frontierCheck.run();
                    store.save(area);
                    List<PreparedWaterArea> next = new ArrayList<>(areas);
                    next.add(area);
                    areas = List.copyOf(next);
                    status =
                            "Вода готова: от ("
                                    + area.x()
                                    + ", "
                                    + area.z()
                                    + ") до ("
                                    + (area.x() + area.side() - 1)
                                    + ", "
                                    + (area.z() + area.side() - 1)
                                    + "), водных колонок: "
                                    + area.wetColumns()
                                    + ", водопадов: "
                                    + area.waterfalls().size()
                                    + ". Координаты: /td-water falls";
                    commit.complete(null);
                } catch (Exception error) {
                    commit.completeExceptionally(error);
                }
            });
            commit.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            status = "Подготовка воды отменена при закрытии мира";
        } catch (Exception failure) {
            Throwable cause =
                    failure instanceof ExecutionException && failure.getCause() != null ? failure.getCause() : failure;
            LOG.error("Water preparation failed for {}", bounds, cause);
            status = "Подготовка воды не завершена: " + cause.getMessage();
        } finally {
            running.set(false);
        }
    }

    private void requireActive() throws InterruptedException {
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        WorldSession.requireActive(session);
    }

    public String status() {
        return status;
    }

    private PreparedWaterArea areaAt(int x, int z) {
        for (var a : areas) if (a.contains(x, z)) return a;
        return null;
    }

    // Closing cancels new work; immutable terrain remains readable until the
    // world session retires, including while Minecraft drains chunk work on shutdown.
    @Override
    public boolean isReady(int x, int z) {
        return session.isActive() && areaAt(x, z) != null;
    }

    @Override
    public int carvedHeight(int x, int z, int rawY) {
        if (!session.isActive()) return rawY;
        var a = areaAt(x, z);
        return a == null ? rawY : a.carvedHeight(x, z, rawY);
    }

    @Override
    public int waterY(int x, int z) {
        if (!session.isActive()) return PreparedWaterArea.NONE;
        var a = areaAt(x, z);
        return a == null ? PreparedWaterArea.NONE : a.waterY(x, z);
    }

    @Override
    public short currentAt(int x, int y, int z) {
        if (!session.isActive()) return 0;
        var area = areaAt(x, z);
        return area == null ? 0 : area.currentAt(x, y, z);
    }

    @Override
    public int bedY(int x, int z, int rawY) {
        return carvedHeight(x, z, rawY) - 1;
    }

    @Override
    public Waterfall waterfallAt(int x, int z) {
        if (!session.isActive()) return null;
        var area = areaAt(x, z);
        return area == null ? null : area.waterfallAt(x, z);
    }

    @Override
    public List<Waterfall> waterfalls() {
        if (!session.isActive()) return List.of();
        return areas.stream().flatMap(a -> a.waterfalls().stream()).toList();
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
    }
}

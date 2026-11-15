package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.WorkTimeline.Stage.*;

import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WaterGenerationOptions;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** On-demand, persistent global water. Dependency graph is core -> unit -> raw terrain only. */
public final class WorldWaterController implements WorldSession.RiverOverlay, AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorldWaterController.class);
    private final WorldSession ticket;
    private final TerrainRepository repository;
    private final WorldWaterStore store;
    private final WorldWaterGrid grid;
    private final int cap;
    private final int newPlanRevision;
    private final WaterPlanCache<WorldWaterGrid.Cell, WorldWaterTile> tiles;
    private final WaterPlanCache<WorldWaterGrid.Cell, WorldWaterUnit> units;

    /** A null writer persists each plan synchronously. */
    private final WaterWriteBehind writer;

    private final Semaphore planning = new Semaphore(1, true);
    // Hold scratch space for one water unit through persistence.
    private final Semaphore unitMemory = new Semaphore(1, true);
    private volatile long unitMemorySince;

    public long diagnosticHoldNanos() {
        long since = unitMemorySince;
        return since == 0 ? 0 : Math.max(0, System.nanoTime() - since);
    }

    private final Semaphore ahead = new Semaphore(1);
    private final AtomicInteger foreground = new AtomicInteger();
    private final StageTimings timings = new StageTimings();
    // 32 * 256 * 256 * 4 = 8 MiB at the default raw tile size.
    private final WaterPlanCache<TerrainTileKey, int[]> heights;
    private final AtomicInteger computedTiles = new AtomicInteger(), computedUnits = new AtomicInteger();
    private final ThreadLocal<WorldWaterTile> last = new ThreadLocal<>();
    private final UUID densityCacheId = UUID.randomUUID();

    /** Immutable plan identity without retaining this controller in worker caches. */
    public UUID densityCacheId() {
        return densityCacheId;
    }

    private volatile boolean closed;
    private final Semaphore currentRead = new Semaphore(1);
    private final WaterPlanCache<WorldWaterGrid.Cell, short[]> restoredCores = new WaterPlanCache<>(16);
    private final WaterPlanCache<WorldWaterGrid.Cell, WorldWaterUnit> restoredUnits = new WaterPlanCache<>(8);
    private long nextCurrentRead = System.nanoTime();
    private volatile Set<WorldWaterGrid.Cell> aheadTargets = Set.of();

    public void prioritizeAhead(List<TerrainTileKey> keys) {
        Set<WorldWaterGrid.Cell> cells = new HashSet<>();
        for (var key : keys) cells.add(grid.cell(key.blockStartX(), key.blockStartZ()));
        aheadTargets = Set.copyOf(cells);
    }

    public boolean prepareDistantStep(int x, int z, BooleanSupplier allowed) throws Exception {
        var priorities = aheadTargets;
        var requested = grid.cell(x, z);
        if (!priorities.contains(requested))
            for (var key : priorities)
                if (!tiles.contains(key) && !store.hasTile(key)) {
                    prepareAheadStep(grid.x(key), grid.z(key), allowed);
                    return false;
                }
        try (var ignored = com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.scope(
                com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.Priority.DISTANT)) {
            return prepareStep(x, z, allowed);
        }
    }

    private volatile String progress = "Ожидание генерации местности";

    public WorldWaterController(WorldSession ticket, Path directory, int surfaceCapY) {
        this(ticket, directory, surfaceCapY, WorldWaterGrid.DEFAULT, 16, 24, 6);
    }

    WorldWaterController(
            WorldSession ticket, Path directory, int surfaceCapY, WorldWaterGrid grid, int tileCache, int unitCache) {
        this(ticket, directory, surfaceCapY, grid, tileCache, unitCache, 1);
    }

    WorldWaterController(
            WorldSession ticket,
            Path directory,
            int surfaceCapY,
            WorldWaterGrid grid,
            int tileCache,
            int unitCache,
            int revision) {
        if (revision < 1 || revision > 6) throw new IllegalArgumentException("Unsupported new-plan revision");
        newPlanRevision = revision;
        this.ticket = Objects.requireNonNull(ticket);
        repository = Objects.requireNonNull(ticket.repository());
        this.cap = surfaceCapY;
        this.grid = grid;
        store = new WorldWaterStore(directory, ticket.worldSeed(), ticket.fingerprint(), repository.tileSize(), grid);
        writer = com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig.waterWriteBehind()
                ? new WaterWriteBehind(store, 16)
                : null;
        tiles = new WaterPlanCache<>(tileCache);
        units = new WaterPlanCache<>(unitCache);
        heights = new WaterPlanCache<>(
                Math.max(1, 8 * 1024 * 1024 / (repository.tileSize() * repository.tileSize() * 4)));
    }

    public void ensureColumn(int x, int z) {
        if (inWorld(x, z)) tile(x, z);
    }

    public int coreSize() {
        return grid.core();
    }

    public boolean foregroundBusy() {
        return foreground.get() > 0;
    }

    public boolean isPrepared(int x, int z) {
        if (!ticket.isActive() || closed || !inWorld(x, z)) return false;
        var cell = grid.cell(x, z);
        return tiles.contains(cell) || store.hasTile(cell);
    }

    /** Read-only export: never creates terrain or plans when a core is absent. */
    public PreparedWaterView frozenCoreIfReady(int x, int z) throws Exception {
        requirePlanning();
        var key = grid.cell(x, z);
        var ready = tiles.peek(key);
        if (ready == null) {
            if (!store.hasTile(key)) return null;
            ready = tiles.get(key, () -> {
                requirePlanning();
                var saved = store.loadTile(key);
                if (saved == null) throw new IOException("Frozen core disappeared during LOD export");
                requireRawPresent(grid.x(key) - grid.renderHalo(), grid.z(key) - grid.renderHalo(), grid.renderSide());
                return saved;
            });
        }
        WorldSession.requireActive(ticket);
        return ready.export();
    }

    /** One raw tile OR one already-fed watershed OR one final core per admission.
     * Never installs a shared core future and then suspends it for foreground work. */
    public boolean prepareAheadStep(int x, int z, BooleanSupplier allowed) throws Exception {
        try (var ignored = com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.scope(
                com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.Priority.NEAR)) {
            return prepareStep(x, z, allowed);
        }
    }

    private boolean prepareStep(int x, int z, BooleanSupplier allowed) throws Exception {
        if (!inWorld(x, z)
                || !ticket.isActive()
                || closed
                || foregroundBusy()
                || !allowed.getAsBoolean()
                || !ahead.tryAcquire()) return false;
        long start = System.nanoTime();
        try {
            if (!ticket.claimBackgroundStep()) return false;
            requirePlanning();
            var cell = grid.cell(x, z);
            if (isPrepared(x, z)) return true;
            for (var unit : dependencies(cell)) {
                if (!mayPrepare(allowed)) return false;
                if (units.contains(unit) || store.hasUnit(unit)) continue;
                if (!rawReadyOrPrepareOne(
                        grid.x(unit) - grid.unitHalo(), grid.z(unit) - grid.unitHalo(), grid.unitSide(), allowed))
                    return false;
                if (!mayPrepare(allowed)) return false;
                // Once shared with a foreground reader, finish this finite stage.
                units.get(unit, () -> loadUnit(unit));
                return false;
            }
            if (!rawReadyOrPrepareOne(
                    grid.x(cell) - grid.renderHalo(), grid.z(cell) - grid.renderHalo(), grid.renderSide(), allowed))
                return false;
            if (!mayPrepare(allowed)) return false;
            tiles.get(cell, () -> loadTile(cell));
            return true;
        } finally {
            timings.record("aheadStep", System.nanoTime() - start);
            ahead.release();
        }
    }

    private boolean mayPrepare(BooleanSupplier allowed) {
        return !closed && ticket.backgroundAllowed() && !foregroundBusy() && allowed.getAsBoolean();
    }

    private boolean rawReadyOrPrepareOne(int x, int z, int side, BooleanSupplier allowed) throws Exception {
        int ts = repository.tileSize();
        for (int tz = Math.floorDiv(z, ts); tz <= Math.floorDiv(z + side - 1, ts); tz++)
            for (int tx = Math.floorDiv(x, ts); tx <= Math.floorDiv(x + side - 1, ts); tx++) {
                if (!mayPrepare(allowed)) return false;
                var key = TerrainTileKey.ofTile(ticket.worldSeed(), tx, tz, ts, ticket.fingerprint());
                if (!heights.contains(key) && repository.peek(key).isEmpty()) {
                    if (mayPrepare(allowed)) source(key);
                    return false;
                }
            }
        return true;
    }

    private List<WorldWaterGrid.Cell> dependencies(WorldWaterGrid.Cell cell) {
        int x = grid.x(cell) - grid.renderHalo(), z = grid.z(cell) - grid.renderHalo(), side = grid.renderSide();
        int minX = Math.floorDiv(x - grid.unitHalo(), grid.core()),
                minZ = Math.floorDiv(z - grid.unitHalo(), grid.core());
        int maxX = Math.floorDiv(x + side - 1 + grid.unitHalo(), grid.core()),
                maxZ = Math.floorDiv(z + side - 1 + grid.unitHalo(), grid.core());
        List<WorldWaterGrid.Cell> result = new ArrayList<>();
        for (int uz = minZ; uz <= maxZ; uz++)
            for (int ux = minX; ux <= maxX; ux++) result.add(new WorldWaterGrid.Cell(ux, uz));
        return result;
    }

    public void ensureRegion(int x, int z, int endX, int endZ) {
        for (long wz = z; wz < endZ; wz = (Math.floorDiv(wz, grid.core()) + 1) * grid.core())
            for (long wx = x; wx < endX; wx = (Math.floorDiv(wx, grid.core()) + 1) * grid.core())
                ensureColumn((int) wx, (int) wz);
    }

    private boolean inWorld(int x, int z) {
        return Math.abs((long) x) < 29_997_000 && Math.abs((long) z) < 29_997_000;
    }

    private void requirePlanning() throws IOException {
        WorldSession.requireActive(ticket);
        if (closed || Thread.currentThread().isInterrupted()) throw new IOException("World water session closed");
    }

    private WorldWaterTile tile(int x, int z) {
        WorldSession.requireActive(ticket);
        WorldWaterTile hot = last.get();
        if (hot != null && hot.contains(x, z)) return hot;
        var key = grid.cell(x, z);
        long start = System.nanoTime(), trace = PerformanceDiagnostics.begin(WATER_FOREGROUND_WAIT);
        foreground.incrementAndGet();
        try {
            var tile = tiles.get(key, () -> loadTile(key));
            WorldSession.requireActive(ticket);
            last.set(tile);
            return tile;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Water planning interrupted", e);
        } catch (Exception e) {
            emit(
                    cancelled(e) ? Code.LFC_CANCEL : Code.HYD_PLAN,
                    DiagnosticErrors.context("WATER_FOREGROUND", "blocks=(" + grid.x(key) + "," + grid.z(key) + ")"),
                    e,
                    cancelled(e) ? Outcome.CANCELLED : Outcome.FAILED,
                    "No invalid water published; exception propagated",
                    !cancelled(e));
            throw new IllegalStateException(
                    "Cannot generate water at " + grid.x(key) + ", " + grid.z(key) + ": " + e.getMessage(), e);
        } finally {
            PerformanceDiagnostics.end(trace);
            foreground.decrementAndGet();
            timings.record("foreground", System.nanoTime() - start);
        }
    }

    private DiagnosticErrors.Context waterContext(String stage, WorldWaterGrid.Cell cell) {
        var outer = DiagnosticErrors.context(
                stage,
                "waterCell=(" + cell.x() + "," + cell.z() + ") blocks=(" + grid.x(cell) + "," + grid.z(cell) + ")");
        return new DiagnosticErrors.Context(
                outer.requestId(),
                stage,
                ticket.diagnosticDimension(),
                ticket.id().toString(),
                outer.coordinates());
    }

    private WorldWaterTile loadTile(WorldWaterGrid.Cell cell) throws Exception {
        try (var diagnostic = DiagnosticErrors.scope(waterContext("WATER_TILE", cell))) {
            requirePlanning();
            long io = System.nanoTime();
            var saved = store.loadTile(cell);
            timings.record("ioRead", System.nanoTime() - io);
            int x = grid.x(cell) - grid.renderHalo(), z = grid.z(cell) - grid.renderHalo(), side = grid.renderSide();
            if (saved != null) {
                requireRawPresent(x, z, side);
                return saved;
            }
            List<WorldWaterUnit> plans = new ArrayList<>();
            for (var key : dependencies(cell)) {
                requirePlanning();
                plans.add(units.get(key, () -> loadUnit(key)));
            }
            requirePlanning();
            progress = "Формирование берегов: " + grid.x(cell) + ", " + grid.z(cell);
            var raw = loadRaw(x, z, side);
            long start = System.nanoTime();
            long trace = PerformanceDiagnostics.begin(WATER_BANKS);
            WorldWaterTile result;
            try {
                result = WorldWaterTile.render(ticket.worldSeed(), grid, cell, raw, plans);
            } finally {
                PerformanceDiagnostics.end(trace);
                timings.record("banks", System.nanoTime() - start);
            }
            requirePlanning();
            io = System.nanoTime();
            saveTile(result);
            timings.record("ioWrite", System.nanoTime() - io);
            computedTiles.incrementAndGet();
            progress = "Вода готова около " + grid.x(cell) + ", " + grid.z(cell);
            return result;
        }
    }

    private WorldWaterUnit loadUnit(WorldWaterGrid.Cell cell) throws Exception {
        try (var diagnostic = DiagnosticErrors.scope(waterContext("WATER_UNIT", cell))) {
            requirePlanning();
            long io = System.nanoTime();
            var saved = store.loadUnit(cell);
            timings.record("ioRead", System.nanoTime() - io);
            if (saved != null) {
                requireRawPresent(grid.x(cell) - grid.unitHalo(), grid.z(cell) - grid.unitHalo(), grid.unitSide());
                return saved;
            }
            int x = grid.x(cell) - grid.unitHalo(), z = grid.z(cell) - grid.unitHalo(), side = grid.unitSide();
            long preparing = System.nanoTime();
            try {
                prepareRawDependencies(x, z, side);
            } finally {
                timings.record("unitDependencies", System.nanoTime() - preparing);
            }
            long waiting = System.nanoTime();
            unitMemory.acquire();
            long memoryAcquired = System.nanoTime();
            unitMemorySince = memoryAcquired;
            timings.record("unitMemoryWait", memoryAcquired - waiting);
            try {
                requirePlanning();
                progress = "Расчёт водосборов: " + grid.x(cell) + ", " + grid.z(cell);
                LOG.info("Planning natural water catchments at {}, {}", grid.x(cell), grid.z(cell));
                WorldWaterUnit result = planReadyUnit(cell, x, z, side);
                requirePlanning();
                io = System.nanoTime();
                saveUnit(result);
                timings.record("ioWrite", System.nanoTime() - io);
                computedUnits.incrementAndGet();
                return result;
            } finally {
                timings.record("unitMemoryHold", System.nanoTime() - memoryAcquired);
                unitMemorySince = 0;
                unitMemory.release();
            }
        }
    }

    private void prepareRawDependencies(int x, int z, int side) throws Exception {
        int ts = repository.tileSize();
        for (int tz = Math.floorDiv(z, ts); tz <= Math.floorDiv(z + side - 1, ts); tz++)
            for (int tx = Math.floorDiv(x, ts); tx <= Math.floorDiv(x + side - 1, ts); tx++) {
                requirePlanning();
                var key = TerrainTileKey.ofTile(ticket.worldSeed(), tx, tz, ts, ticket.fingerprint());
                if (!heights.contains(key)) source(key);
            }
    }

    private WorldWaterUnit planReadyUnit(WorldWaterGrid.Cell cell, int x, int z, int side) throws Exception {
        // Raw IO/height conversion cannot retain the CPU permit; the memory gate bounds this grid.
        int[][] raw = loadRaw(x, z, side);
        long waiting = System.nanoTime();
        planning.acquire();
        long acquired = System.nanoTime();
        timings.record("unitWait", acquired - waiting);
        long trace = 0;
        try {
            requirePlanning();
            trace = PerformanceDiagnostics.begin(WATER_PLAN);
            try {
                return WorldWaterUnit.plan(
                        ticket.worldSeed(),
                        grid,
                        cell,
                        raw,
                        newPlanRevision,
                        newPlanRevision >= 6 ? ticket.profile().water() : WaterGenerationOptions.DEFAULT,
                        ticket.profile().worldScale());
            } catch (RuntimeException failure) {
                failed(Code.HYD_PLAN, "HYDROLOGY_PLAN", failure);
                throw failure;
            }
        } finally {
            PerformanceDiagnostics.end(trace);
            long elapsed = System.nanoTime() - acquired;
            timings.record("unitPlan", elapsed);
            timings.record("unitHold", elapsed);
            planning.release();
        }
    }

    private int[][] loadRaw(int x, int z, int side) throws Exception {
        return loadRaw(x, z, side, false);
    }

    private int[][] loadRaw(int x, int z, int side, boolean frozenOnly) throws Exception {
        int[][] raw = new int[side][side];
        int ts = repository.tileSize();
        for (int tz = Math.floorDiv(z, ts); tz <= Math.floorDiv(z + side - 1, ts); tz++)
            for (int tx = Math.floorDiv(x, ts); tx <= Math.floorDiv(x + side - 1, ts); tx++) {
                requirePlanning();
                var key = TerrainTileKey.ofTile(ticket.worldSeed(), tx, tz, ts, ticket.fingerprint());
                int[] converted = heights.get(key, () -> {
                    var tile = frozenOnly
                            ? repository.peek(key).orElseThrow(() -> new IOException("Missing frozen terrain " + key))
                            : source(key);
                    int[] value = new int[ts * ts];
                    long start = System.nanoTime();
                    long trace = PerformanceDiagnostics.begin(WATER_HEIGHT);
                    try {
                        for (int lz = 0; lz < ts; lz++)
                            for (int lx = 0; lx < ts; lx++)
                                value[lz * ts + lx] = MountainHeightField.sample(
                                                ticket.worldSeed(),
                                                key.blockStartX() + lx,
                                                key.blockStartZ() + lz,
                                                tile.heightAt(lx, lz),
                                                ticket.profile().worldScale(),
                                                cap,
                                                ticket.profile().mountainRelief())
                                        .surfaceY();
                        return value;
                    } finally {
                        PerformanceDiagnostics.end(trace);
                        timings.record("heightTransform", System.nanoTime() - start);
                    }
                });
                for (int wz = Math.max(z, tz * ts); wz < Math.min(z + side, (tz + 1) * ts); wz++) {
                    int wx = Math.max(x, tx * ts), end = Math.min(x + side, (tx + 1) * ts);
                    System.arraycopy(
                            converted,
                            Math.floorMod(wz, ts) * ts + Math.floorMod(wx, ts),
                            raw[wz - z],
                            wx - x,
                            end - wx);
                }
            }
        return raw;
    }

    private TerrainTile source(TerrainTileKey key) throws Exception {
        long start = System.nanoTime();
        try {
            return repository.get(key);
        } finally {
            timings.record("rawWait", System.nanoTime() - start);
        }
    }

    private void requireRawPresent(int x, int z, int side) throws IOException {
        int ts = repository.tileSize();
        for (int tz = Math.floorDiv(z, ts); tz <= Math.floorDiv(z + side - 1, ts); tz++)
            for (int tx = Math.floorDiv(x, ts); tx <= Math.floorDiv(x + side - 1, ts); tx++)
                if (repository
                        .peek(TerrainTileKey.ofTile(ticket.worldSeed(), tx, tz, ts, ticket.fingerprint()))
                        .isEmpty()) {
                    var missing = new IOException("Missing frozen terrain for world water at tile " + tx + ", " + tz);
                    failed(Code.HYD_RAW, "RAW_WATER_DEPENDENCY", missing);
                    throw missing;
                }
    }

    @Override
    public boolean isReady(int x, int z) {
        if (!ticket.isActive() || !inWorld(x, z)) return false;
        tile(x, z);
        return true;
    }

    @Override
    public int carvedHeight(int x, int z, int raw) {
        if (!ticket.isActive() || !inWorld(x, z)) return raw;
        var tile = tile(x, z);
        return tile.air[tile.index(x, z)];
    }

    @Override
    public int waterY(int x, int z) {
        if (!ticket.isActive() || !inWorld(x, z)) return PreparedWaterArea.NONE;
        var tile = tile(x, z);
        return tile.water[tile.index(x, z)];
    }

    @Override
    public short currentAt(int x, int y, int z) {
        if (closed || !ticket.isActive() || !inWorld(x, z) || y <= 62) return 0;
        var cell = grid.cell(x, z);
        var ready = tiles.peek(cell);
        if (ready != null) {
            int index = ready.index(x, z);
            if (y < ready.air[index] || y > ready.water[index]) return 0;
            short current = ready.currentAt(x, y, z);
            if (current != 0) return current;
            var restored = restoredCores.peek(cell);
            if (restored != null) return restored.length == 0 ? 0 : restored[index];
        }
        // Entity ticks can request saved currents without waiting for IO or planning.
        long now = System.nanoTime();
        if (now - nextCurrentRead >= 0 && currentRead.tryAcquire()) {
            nextCurrentRead = now + 250_000_000L;
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    frozenCoreIfReady(x, z);
                    var saved = tiles.peek(cell);
                    if (saved != null && saved.water[saved.index(x, z)] >= y && saved.currentAt(x, y, z) == 0)
                        restoredCores.get(cell, () -> restoreCurrents(saved));
                } catch (Exception failure) {
                    if (!closed && ticket.isActive())
                        LOG.debug("Could not load river current at {}, {}", x, z, failure);
                } finally {
                    currentRead.release();
                }
            });
        }
        return 0;
    }

    private short[] restoreCurrents(WorldWaterTile tile) throws Exception {
        List<WorldWaterUnit> contributors = new ArrayList<>();
        boolean restored = false;
        for (var key : dependencies(tile.cell)) {
            requirePlanning();
            var unit = units.peek(key);
            if (unit == null) unit = store.loadUnit(key);
            if (unit == null) continue;
            if (unit.revision < 6 && unit.hasRiverIn(grid, tile)) {
                var frozen = unit;
                unit = restoredUnits.get(key, () -> {
                    unitMemory.acquire();
                    try {
                        int x = grid.x(key) - grid.unitHalo(), z = grid.z(key) - grid.unitHalo();
                        return frozen.restoreCurrents(ticket.worldSeed(), grid, loadRaw(x, z, grid.unitSide(), true));
                    } finally {
                        unitMemory.release();
                    }
                });
                restored = true;
            }
            contributors.add(unit);
        }
        if (!restored) return new short[0];
        int[] water = new int[tile.water.length];
        Arrays.fill(water, PreparedWaterArea.NONE);
        boolean[] lake = new boolean[water.length];
        short[] flow = new short[water.length];
        for (var unit : contributors) unit.merge(grid, tile.x, tile.z, tile.side, water, lake, flow);
        for (int i = 0; i < flow.length; i++) if (water[i] != tile.water[i]) flow[i] = 0;
        return flow;
    }

    @Override
    public int bedY(int x, int z, int raw) {
        return carvedHeight(x, z, raw) - 1;
    }

    @Override
    public Waterfall waterfallAt(int x, int z) {
        if (!ticket.isActive() || !inWorld(x, z)) return null;
        for (var f : tile(x, z).falls) if (f.contains(x, z)) return f;
        return null;
    }

    @Override
    public List<Waterfall> waterfalls() {
        return tiles.snapshot().stream()
                .flatMap(t -> t.falls.stream())
                .distinct()
                .sorted(Comparator.comparingInt(Waterfall::x).thenComparingInt(Waterfall::z))
                .toList();
    }

    @Override
    public List<Waterfall> waterfallsForChunk(int chunkX, int chunkZ) {
        int x = Math.multiplyExact(chunkX, 16), z = Math.multiplyExact(chunkZ, 16);
        return ticket.isActive() && inWorld(x, z) ? tile(x, z).falls : List.of();
    }

    public String status() {
        return "Вода генерируется автоматически. "
                + progress
                + ". Рассчитано участков: "
                + computedTiles.get()
                + ", водосборов: "
                + computedUnits.get();
    }

    public String performance() {
        return "waterTimings="
                + timings
                + " cores={"
                + tiles.stats()
                + "} units={"
                + units.stats()
                + "} heights={"
                + heights.stats()
                + "}"
                + (writer == null
                        ? ""
                        : " writeBehind={flushed="
                                + writer.flushedCount()
                                + ", saturated="
                                + writer.saturatedCount()
                                + ", duplicate="
                                + writer.duplicateCount()
                                + ", dropped="
                                + writer.droppedCount()
                                + "}");
    }

    StageTimings timings() {
        return timings;
    }

    public Map<String, StageTimings.Snapshot> measurements() {
        return timings.snapshot();
    }

    int residentHeights() {
        return heights.size();
    }

    int residentTiles() {
        return tiles.size();
    }

    int residentUnits() {
        return units.size();
    }

    int computedUnits() {
        return computedUnits.get();
    }

    int computedTiles() {
        return computedTiles.get();
    }

    private void saveTile(WorldWaterTile tile) throws IOException {
        if (writer == null) store.save(tile);
        else writer.tile(tile);
    }

    private void saveUnit(WorldWaterUnit unit) throws IOException {
        if (writer == null) store.save(unit);
        else writer.unit(unit);
    }

    @Override
    public void close() {
        closed = true;
        if (writer != null) writer.close();
    }
}

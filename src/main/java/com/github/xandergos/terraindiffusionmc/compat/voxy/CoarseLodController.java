package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.client.ClientPerformance;
import com.github.xandergos.terraindiffusionmc.pipeline.*;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.WorldHeightProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

/** Single bounded producer of neural coarse data, independent of exact water planning. */
public final class CoarseLodController implements AutoCloseable {
    private final MinecraftClient client;
    private final ClientWorld world;
    private final WorldSession ticket;
    private final Voxy016Api api;
    private final BooleanSupplier current, allowed;
    private final CoarseLodCache cache;
    private final CoarseVoxyRenderer renderer;
    private final CoarseCoverage coverage = new CoarseCoverage();
    private final int scale, compression, span;
    private final LodChunkGuard guard;
    private final CoarseLodCorrections corrections = new CoarseLodCorrections(262144);
    private final CoarseUpdateBudget budget = new CoarseUpdateBudget();
    private final GateTracker gateTracker = new GateTracker();

    private record Anchors(int x, int z, int middle, int edge, int[] heights) {}

    private final java.util.concurrent.ConcurrentHashMap<Long, Anchors> anchors =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile int x, z, radius;
    private volatile long steps, notifications, windows;
    private volatile String problem = "";
    private volatile int lastBatch;

    public CoarseLodController(
            MinecraftClient client,
            ClientWorld world,
            WorldSession ticket,
            Voxy016Api api,
            TerrainDiffusionBiomeSource biomes,
            LodChunkGuard guard,
            BooleanSupplier current,
            BooleanSupplier allowed)
            throws Exception {
        this.client = client;
        this.world = world;
        this.ticket = ticket;
        this.api = api;
        this.current = current;
        this.allowed = allowed;
        this.guard = guard;
        scale = ticket.profile().worldScale();
        compression = WorldPipelineModelConfig.latentCompression();
        span = CoarseLodPatch.SIDE * 32 * compression * scale;
        cache = new CoarseLodCache(span, 64);
        try (var bound = api.acquire(world)) {
            if (bound == null) throw new IllegalStateException("Voxy engine is not active");
            renderer = new CoarseVoxyRenderer(
                    bound.identity(),
                    cache,
                    coverage,
                    guard,
                    this::current,
                    bound.coarsePalette(biomes),
                    world.getBottomY(),
                    world.getTopYInclusive(),
                    corrections);
        }
        CoarseRenderHooks.attach(renderer);
        var worker = new Thread(this::run, "terrain-voxy-coarse");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean current() {
        return !closed && current.getAsBoolean() && ticket.isActive();
    }

    public boolean usable() {
        return !closed && renderer.active();
    }

    public void update(int x, int z, int radius) {
        this.x = x;
        this.z = z;
        this.radius = radius;
        renderer.range(x, z, radius);
    }

    public void exact(int cellX, int cellZ, int mask, LodColumn column) {
        boolean changed = false;
        for (int dz = 0; dz < 2; dz++)
            for (int dx = 0; dx < 2; dx++)
                if (LodChunkGuard.includes(mask, dx, dz)) changed |= coverage.mark(cellX * 2 + dx, cellZ * 2 + dz);
        if (changed) {
            renderer.exactChanged(cellX, cellZ);
            if (anchors.size() < 4096) {
                int[] heights = new int[9];
                int n = 0;
                for (int z : new int[] {0, 16, 31})
                    for (int x : new int[] {0, 16, 31}) heights[n++] = column.firstAir(x, z);
                anchors.put(
                        CoarseLodCorrections.key(cellX, cellZ), new Anchors(cellX * 32, cellZ * 32, 16, 31, heights));
            }
        }
    }

    public void realChunk(int chunkX, int chunkZ) {
        if (coverage.mark(chunkX, chunkZ)) renderer.exactChanged(Math.floorDiv(chunkX, 2), Math.floorDiv(chunkZ, 2));
    }

    /** Read the already loaded chunk only; no inference, disk reads or neighbor loading. */
    public void realChunk(net.minecraft.world.chunk.WorldChunk chunk) {
        var pos = chunk.getPos();
        if (!coverage.mark(pos.x, pos.z)) return;
        // Invalidate the region probe when real chunk data arrives.
        renderer.invalidateStoredColumns(pos.x, pos.z);
        renderer.exactChanged(Math.floorDiv(pos.x, 2), Math.floorDiv(pos.z, 2));
        if (anchors.size() >= 4096) return;
        int[] heights = new int[9];
        java.util.Arrays.fill(heights, Integer.MIN_VALUE);
        int n = 0;
        var cursor = new net.minecraft.util.math.BlockPos.Mutable();
        for (int dz : new int[] {0, 8, 15})
            for (int dx : new int[] {0, 8, 15}) {
                int y = chunk.getHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES)
                                .get(dx, dz)
                        - 1;
                for (int checked = 0; checked < 64 && y >= world.getBottomY(); checked++, y--) {
                    cursor.set(pos.getStartX() + dx, y, pos.getStartZ() + dz);
                    var state = chunk.getBlockState(cursor);
                    if (state.getFluidState().isEmpty()
                            && !state.isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                            && !state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)
                            && state.isOpaqueFullCube()) {
                        heights[n] = y + 1;
                        break;
                    }
                }
                n++;
            }
        anchors.put(
                CoarseLodCorrections.key(pos.getStartX(), pos.getStartZ()),
                new Anchors(pos.getStartX(), pos.getStartZ(), 8, 15, heights));
    }

    public String statistics() {
        return "coarse={patches="
                + cache.size()
                + "/"
                + patchDemand(x, z, radius + 512, span)
                + " span="
                + span
                + " readHalo=512"
                + ", steps="
                + steps
                + ", windows="
                + windows
                + ", rendered="
                + renderer.built.sum()
                + ", notify="
                + notifications
                + ", waiting="
                + renderer.waiting.size()
                + ", dropped="
                + renderer.dropped.sum()
                + ", deferredNoField="
                + renderer.deferredNoField.sum()
                + ", deferredRegion="
                + renderer.deferredRegion.sum()
                + ", protectedExact="
                + renderer.protectedExact.sum()
                + ", outsideRadius="
                + renderer.outsideRadius.sum()
                + ", corrections="
                + corrections.size()
                + ", errors="
                + renderer.errors.sum()
                + (problem.isEmpty() ? "" : ", stopped=" + problem)
                + "} "
                + guard.renderStatistics()
                + " meshQueue="
                + renderer.meshQueue()
                + " lastBatch="
                + lastBatch
                + " publishGate="
                + com.github.xandergos.terraindiffusionmc.client.ClientPerformance.publishGateReason()
                + " gateOpen10/30/60s="
                + gateTracker.openRatio10()
                + "/"
                + gateTracker.openRatio30()
                + "/"
                + gateTracker.openRatio60()
                + " publicationBudget="
                + budget.allowance()
                + " "
                + renderer.waiting.statistics()
                + " "
                + renderer.timings();
    }

    /** Number of patch keys whose rectangle intersects the radius disc. Bounded (a few
     * dozen even at the 2048-chunk horizon); called only from status lines. */
    static long patchDemand(int x, int z, int radius, int span) {
        if (radius < 64) return 1;
        long total = 0;
        for (int pz = Math.floorDiv(z - radius, span); pz <= Math.floorDiv(z + radius, span); pz++)
            for (int px = Math.floorDiv(x - radius, span); px <= Math.floorDiv(x + radius, span); px++) {
                long dx = Math.max(0, Math.max((long) px * span - x, x - ((long) px + 1) * span));
                long dz = Math.max(0, Math.max((long) pz * span - z, z - ((long) pz + 1) * span));
                if ((long) dx * dx + dz * dz <= (long) radius * radius) total++;
            }
        return total;
    }

    private void notifyReady() throws Exception {
        if (!ClientPerformance.mayPublish()) return;
        int limit = budget.quota(ClientPerformance.FRAMES.pressure(), renderer.meshQueue());
        lastBatch = 0;
        var ready = renderer.waiting.select(limit, renderer::within, renderer::readyForQueue);
        if (ready.isEmpty()) return;
        try {
            var bound = client.submit(() -> {
                        if (!current() || !ClientPerformance.mayPublish()) return null;
                        try {
                            return api.acquire(world);
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .get();
            if (bound == null) return;
            try (bound) {
                if (!bound.active()) return;
                long started = System.nanoTime();
                for (var task : ready) {
                    if (!current() || !ClientPerformance.mayPublish()) break;
                    if (renderer.meshQueue() >= 512 || lastBatch > 0 && System.nanoTime() - started >= 2_000_000L)
                        break;
                    if (renderer.waiting.complete(task)) {
                        renderer.invalidate(task.key());
                        notifications++;
                        lastBatch++;
                    }
                }
            }
        } finally {
            renderer.waiting.retryUrgent(ready);
        }
    }

    private void run() {
        try {
            produce();
        } finally {
            close();
            // produce preserves interruption; clear it while draining the detached provider.
            boolean interrupted = Thread.interrupted();
            try {
                renderer.drainRetired();
            } catch (InterruptedException stopped) {
                interrupted = true;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private void produce() {
        CoarseLodPatch.Key target = null;
        while (current())
            try {
                if (!renderer.active()) {
                    problem = "renderer unavailable";
                    close();
                    return;
                }
                // Count closed publication cycles before forking.
                boolean gateOpen = ClientPerformance.mayPublish();
                gateTracker.sample(gateOpen);
                TimeUnit.MILLISECONDS.sleep(50);
                if (gateOpen) {
                    guard.prepareRenderRegions(32);
                    renderer.prepareStoredColumns();
                    applyAnchors();
                    notifyReady();
                }
                if (radius < 64 || !allowed.getAsBoolean()) continue;
                // Re-evaluate the nearest unfinished patch after movement. No huge job queue.
                // One native root of read-only halo supplies border faces, not extra visible range.
                var next = cache.next(x, z, radius + 512);
                if (next == null) continue;
                target = next;
                if (!ticket.claimBackgroundStep()) continue;
                int i0 = target.z() * CoarseLodPatch.SIDE - CoarseLodPatch.HALO,
                        j0 = target.x() * CoarseLodPatch.SIDE - CoarseLodPatch.HALO;
                // Each window yields to foreground. A cold 68-square slice at origin
                // intersects nine windows; neighboring patches can share cached windows.
                for (int guard = 0; guard < CoarseLodPatch.GRID; guard++) {
                    int step = LocalTerrainProvider.prepareCoarseLodWindowStep(
                            i0,
                            j0,
                            i0 + CoarseLodPatch.GRID,
                            j0 + CoarseLodPatch.GRID,
                            ticket,
                            () -> current() && allowed.getAsBoolean());
                    if (step <= 0) break; // slice complete (0) or deferred (-1): yield the claim
                    windows++;
                    steps++;
                }
                var result = LocalTerrainProvider.prepareCoarseLodStep(
                        i0,
                        j0,
                        i0 + CoarseLodPatch.GRID,
                        j0 + CoarseLodPatch.GRID,
                        ticket,
                        () -> current() && allowed.getAsBoolean());
                steps++;
                if (result != null && current())
                    cache.put(new CoarseLodPatch(
                            target,
                            scale,
                            compression,
                            ticket.worldSeed(),
                            WorldHeightProfile.surfaceCapYForScale(scale),
                            result));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                close();
                return;
            } catch (Exception | LinkageError failure) {
                problem = failure.getClass().getSimpleName();
                DiagnosticErrors.emit(
                        DiagnosticErrors.Code.VOX_COARSE,
                        DiagnosticErrors.current("VOXY_COARSE_STEP"),
                        failure,
                        DiagnosticErrors.Outcome.DEGRADED,
                        "Experimental coarse preview stopped; hooks detached; storage untouched",
                        false);
                if (current())
                    org.slf4j.LoggerFactory.getLogger(CoarseLodController.class).warn("Coarse LOD stopped", failure);
                close();
                return;
            }
    }

    private void applyAnchors() {
        int processed = 0;
        for (var entry : anchors.entrySet()) {
            var anchor = entry.getValue();
            if (cache.at(anchor.x, anchor.z) == null) continue;
            var samples = new ArrayList<CoarseLodCorrections.Anchor>(9);
            int n = 0;
            for (int z : new int[] {0, anchor.middle, anchor.edge})
                for (int x : new int[] {0, anchor.middle, anchor.edge}) {
                    var base = cache.surface(anchor.x + x, anchor.z + z);
                    if (base != null && anchor.heights[n] != Integer.MIN_VALUE)
                        samples.add(new CoarseLodCorrections.Anchor(
                                anchor.x + x, anchor.z + z, anchor.heights[n] - base.firstAir()));
                    n++;
                }
            corrections.add(samples);
            anchors.remove(entry.getKey(), anchor);
            renderer.correctionChanged(anchor.x, anchor.z);
            if (++processed >= 4) break;
        }
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        renderer.retire();
    }
}

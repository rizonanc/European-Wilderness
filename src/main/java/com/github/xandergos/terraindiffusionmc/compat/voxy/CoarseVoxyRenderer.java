package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.common.world.*;
import me.cortex.voxy.common.world.other.Mapper;

/** Renderer-only sections. Nothing here is inserted into WorldEngine or saved.
 * Voxy owns/releases returned untracked sections exactly like its normal reads. */
public final class CoarseVoxyRenderer implements CoarseRenderHooks.Provider {
    private static final int MAX_WAITING = 262144;

    private record Column(int level, int x, int z) {}

    private final Object engine;
    private final CoarseLodCache cache;
    private final CoarseCoverage coverage;
    private final LodChunkGuard guard;
    private final BooleanSupplier current;
    private final CoarseLodCorrections corrections;
    private final Map<Short, long[]> palettes;
    private final int bottom, top;
    private final LinkedHashMap<Column, CoarseLodPatch.Surface[]> fields = new LinkedHashMap<>(512, .75f, true);
    private final CoarseVirtualHistory virtual = new CoarseVirtualHistory();
    private final CoarseMeshTracker meshes = new CoarseMeshTracker(MAX_WAITING);
    private final CoarseStoredColumns storedColumns = new CoarseStoredColumns();
    final CoarseUpdateQueue waiting = new CoarseUpdateQueue(MAX_WAITING, 8192);
    private volatile RenderGenerationService service;
    final LongAdder built = new LongAdder(), errors = new LongAdder(), dropped = new LongAdder();
    // Counts of deferred coarse sections by cause.
    final LongAdder deferredNoField = new LongAdder(),
            deferredRegion = new LongAdder(),
            protectedExact = new LongAdder(),
            outsideRadius = new LongAdder();
    // Counts of ready and deferred sections.
    final LongAdder readyProbes = new LongAdder(), readyHits = new LongAdder();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> exactReasons =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean failed, retired;
    private final com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings timings =
            new com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings();
    private volatile int centerX, centerZ, radius;

    public CoarseVoxyRenderer(
            Object engine,
            CoarseLodCache cache,
            CoarseCoverage coverage,
            LodChunkGuard guard,
            BooleanSupplier current,
            Map<Short, long[]> palettes,
            int bottom,
            int top,
            CoarseLodCorrections corrections) {
        this.engine = engine;
        this.cache = cache;
        this.coverage = coverage;
        this.guard = guard;
        this.current = current;
        this.palettes = palettes;
        this.bottom = bottom;
        this.top = top;
        this.corrections = corrections;
    }

    public boolean active() {
        return !failed && !retired && current.getAsBoolean();
    }

    public boolean matches(Object engine) {
        return this.engine == engine && active();
    }

    public void observeService(Object engine, Object service) {
        if (matches(engine) && service instanceof RenderGenerationService renderer) this.service = renderer;
    }

    public int meshQueue() {
        var renderer = service;
        return renderer == null ? -1 : Math.max(0, renderer.getTaskCount());
    }

    public String timings() {
        return timings
                + " "
                + meshes.statistics()
                + " "
                + storedColumns.statistics()
                + " readyProbes="
                + readyProbes.sum()
                + " readyHits="
                + readyHits.sum()
                + " exactReasons="
                + exactReasons;
    }

    public boolean meshCurrent(long position, long revision) {
        return active() && meshes.current(position, revision);
    }

    public void staleMesh() {
        meshes.stale();
    }

    public String describe(Object engine, long position) {
        if (!matches(engine)) return "coarse provider: inactive/different engine";
        int side = 32 << WorldEngine.getLevel(position),
                x = WorldEngine.getX(position) * side,
                z = WorldEngine.getZ(position) * side;
        var sample = cache.surface(x + side / 2, z + side / 2);
        int wx = x + side / 2, wz = z + side / 2;
        double residual = corrections.adjustment(wx, wz);
        return "sectionId="
                + Long.toUnsignedString(position)
                + " L"
                + WorldEngine.getLevel(position)
                + " worldBoundsXZ="
                + x
                + ","
                + z
                + ".."
                + (x + side - 1)
                + ","
                + (z + side - 1)
                + " source=TD_COARSE_RENDER_ONLY"
                + " centerXZ="
                + wx
                + ","
                + wz
                + " coarse={within="
                + within(position)
                + ", haloReady="
                + ready(position)
                + ", centerProtected="
                + exact(x + side / 2, z + side / 2)
                + ", centerFirstAir="
                + (sample == null ? "unprepared" : sample.firstAir())
                + ", correction="
                + residual
                + ", correctedFirstAir="
                + (sample == null ? "UNKNOWN" : Math.round(sample.firstAir() + residual))
                + ", knownReal="
                + guard.knownRealColumn(wx, wz)
                + ", savedReal="
                + guard.savedRealColumn(wx, wz)
                + ", previouslyVirtual="
                + virtual.contains(position)
                + ", meshQueue="
                + meshQueue()
                + "}; "
                + meshes.describe(position)
                + "; meshRevision=UNKNOWN parentMeshRevision=UNKNOWN residentMesh=UNKNOWN pixelAttribution=UNKNOWN";
    }

    public void range(int x, int z, int radius) {
        centerX = x;
        centerZ = z;
        this.radius = radius;
    }

    /** Explicit worker-side probe; stored voxels and predicted virtual voxels remain distinct. */
    public String describePoint(Object engine, long key, int wx, int wy, int wz) {
        String description = describe(engine, key);
        if (!matches(engine)) return description;
        int l = WorldEngine.getLevel(key),
                sx = WorldEngine.getX(key),
                sy = WorldEngine.getY(key),
                sz = WorldEngine.getZ(key),
                unit = 1 << l,
                side = 32 * unit;
        if (Math.floorDiv(wx, side) != sx || Math.floorDiv(wy, side) != sy || Math.floorDiv(wz, side) != sz)
            throw new IllegalArgumentException("Point outside section");
        int x = Math.floorMod(wx >> l, 32),
                y = Math.floorMod(wy >> l, 32),
                z = Math.floorMod(wz >> l, 32),
                index = (y << 10) | (z << 5) | x;
        int sampleX = sx * side + x * unit + unit / 2, sampleZ = sz * side + z * unit + unit / 2;
        var field = field(l, sx, sz);
        var point = cache.surface(sampleX, sampleZ);
        String suffix = "; requestedXYZ="
                + wx
                + ","
                + wy
                + ","
                + wz
                + " sampledXZ="
                + sampleX
                + ","
                + sampleZ
                + " pointFirstAir="
                + (point == null ? "UNKNOWN" : point.firstAir())
                + " pointCorrection="
                + corrections.adjustment(sampleX, sampleZ)
                + " pointOwned="
                + exact(sampleX, sampleZ)
                + " ownership="
                + exactReason(sampleX, sampleZ)
                + " exactTerrainHeight=UNKNOWN";
        if (field == null || !ownershipReady(sampleX, sampleZ))
            return description + suffix + " virtualExpected=UNKNOWN";
        var world = (WorldEngine) engine;
        var original = world.acquireIfExists(key);
        try {
            long[] stored = original == null ? null : original.copyData(), expected = new long[32768];
            CoarseSectionComposer.fill(
                    l,
                    sx,
                    sy,
                    sz,
                    bottom,
                    top,
                    field,
                    stored,
                    expected,
                    this::exact,
                    biome -> palettes.getOrDefault((short) biome, palettes.get((short) 229)),
                    Mapper::isAir,
                    corrections::adjustment);
            return description
                    + suffix
                    + " engineVoxelId="
                    + (stored == null ? "ABSENT" : Mapper.getBlockId(stored[index]))
                    + " virtualExpectedVoxelId="
                    + Mapper.getBlockId(expected[index])
                    + " (CPU prediction, not a GPU mesh read)";
        } finally {
            if (original != null) original.release();
        }
    }

    public boolean within(long position) {
        return within(position, 0);
    }

    private boolean within(long position, int halo) {
        int level = WorldEngine.getLevel(position);
        if (level < 0 || level > 4 || radius < 64) return false;
        int side = 32 << level;
        long x = (long) WorldEngine.getX(position) * side, z = (long) WorldEngine.getZ(position) * side;
        long dx = Math.max(0, Math.max(x - centerX, centerX - (x + side))),
                dz = Math.max(0, Math.max(z - centerZ, centerZ - (z + side)));
        long reach = (long) radius + halo;
        return dx * dx + dz * dz <= reach * reach;
    }

    public Object render(Object engine, long position, Object original) {
        return render(engine, position, original, false);
    }

    public Object neighbor(Object engine, long position, Object original) {
        return render(engine, position, original, true);
    }

    private Object render(Object engine, long position, Object original, boolean neighbor) {
        var DBG = System.getProperty("td.coarse.trace") != null;
        if (DBG)
            System.err.println("coarse render L"
                    + WorldEngine.getLevel(position)
                    + " at "
                    + WorldEngine.getX(position)
                    + ","
                    + WorldEngine.getZ(position)
                    + " failed="
                    + failed
                    + " retired="
                    + retired);
        if (!matches(engine)) return original;
        long started = System.nanoTime();
        try {
            long revision = neighbor ? 0 : meshes.request(position);
            if (!neighbor && revision < 0) {
                defer(position);
                return original;
            }
            var copy = compose(position, (WorldSection) original, neighbor);
            if (copy == original) return original;
            virtual.mark(position);
            if (!neighbor) {
                meshes.built(position);
                CoarseRenderHooks.tag(copy, this, position, revision);
            }
            built.increment();
            return copy;
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            errors.increment();
            com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.emit(
                    com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.Code.VOX_COARSE,
                    com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.current(
                            "VOXY_COARSE_RENDER"),
                    failure,
                    com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.Outcome.DEGRADED,
                    "Experimental coarse fallback disabled; original sections returned; storage untouched",
                    false);
            org.slf4j.LoggerFactory.getLogger(CoarseVoxyRenderer.class)
                    .warn("Coarse render fallback disabled", failure);
            return original;
        } finally {
            timings.record("meshRead", System.nanoTime() - started);
        }
    }

    public byte children(Object engine, Object original, byte actual) {
        if (!matches(engine)) return actual;
        long started = System.nanoTime();
        try {
            WorldSection source = (WorldSection) original, copy = compose(source.key, source, false);
            if (copy == source) return actual;
            try {
                return copy.getNonEmptyChildren();
            } finally {
                copy.release();
            }
        } catch (RuntimeException | LinkageError failure) {
            failed = true;
            errors.increment();
            com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.emit(
                    com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.Code.VOX_COARSE,
                    com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.current(
                            "VOXY_COARSE_CHILDREN"),
                    failure,
                    com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.Outcome.DEGRADED,
                    "Experimental coarse fallback disabled; original child mask retained; storage untouched",
                    false);
            return actual;
        } finally {
            timings.record("childRead", System.nanoTime() - started);
        }
    }

    /** Only stored voxels establish coarse ownership. */
    private boolean exact(int x, int z) {
        var decision = CoarseOwnership.decide(storedColumns.knownContains(x, z));
        if (decision != CoarseOwnership.Decision.OPEN) exactReasons.merge(decision.name(), 1L, Long::sum);
        return CoarseOwnership.exact(decision);
    }

    /** Which ownership evidence protected a column — surfaced through describePoint
     * (reached by /td-voxy inspect-at); kept out of hot paths. */
    public String exactReason(int x, int z) {
        var decision = CoarseOwnership.decide(storedColumns.knownContains(x, z));
        return decision
                + (decision == CoarseOwnership.Decision.EXACT_STORED
                        ? " (stored probe: real data in storage)"
                        : " (no stored data: neural preview drawn; knownReal="
                                + guard.knownRealColumn(x, z)
                                + " savedReal="
                                + guard.savedRealColumn(x, z)
                                + " coverage="
                                + coverage.contains(Math.floorDiv(x, 16), Math.floorDiv(z, 16))
                                + ")");
    }

    private boolean ownershipReady(int x, int z) {
        return guard.renderRegionReady(x, z)
                && (!guard.savedRenderRegion(x, z) || guard.uncertainRenderRegion(x, z) || storedColumns.ready(x, z));
    }

    /** Existing exact mip data only; approximate renderer copies never enter this reader. */
    void prepareStoredColumns() {
        var world = (WorldEngine) engine;
        if (!active() || !world.isLive()) return;
        storedColumns.prepare(
                2,
                bottom,
                top,
                (x, y, z) -> {
                    var section = world.acquireIfExists(4, x, y, z);
                    if (section == null) return null;
                    try {
                        return section.copyData();
                    } finally {
                        section.release();
                    }
                },
                Mapper::isAir);
    }

    /** Invalidate the chunk storage region probe after ingest. */
    void invalidateStoredColumns(int chunkX, int chunkZ) {
        storedColumns.invalidate(chunkX * 16, chunkZ * 16);
    }

    private void defer(long position) {
        if (!waiting.offer(position, false)) dropped.increment();
    }

    private CoarseLodPatch.Surface[] field(int level, int x, int z) {
        int unit = 1 << level, side = 32 * unit;
        var key = new Column(level, x, z);
        synchronized (fields) {
            var ready = fields.get(key);
            if (ready != null) return ready;
        }
        if (cache.at(x * side, z * side) == null || cache.at(x * side + side - 1, z * side + side - 1) == null)
            return null;
        var values = new CoarseLodPatch.Surface[1024];
        for (int dz = 0; dz < 32; dz++)
            for (int dx = 0; dx < 32; dx++) {
                int wx = x * side + dx * unit + unit / 2, wz = z * side + dz * unit + unit / 2;
                values[dz * 32 + dx] = cache.surface(wx, wz);
                if (values[dz * 32 + dx] == null) return null;
            }
        synchronized (fields) {
            fields.put(key, values);
            while (fields.size() > 512) fields.remove(fields.keySet().iterator().next());
        }
        return values;
    }

    private WorldSection compose(long position, WorldSection original, boolean neighbor) {
        var DBG = System.getProperty("td.coarse.trace") != null;
        if (DBG)
            System.err.println("compose L"
                    + WorldEngine.getLevel(position)
                    + " enter original="
                    + (original != null)
                    + " children="
                    + (original == null ? "-" : original.getNonEmptyChildren()));
        if (!within(position, neighbor ? 32 << WorldEngine.getLevel(position) : 0)) {
            if (DBG) System.err.println(" -> outsideRadius");
            outsideRadius.increment();
            return original;
        }
        int level = WorldEngine.getLevel(position),
                x = WorldEngine.getX(position),
                y = WorldEngine.getY(position),
                z = WorldEngine.getZ(position);
        if (level < 0 || level > 4) return original;
        int side = 32 << level, unit = 1 << level;
        if ((long) y * side > top || ((long) y + 1) * side <= bottom) return original;
        if (!ownershipReady(x * side, z * side)) {
            if (DBG) System.err.println(" -> deferredRegion");
            deferredRegion.increment();
            defer(position);
            return original;
        }
        if (original != null && original.getNonEmptyChildren() != 0 && !storedColumns.ready(x * side, z * side)) {
            if (DBG) System.err.println(" -> storedNotReady");
            deferredRegion.increment();
            defer(position);
            return original;
        }
        if (CoarseSectionComposer.fullyProtected(level, x, z, this::exact)) {
            if (DBG) System.err.println(" -> fullyProtected");
            protectedExact.increment();
            return original;
        }
        // The stock mesher exposes every solid side against air. Never publish a new
        // neural column until its horizontal read halo is available at the same LOD.
        if (!neighbor && !ready(position)) {
            if (DBG) System.err.println(" -> notReadyHalo");
            defer(position);
            return original;
        }
        var field = field(level, x, z);
        if (field == null) {
            if (DBG) System.err.println(" -> deferredNoField");
            deferredNoField.increment();
            defer(position);
            return original;
        }
        // Keep loaded chunks intact while Voxy ingest is pending.
        CoarseSectionComposer.Protected preserveOriginal = null;
        if (original != null
                && (guard.knownRealColumn(x * side + 8, z * side + 8)
                        || guard.savedRealColumn(x * side + 8, z * side + 8)
                                && storedColumns.contains(x * side + 8, z * side + 8))) {
            int sectionX = x * side, sectionZ = z * side;
            long[] sourceData = original._unsafeGetRawDataArray();
            preserveOriginal = (wx, wz) -> {
                int lx = wx - sectionX, lz = wz - sectionZ, p = (lz / unit) * 32 + lx / unit;
                for (int vy = 0; vy < 32; vy++) if (sourceData[(vy << 10) + p] != 0L) return true;
                return false;
            };
        }
        var copy = WorldSection._createRawUntrackedUnsafeSection(level, x, y, z);
        copy.acquire();
        boolean success = false;
        try {
            long[] target = copy._unsafeGetRawDataArray();
            if (original != null) original.copyDataTo(target);
            var result = CoarseSectionComposer.fill(
                    level,
                    x,
                    y,
                    z,
                    bottom,
                    top,
                    field,
                    original == null ? null : target,
                    target,
                    this::exact,
                    biome -> palettes.getOrDefault((short) biome, palettes.get((short) 229)),
                    Mapper::isAir,
                    corrections::adjustment,
                    preserveOriginal);
            byte children = result.mergeChildren(original == null ? 0 : original.getNonEmptyChildren());
            if (original == null && !result.changed() && children == 0) return null;
            if (!result.changed() && original != null && children == original.getNonEmptyChildren()) {
                if (DBG)
                    System.err.println(" -> unchanged children="
                            + children
                            + " origChildren="
                            + original.getNonEmptyChildren()
                            + " nonAir="
                            + result.nonAir());
                return original;
            }
            if (level == 0) copy.addNonEmptyBlockCount(result.nonAir());
            copy._unsafeSetNonEmptyChildren(children);
            success = true;
            return copy;
        } finally {
            if (!success) copy.release();
        }
    }

    /** Only notifies render watchers; source data and save flags are untouched. */
    public void invalidate(long position) {
        var world = (WorldEngine) engine;
        if (!matches(world) || !world.isLive()) return;
        var section = world.acquire(position);
        try {
            world.markDirty(section, WorldEngine.DEFAULT_UPDATE_FLAGS | WorldEngine.UPDATE_TYPE_DONT_SAVE, 63);
        } finally {
            section.release();
        }
        meshes.notified(position);
    }

    /** Halo readiness. Uncounted: also used by compose() and describe probes. */
    public boolean ready(long position) {
        int side = 32 << WorldEngine.getLevel(position),
                x = WorldEngine.getX(position) * side,
                z = WorldEngine.getZ(position) * side;
        boolean ready = columnReady(x, z, side);
        ready &= columnReady(x - side, z, side);
        ready &= columnReady(x + side, z, side);
        ready &= columnReady(x, z - side, side);
        ready &= columnReady(x, z + side, side);
        return ready;
    }

    /** Queue-candidate variant: only these probes count as publication readiness. */
    public boolean readyForQueue(long position) {
        readyProbes.increment();
        boolean ready = ready(position);
        if (ready) readyHits.increment();
        return ready;
    }

    private boolean columnReady(int x, int z, int side) {
        boolean ownership = ownershipReady(x, z);
        return ownership
                && cache.at(x, z) != null
                && cache.at(x + side - 1, z) != null
                && cache.at(x, z + side - 1) != null
                && cache.at(x + side - 1, z + side - 1) != null;
    }

    /** Coverage changes must also rebuild virtual sky ABOVE the exact surface.
     * Such sky never existed in WorldEngine, so ordinary ingest cannot dirty it. */
    public void exactChanged(int cellX, int cellZ) {
        invalidateArea(cellX * 32, cellZ * 32, cellX * 32 + 31, cellZ * 32 + 31);
    }

    public void correctionChanged(int x, int z) {
        int halo = CoarseLodCorrections.RADIUS + CoarseLodCorrections.STEP;
        invalidateArea(x - halo, z - halo, x + 31 + halo, z + 31 + halo);
    }

    /** Neighbor primary meshes also depend on this column's faces. */
    void invalidateArea(int minX, int minZ, int maxX, int maxZ) {
        for (int level = 0; level <= 4; level++) {
            int side = 32 << level;
            for (int sz = Math.floorDiv(minZ, side) - 1; sz <= Math.floorDiv(maxZ, side) + 1; sz++)
                for (int sx = Math.floorDiv(minX, side) - 1; sx <= Math.floorDiv(maxX, side) + 1; sx++)
                    for (int sy = Math.floorDiv(bottom, side); sy <= Math.floorDiv(top, side); sy++) {
                        long key = WorldEngine.getWorldSectionId(level, sx, sy, sz);
                        meshes.invalidate(key);
                        if (virtual.contains(key) && !waiting.offer(key, true)) dropped.increment();
                    }
        }
    }

    void retire() {
        retired = true;
        meshes.retire();
        CoarseRenderHooks.detach(this);
    }

    /** The controller worker drains this AFTER detach, independently of its stopped normal queue. */
    void drainRetired() throws InterruptedException {
        if (!(engine instanceof WorldEngine world) || !world.isLive()) return;
        var renderer = service;
        if (renderer == null) return;
        long[] keys = meshes.virtualKeys();
        int index = 0;
        while (index < keys.length && world.isLive()) {
            if (!com.github.xandergos.terraindiffusionmc.client.ClientPerformance.mayPublish()
                    || renderer.getTaskCount() >= 512) {
                Thread.sleep(50);
                continue;
            }
            int end = Math.min(index + 32, keys.length);
            while (index < end && world.isLive()) renderer.enqueueTask(keys[index++]);
            Thread.sleep(50);
        }
    }
}

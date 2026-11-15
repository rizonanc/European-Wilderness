package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.WorkTimeline.Stage.*;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.block.*;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import java.lang.reflect.*;
import java.lang.invoke.*;
import java.util.*;

/** Calls the public 0.2.16 interfaces documented in the author's published 12111 source.
 * Reflection isolates an optional, version-pinned dependency; no Voxy classes are bundled. */
public final class Voxy016Api {
    /** GPU capabilities and the instance factory must exist before VoxyConfig is initialized. */
    public static boolean runtimeReady() throws ReflectiveOperationException {
        return (boolean) Class.forName("me.cortex.voxy.commonImpl.VoxyCommon").getMethod("isAvailable").invoke(null);
    }

    private final Method instance, worldId, nullable, enabled, live, acquire, release, mapper, exists, sectionRelease;
    private final Method create, position, mip, insert, blockId, biomeId, compose, reingest;
    private final Method children, nonEmptyBlocks, childIndex;
    private final Method copySection, rawSection, markDirty;
    private final MethodHandle stockMip;
    private final int blockUpdateBit;
    private final Field data, nonAir;
    private final Field config, renderDistance, configEnabled, configRendering;
    private EncoderState encoder;
    private final Object encodingLock = new Object();
    private final com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings timings =
            new com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings();

    public String statistics() {
        return timings.toString();
    }

    public synchronized void clearCache() {
        encoder = null;
    }

    private synchronized EncoderState encoder(Object engine, Object mapper) throws ReflectiveOperationException {
        if (encoder == null || encoder.engine != engine)
            encoder = new EncoderState(engine, mapper, create.invoke(null));
        return encoder;
    }

    private static final class EncoderState {
        final Object engine, map, section;
        final Map<Short, long[][]> palette = new HashMap<>();
        long[] stockMips;
        LodColumn column;
        LodVoxelEncoder voxels;

        EncoderState(Object engine, Object map, Object section) {
            this.engine = engine;
            this.map = map;
            this.section = section;
        }
    }

    private final Object[] states = {
        Blocks.AIR.getDefaultState(),
        Blocks.STONE.getDefaultState(),
        Blocks.DIRT.getDefaultState(),
        Blocks.GRASS_BLOCK.getDefaultState(),
        Blocks.SAND.getDefaultState(),
        Blocks.SNOW_BLOCK.getDefaultState(),
        Blocks.WATER.getDefaultState(),
        Blocks.OAK_LOG.getDefaultState(),
        Blocks.BIRCH_LOG.getDefaultState(),
        Blocks.SPRUCE_LOG.getDefaultState(),
        Blocks.OAK_LEAVES.getDefaultState(),
        Blocks.BIRCH_LEAVES.getDefaultState(),
        Blocks.SPRUCE_LEAVES.getDefaultState()
    };

    public Voxy016Api() throws ReflectiveOperationException {
        var configuration = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
        config = configuration.getField("CONFIG");
        renderDistance = configuration.getField("sectionRenderDistance");
        configEnabled = configuration.getField("enabled");
        configRendering = configuration.getField("enableRendering");
        var common = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
        var id = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
        var inst = Class.forName("me.cortex.voxy.commonImpl.VoxyInstance");
        var engine = Class.forName("me.cortex.voxy.common.world.WorldEngine");
        var section = Class.forName("me.cortex.voxy.common.voxelization.VoxelizedSection");
        var map = Class.forName("me.cortex.voxy.common.world.other.Mapper");
        instance = common.getMethod("getInstance");
        worldId = id.getMethod("of", World.class);
        nullable = id.getMethod("getNullable");
        enabled = inst.getMethod("isIngestEnabled", id);
        live = engine.getMethod("isLive");
        acquire = engine.getMethod("acquireRef");
        release = engine.getMethod("releaseRef");
        mapper = engine.getMethod("getMapper");
        exists = engine.getMethod("acquireIfExists", int.class, int.class, int.class, int.class);
        sectionRelease = Class.forName("me.cortex.voxy.common.world.WorldSection").getMethod("release");
        var worldSection = Class.forName("me.cortex.voxy.common.world.WorldSection");
        children = worldSection.getMethod("getNonEmptyChildren");
        copySection = worldSection.getMethod("copyData");
        rawSection = worldSection.getMethod("_unsafeGetRawDataArray");
        stockMip =
                MethodHandles.publicLookup()
                        .unreflect(
                                Class.forName("me.cortex.voxy.common.world.other.Mipper")
                                        .getMethod(
                                                "mip",
                                                long.class,
                                                long.class,
                                                long.class,
                                                long.class,
                                                long.class,
                                                long.class,
                                                long.class,
                                                long.class,
                                                map))
                        .asType(
                                MethodType.methodType(
                                        long.class,
                                        long.class,
                                        long.class,
                                        long.class,
                                        long.class,
                                        long.class,
                                        long.class,
                                        long.class,
                                        long.class,
                                        Object.class));
        markDirty = engine.getMethod("markDirty", worldSection, int.class, int.class);
        blockUpdateBit = engine.getField("UPDATE_TYPE_BLOCK_BIT").getInt(null);
        nonEmptyBlocks = worldSection.getMethod("getNonEmptyBlockCount");
        childIndex = worldSection.getMethod("getChildIndex", int.class, int.class, int.class);
        create = section.getMethod("createEmpty");
        position = section.getMethod("setPosition", int.class, int.class, int.class);
        data = section.getField("section");
        nonAir = section.getField("lvl0NonAirCount");
        mip =
                Class.forName("me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper")
                        .getMethod("mipSection", section, map);
        insert = Class.forName("me.cortex.voxy.common.world.WorldUpdater").getMethod("insertUpdate", engine, section);
        blockId = map.getMethod("getIdForBlockState", BlockState.class);
        biomeId = map.getMethod("getIdForBiome", RegistryEntry.class);
        compose = map.getMethod("composeMappingId", byte.class, int.class, int.class);
        reingest =
                Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService")
                        .getMethod("tryAutoIngestChunk", WorldChunk.class);
    }

    /** Must run on the client thread before handing the pinned engine to a worker. */
    public boolean available(ClientWorld world) throws ReflectiveOperationException {
        Object settings = config.get(null);
        if (!configEnabled.getBoolean(settings) || !configRendering.getBoolean(settings)) return false;
        Object owner = instance.invoke(null);
        if (owner == null) return false;
        Object id = worldId.invoke(null, world);
        if (id == null || !(boolean) enabled.invoke(owner, id)) return false;
        Object engine = nullable.invoke(id);
        return engine != null && (boolean) live.invoke(engine);
    }

    public Bound acquire(ClientWorld world) throws ReflectiveOperationException {
        Object owner = instance.invoke(null), id = worldId.invoke(null, world);
        if (owner == null || id == null || !(boolean) enabled.invoke(owner, id)) return null;
        Object engine = nullable.invoke(id);
        if (engine == null || !(boolean) live.invoke(engine)) return null;
        acquire.invoke(engine);
        try {
            return new Bound(engine, owner);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            release.invoke(engine);
            throw failure;
        }
    }

    public void reingest(WorldChunk chunk) throws ReflectiveOperationException {
        reingest.invoke(null, chunk);
    }

    /** Client-thread identity check also catches renderer/engine reloads between ticks. */
    public Object engineIdentity(ClientWorld world) throws ReflectiveOperationException {
        Object id = worldId.invoke(null, world);
        return id == null ? null : nullable.invoke(id);
    }

    public int horizonBlocks() throws ReflectiveOperationException {
        return horizonBlocks(renderDistance.getFloat(config.get(null)));
    }

    public static int horizonBlocks(double sections) {
        return Double.isFinite(sections) ? (int) Math.max(64, Math.min(32768, Math.ceil(sections * 512))) : 1024;
    }

    public final class Bound implements AutoCloseable {
        private final Object engine, owner;
        private final EncoderState state;
        private final WaterMipRepairData.Mipper stockReducer = this::stockMip;

        private long stockMip(long a, long b, long c, long d, long e, long f, long g, long h) throws Exception {
            try {
                return (long) Voxy016Api.this.stockMip.invokeExact(a, b, c, d, e, f, g, h, state.map);
            } catch (Throwable failure) {
                if (failure instanceof Error error) throw error;
                throw new ReflectiveOperationException("Pinned stock mipper failed", failure);
            }
        }

        Bound(Object engine, Object owner) throws ReflectiveOperationException {
            this.engine = engine;
            this.owner = owner;
            state = encoder(engine, mapper.invoke(engine));
        }

        public boolean active() throws ReflectiveOperationException {
            return owner == instance.invoke(null) && (boolean) live.invoke(engine);
        }

        public Object identity() {
            return engine;
        }

        /** Rebuild only derived LOD cells from an existing base cube. Never writes level 0,
         * creates terrain, changes child masks, or relies on insertUpdate's existing-base shortcut. */
        public int repairWaterCube(int x32, int y32, int z32) throws Exception {
            if (!(state.map instanceof WaterMipMapping mapping))
                throw new IllegalStateException("Voxy water mip compatibility hooks are not active");
            Object source = exists.invoke(engine, 0, x32, y32, z32);
            if (source == null) return 0;
            long[] original;
            try {
                original = (long[]) copySection.invoke(source);
            } finally {
                sectionRelease.invoke(source);
            }
            boolean wet = false;
            for (long value : original)
                if (WaterMipPolicy.blockId(value) != 0 && mapping.ewh$isWater(WaterMipPolicy.blockId(value))) {
                    wet = true;
                    break;
                }
            if (!wet) return 0;
            int changed = 0;
            synchronized (encodingLock) {
                Object section = state.section;
                long[] target = (long[]) data.get(section);
                if (state.stockMips == null) state.stockMips = new long[target.length];
                for (int by = 0; by < 2; by++)
                    for (int bz = 0; bz < 2; bz++)
                        for (int bx = 0; bx < 2; bx++) {
                            int cx = x32 * 2 + bx, cy = y32 * 2 + by, cz = z32 * 2 + bz;
                            position.invoke(section, cx, cy, cz);
                            for (int y = 0; y < 16; y++)
                                for (int z = 0; z < 16; z++)
                                    for (int x = 0; x < 16; x++)
                                        target[y * 256 + z * 16 + x] =
                                                original[(by * 16 + y) * 1024 + (bz * 16 + z) * 32 + bx * 16 + x];
                            System.arraycopy(target, 0, state.stockMips, 0, 4096);
                            WaterMipRepairData.stock(state.stockMips, stockReducer);
                            mip.invoke(null, section, state.map);
                            for (int level = 1; level <= 4; level++) {
                                Object parent =
                                        exists.invoke(
                                                engine, level, cx >> (level + 1), cy >> (level + 1), cz >> (level + 1));
                                if (parent == null) continue;
                                try {
                                    long[] parentData = (long[]) rawSection.invoke(parent);
                                    if (WaterMipRepairData.patch(
                                                    level, cx, cy, cz, parentData, state.stockMips, target, mapping)
                                            != 0) {
                                        markDirty.invoke(engine, parent, blockUpdateBit, 63);
                                        changed++;
                                    }
                                } finally {
                                    sectionRelease.invoke(parent);
                                }
                            }
                        }
            }
            return changed;
        }

        /** Recompute plant-only L1/L2 cells from existing L0; never replace ground. */
        public int repairVegetationCube(int x32, int y32, int z32) throws Exception {
            if (!(state.map instanceof GrassMipMapping mapping))
                throw new IllegalStateException("Grass mip hook is not active");
            Object source = exists.invoke(engine, 0, x32, y32, z32);
            if (source == null) return 0;
            long[] original;
            try {
                original = (long[]) copySection.invoke(source);
            } finally {
                sectionRelease.invoke(source);
            }
            boolean plants = false;
            for (long value : original)
                if (mapping.ewh$isGrass(WaterMipPolicy.blockId(value))) {
                    plants = true;
                    break;
                }
            if (!plants) return 0;
            int changed = 0;
            synchronized (encodingLock) {
                Object section = state.section;
                long[] target = (long[]) data.get(section);
                if (state.stockMips == null) state.stockMips = new long[target.length];
                for (int by = 0; by < 2; by++)
                    for (int bz = 0; bz < 2; bz++)
                        for (int bx = 0; bx < 2; bx++) {
                            int cx = x32 * 2 + bx, cy = y32 * 2 + by, cz = z32 * 2 + bz;
                            position.invoke(section, cx, cy, cz);
                            for (int y = 0; y < 16; y++)
                                for (int z = 0; z < 16; z++)
                                    for (int x = 0; x < 16; x++)
                                        target[y * 256 + z * 16 + x] =
                                                original[(by * 16 + y) * 1024 + (bz * 16 + z) * 32 + bx * 16 + x];
                            System.arraycopy(target, 0, state.stockMips, 0, 4096);
                            WaterMipRepairData.stock(state.stockMips, stockReducer);
                            mip.invoke(null, section, state.map);
                            for (int level = 1; level <= 2; level++) {
                                Object parent =
                                        exists.invoke(
                                                engine, level, cx >> (level + 1), cy >> (level + 1), cz >> (level + 1));
                                if (parent == null) continue;
                                try {
                                    // Caps remain within their source 16-cube. Its fine terrain is
                                    // nonempty; require the same existing child bit before migration.
                                    int bit = (int) childIndex.invoke(null, cx >> level, cy >> level, cz >> level);
                                    if ((Byte.toUnsignedInt((byte) children.invoke(parent)) & (1 << bit)) == 0)
                                        continue;
                                    long[] values = (long[]) rawSection.invoke(parent);
                                    if (GrassMipRepairData.patch(
                                                    level, cx, cy, cz, values, state.stockMips, target, mapping)
                                            != 0) {
                                        markDirty.invoke(engine, parent, blockUpdateBit, 63);
                                        changed++;
                                    }
                                    // Voxy tracks nonempty block counts only for L0. Derived child
                                    // masks describe the existing fine children, not these voxels.
                                } finally {
                                    sectionRelease.invoke(parent);
                                }
                            }
                        }
            }
            return changed;
        }

        /** Register palette ids on the client thread, never on the render workers. */
        public Map<Short, long[]> coarsePalette(TerrainDiffusionBiomeSource biomes)
                throws ReflectiveOperationException {
            Map<Short, long[]> values = new HashMap<>();
            for (short biome : new short[] {0, 5, 29, 32, 35, 208, 209, 215, 229}) {
                int bid = (int) biomeId.invoke(state.map, biomes.recordedBiome(biome));
                long[] ids = new long[states.length];
                for (int k = 0; k < states.length; k++)
                    ids[k] = (long) compose.invoke(null, (byte) 15, (int) blockId.invoke(state.map, states[k]), bid);
                ids[0] = 0;
                values.put(biome, ids);
            }
            return Map.copyOf(values);
        }

        public boolean occupied(int x, int y, int z) throws ReflectiveOperationException {
            Object value = exists.invoke(engine, 0, x, y, z);
            if (value == null) return false;
            sectionRelease.invoke(value);
            return true;
        }

        /** census access acquires sections without marking them dirty. */
        public boolean exists(int level, int x, int y, int z) throws ReflectiveOperationException {
            Object value = exists.invoke(engine, level, x, y, z);
            if (value == null) return false;
            sectionRelease.invoke(value);
            return true;
        }

        public int nonEmptyChildrenMask(int level, int x, int y, int z) throws ReflectiveOperationException {
            Object value = exists.invoke(engine, level, x, y, z);
            if (value == null) return -1;
            try {
                return level == 0
                        ? ((int) nonEmptyBlocks.invoke(value) > 0 ? 1 : 0)
                        : Byte.toUnsignedInt((byte) children.invoke(value));
            } finally {
                sectionRelease.invoke(value);
            }
        }

        public long[] rawCopy(int level, int x, int y, int z) throws ReflectiveOperationException {
            Object value = exists.invoke(engine, level, x, y, z);
            if (value == null) return null;
            try {
                return (long[]) copySection.invoke(value);
            } finally {
                sectionRelease.invoke(value);
            }
        }

        public int highestKnownCube(LodColumn column) throws Exception {
            return highestKnownCube(column.x, column.z, column.bottom, column.top);
        }

        public int highestKnownCube(int worldX, int worldZ, int bottom, int top) throws Exception {
            long start = System.nanoTime();
            try {
                return LodSkyIndex.highest(
                        worldX,
                        worldZ,
                        bottom,
                        top,
                        new LodSkyIndex.Reader() {
                            public int mask(int level, int x, int y, int z) throws Exception {
                                Object section = exists.invoke(engine, level, x, y, z);
                                if (section == null) return -1;
                                try {
                                    return level == 0
                                            ? ((int) nonEmptyBlocks.invoke(section) > 0 ? 1 : 0)
                                            : Byte.toUnsignedInt((byte) children.invoke(section));
                                } finally {
                                    sectionRelease.invoke(section);
                                }
                            }

                            public boolean childPresent(int mask, int x, int y, int z) throws Exception {
                                return (mask & (1 << (int) childIndex.invoke(null, x, y, z))) != 0;
                            }
                        });
            } finally {
                timings.record("skyIndex", System.nanoTime() - start);
            }
        }

        public void cube(LodColumn column, int y32, TerrainDiffusionBiomeSource biomes) throws Exception {
            cube(column, y32, biomes, 15);
        }

        public void cube(LodColumn column, int y32, TerrainDiffusionBiomeSource biomes, int writableMask)
                throws Exception {
            long trace = PerformanceDiagnostics.begin(LOD_CUBE);
            try {
                synchronized (encodingLock) {
                    long started = System.nanoTime();
                    Object section = state.section;
                    long[] target = (long[]) data.get(section);
                    if (state.column != column) {
                        state.voxels =
                                new LodVoxelEncoder(
                                        column,
                                        biome -> {
                                            var values = state.palette.get(biome);
                                            if (values != null) return values;
                                            values = new long[states.length][2];
                                            int bid = (int) biomeId.invoke(state.map, biomes.recordedBiome(biome));
                                            for (int k = 0; k < states.length; k++) {
                                                int block = (int) blockId.invoke(state.map, states[k]);
                                                for (int light = 0; light < 2; light++)
                                                    values[k][light] =
                                                            (long)
                                                                    compose.invoke(
                                                                            null,
                                                                            (byte) (light == 0 ? 0 : 15),
                                                                            block,
                                                                            bid);
                                            }
                                            state.palette.put(biome, values);
                                            return values;
                                        });
                        state.column = column;
                    }
                    for (int by = 0; by < 2; by++)
                        for (int bz = 0; bz < 2; bz++)
                            for (int bx = 0; bx < 2; bx++) {
                                if (!LodChunkGuard.includes(writableMask, bx, bz)) continue;
                                int sy = y32 * 2 + by;
                                position.invoke(
                                        section,
                                        Math.floorDiv(column.x, 16) + bx,
                                        sy,
                                        Math.floorDiv(column.z, 16) + bz);
                                nonAir.setInt(section, state.voxels.fill(bx, sy, bz, target));
                                mip.invoke(null, section, state.map);
                                long inserting = PerformanceDiagnostics.begin(LOD_INSERT);
                                try {
                                    insert.invoke(null, engine, section);
                                } finally {
                                    PerformanceDiagnostics.end(inserting);
                                }
                            }
                    timings.record("cube", System.nanoTime() - started);
                }
            } finally {
                PerformanceDiagnostics.end(trace);
            }
        }

        @Override
        public void close() throws ReflectiveOperationException {
            if ((boolean) live.invoke(engine)) release.invoke(engine);
        }
    }
}

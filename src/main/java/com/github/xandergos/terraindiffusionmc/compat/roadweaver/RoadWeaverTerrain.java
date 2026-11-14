package com.github.xandergos.terraindiffusionmc.compat.roadweaver;

import com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.github.xandergos.terraindiffusionmc.world.WorldSessionBinding;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.PreparedWaterArea;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class RoadWeaverTerrain {
    public static final String SUPPORTED_VERSION = "2.2.0-1.21.11";

    private RoadWeaverTerrain() {}

    public static String installedVersion() {
        return FabricLoader.getInstance().getModContainer("roadweaver")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    public static boolean supports(String version) {
        return SUPPORTED_VERSION.equals(version);
    }

    private static final class RoadQuery {
        private static final MethodHandle IS_ON_ROAD = bind();

        private static MethodHandle bind() {
            if (!supports(installedVersion()) || !TerrainDiffusionConfig.roadWeaverEnabled()) return null;
            try {
                return MethodHandles.publicLookup().findStatic(
                    Class.forName("net.shiroha233.roadweaver.persistence.RoadPositionQuery"),
                    "isOnRoad", MethodType.methodType(boolean.class, StructureWorldAccess.class, BlockPos.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot bind Road Weaver road lookup", e);
            }
        }
    }

    public static boolean isOnRoad(StructureWorldAccess world, BlockPos pos) {
        var query = RoadQuery.IS_ON_ROAD;
        if (query == null) return false;
        try {
            return (boolean) query.invokeExact(world, pos);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Road Weaver road lookup failed", e);
        }
    }

    private static final class Density {
        private static final TerrainDiffusionDensityFunction SAMPLER = new TerrainDiffusionDensityFunction();
    }

    public static WorldSession session(ServerWorld world) {
        if (!WorldSessionBinding.ownsSession(world.getRegistryKey())
                || !(world.getChunkManager().getChunkGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            return null;
        }
        var session = WorldSession.current();
        WorldSession.requireActive(session);
        if (session.worldSeed() != world.getSeed() || session.repository() == null) {
            throw new IllegalStateException("Road Weaver requested terrain from an inactive world");
        }
        return session;
    }

    public static int firstAir(WorldSession session, int x, int z) {
        try (var ignored = InferenceDemand.scope(InferenceDemand.Priority.NEAR)) {
            return Density.SAMPLER.sampleFirstAir(session, x, z);
        }
    }

    public static int waterFirstAir(WorldSession session, int x, int z, int seaLevel) {
        return Math.max(seaLevel, riverFirstAir(session, x, z));
    }

    public static int riverFirstAir(WorldSession session, int x, int z) {
        WorldSession.requireActive(session);
        var overlay = session.riverOverlay();
        if (overlay == null) return PreparedWaterArea.NONE;
        try (var ignored = InferenceDemand.scope(InferenceDemand.Priority.NEAR)) {
            int water = overlay.waterY(x, z);
            WorldSession.requireActive(session);
            return water == PreparedWaterArea.NONE ? water : water + 1;
        }
    }

    public static int floorRelativeToSea(int floor, int waterFirstAir, int seaLevel) {
        return floor - (waterFirstAir - seaLevel);
    }

    public static double mountainGradeCost(int dx, int dy, int dz) {
        double run = Math.hypot(dx, dz);
        double excess = Math.max(0, Math.abs(dy) - run * 0.25);
        return 16 * excess * excess / Math.max(1, run);
    }

    public static double mountainGradeCost(ServerWorld world, BlockPos from, BlockPos to) {
        if (Math.max(from.getY(), to.getY()) < 125 || from.getY() == to.getY()) return 0;
        var session = session(world);
        return session != null && session.profile().newLandforms()
            ? mountainGradeCost(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ()) : 0;
    }

    public static int bridgeComparisonFloor(ServerWorld world, int x, int z, int original) {
        var session = session(world);
        if (session == null) return original;
        int sea = world.getSeaLevel();
        int water = waterFirstAir(session, x, z, sea);
        // Bridge detection subtracts the floor from sea level. Preserve the local depth.
        return water == sea ? original : floorRelativeToSea(firstAir(session, x, z), water, sea);
    }
}

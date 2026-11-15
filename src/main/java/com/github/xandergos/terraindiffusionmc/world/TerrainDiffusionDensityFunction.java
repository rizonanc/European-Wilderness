package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.RiverRasterizer;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.WorldHeightProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.dynamic.CodecHolder;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunction.DensityFunctionVisitor;
import net.minecraft.world.gen.densityfunction.DensityFunction.EachApplier;
import net.minecraft.world.gen.densityfunction.DensityFunction.NoisePos;

public class TerrainDiffusionDensityFunction implements DensityFunction {
    private static final int DENSITY_WINDOW_SIDE = 16;
    private final DensitySampler sampler = new DensitySampler();
    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC =
            MapCodec.unit(TerrainDiffusionDensityFunction::new);
    public static final CodecHolder<TerrainDiffusionDensityFunction> CODEC_HOLDER = CodecHolder.of(CODEC);
    static volatile TerrainDiffusionDensityFunction.HeightmapLookup heightmapLookup =
            (i1, j1, i2, j2) -> LocalTerrainProvider.getInstance().fetchHeightmap(i1, j1, i2, j2);

    @Override
    public double sample(NoisePos pos) {
        return this.compute(pos);
    }

    public double compute(NoisePos context) {
        WorldSession session = WorldSession.current();
        return sampler.sample(session, heightmapLookup, context.blockX(), context.blockY(), context.blockZ());
    }

    public int sampleFirstAir(WorldSession session, int x, int z) {
        return (int) sampler.sample(session, heightmapLookup, x, 0, z);
    }

    static int targetHeight(LocalTerrainProvider.HeightmapData data, int localX, int localZ, int scale) {
        return TerrainDiffusionDensityFunction.Pure.targetHeight(data, localX, localZ, scale);
    }

    static double densityAt(int surfaceY, int queriedY) {
        return TerrainDiffusionDensityFunction.Pure.densityAt(surfaceY, queriedY);
    }

    @Override
    public void fill(double[] densities, EachApplier applier) {
        if (densities.length != 0) {
            WorldSession session = WorldSession.current();
            WorldSession.requireActive(session);
            for (int i = 0; i < densities.length; i++) {
                NoisePos pos = applier.at(i);
                densities[i] = sampler.sample(session, heightmapLookup, pos.blockX(), pos.blockY(), pos.blockZ());
            }

            WorldSession.requireActive(session);
        }
    }

    @Override
    public DensityFunction apply(DensityFunctionVisitor visitor) {
        return visitor.apply(this);
    }

    @Override
    public double minValue() {
        return minValueForScale(WorldScaleManager.getCurrentScale());
    }

    @Override
    public double maxValue() {
        return maxValueForScale(WorldScaleManager.getCurrentScale());
    }

    static double minValueForScale(int scale) {
        return TerrainDiffusionDensityFunction.Pure.minValueForScale(scale);
    }

    static double maxValueForScale(int scale) {
        return TerrainDiffusionDensityFunction.Pure.maxValueForScale(scale);
    }

    @Override
    public CodecHolder<? extends DensityFunction> getCodecHolder() {
        return CODEC_HOLDER;
    }

    @FunctionalInterface
    interface HeightmapLookup extends DensitySampler.Lookup {}

    static final class Pure {
        private Pure() {}

        static TerrainDiffusionDensityFunction.Pure.TileWindow tileWindow(int blockX, int blockZ, int tileSize) {
            int startX = Math.floorDiv(blockX, tileSize) * tileSize;
            int startZ = Math.floorDiv(blockZ, tileSize) * tileSize;
            return new TerrainDiffusionDensityFunction.Pure.TileWindow(
                    startX, startZ, startX + tileSize, startZ + tileSize);
        }

        static TerrainDiffusionDensityFunction.Pure.LocalIndex localIndex(
                LocalTerrainProvider.HeightmapData data,
                TerrainDiffusionDensityFunction.Pure.TileWindow window,
                int blockX,
                int blockZ) {
            int localX = Math.max(0, Math.min(data.width - 1, blockX - window.startX()));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - window.startZ()));
            return new TerrainDiffusionDensityFunction.Pure.LocalIndex(localX, localZ);
        }

        static int targetHeight(LocalTerrainProvider.HeightmapData data, int localX, int localZ, int scale) {
            return data.surfaceYAt(localX, localZ, scale);
        }

        static int applyRiverOverlay(
                WorldSession.RiverOverlay overlay, int worldX, int worldZ, int rawSurfaceY, boolean sourceIsFallback) {
            if (overlay == null) {
                return rawSurfaceY;
            }

            if (!RiverRasterizer.shouldRasterize(sourceIsFallback)) {
                return rawSurfaceY;
            }

            overlay.waterY(worldX, worldZ);
            return overlay.carvedHeight(worldX, worldZ, rawSurfaceY);
        }

        static double densityAt(int surfaceY, int queriedY) {
            return (double) surfaceY - queriedY;
        }

        static double minValueForScale(int scale) {
            return -WorldHeightProfile.dimensionHeightForScale(scale);
        }

        static double maxValueForScale(int scale) {
            return WorldHeightProfile.dimensionHeightForScale(scale);
        }

        record LocalIndex(int x, int z) {}

        record TileWindow(int startX, int startZ, int endX, int endZ) {
            boolean contains(int blockX, int blockZ) {
                return blockX >= this.startX && blockX < this.endX && blockZ >= this.startZ && blockZ < this.endZ;
            }
        }
    }
}

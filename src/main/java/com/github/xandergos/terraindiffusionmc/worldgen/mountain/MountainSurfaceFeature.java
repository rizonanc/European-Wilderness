package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.PerformanceProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.PreparedFallback;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainRepository;
import java.util.Iterator;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

public final class MountainSurfaceFeature extends Feature<DefaultFeatureConfig> {
    private static final MountainPassCampFeature PASS_AND_CAMP = new MountainPassCampFeature();

    public MountainSurfaceFeature() {
        super(DefaultFeatureConfig.CODEC);
    }

    @Override
    public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        if (!(context.getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            return false;
        }

        StructureWorldAccess world = context.getWorld();
        WorldSession session = WorldSession.current();
        WorldSession.requireActive(session);
        TerrainRepository repository = session.repository();
        if (repository == null) {
            throw new IllegalStateException("Missing mountain terrain repository");
        }

        long seed = world.getSeed();
        int chunkX = Math.floorDiv(context.getOrigin().getX(), 16);
        int chunkZ = Math.floorDiv(context.getOrigin().getZ(), 16);
        PerformanceProfile.PreparationMode mode = PreparedFallback.mode();
        MountainTerrainView terrain = new MountainTerrainView(
                repository.tileSize(),
                seed,
                session.fingerprint(),
                session.profile().worldScale(),
                world.getTopYInclusive(),
                MountainTerrainView.adaptChecked(PreparedFallback.requiredTilesFor(repository, mode)::get));

        try {
            boolean surfaceChanged = MountainSurfacePlanning.planAndWrite(
                    mode,
                    () -> MountainSurfacePlanning.planChunk(
                            terrain, seed, chunkX, chunkZ, MountainSurfaceFeature::classify),
                    plan -> {
                        WorldSession.requireActive(session);
                        boolean changed = write(world, plan, seed)
                                | MountainLandforms.generate(
                                        world,
                                        terrain,
                                        seed,
                                        chunkX,
                                        chunkZ,
                                        session.riverOverlay(),
                                        session.profile().newLandforms());
                        return session.profile().climateShift().isPresent()
                                ? changed | StoneLandmark.generate(world, terrain, seed, chunkX, chunkZ)
                                : changed;
                    });
            return PASS_AND_CAMP.generate(context) | surfaceChanged;
        } catch (MountainTerrainView.LookupException failed) {
            throw new IllegalStateException("Mountain surface terrain lookup failed", failed.getCause());
        }
    }

    private static MountainSurfaceMaterials.Material classify(
            long seed, int x, int z, int y, float temperature, int north, int south, int west, int east) {
        int slope = Math.max(
                Math.max(Math.abs(north - y), Math.abs(south - y)), Math.max(Math.abs(west - y), Math.abs(east - y)));
        var glacial = RegionalGeology.glacialSurface(seed, x, z, y, temperature, slope);
        return glacial == MountainSurfaceMaterials.Material.NONE
                ? MountainSurfaceMaterials.classify(seed, x, z, y, temperature, north, south, west, east)
                : glacial;
    }

    private static boolean write(StructureWorldAccess world, List<MountainSurfacePlanning.Placement> plan, long seed) {
        boolean changed = false;
        Iterator var3 = plan.iterator();

        while (true) {
            MountainSurfacePlanning.Placement placement;
            int groundY;
            BlockPos abovePos;
            MountainSurfaceFeature.SurfaceWrite write;
            while (true) {
                if (!var3.hasNext()) {
                    return changed;
                }

                placement = (MountainSurfacePlanning.Placement) var3.next();
                groundY = placement.groundY();
                if (groundY >= world.getBottomY() && groundY < world.getTopYInclusive()) {
                    BlockPos groundPos = new BlockPos(placement.x(), groundY, placement.z());
                    abovePos = groundPos.up();
                    BlockState ground = world.getBlockState(groundPos);
                    BlockState above = world.getBlockState(abovePos);
                    write = planWrite(
                            ground,
                            above,
                            placement.material(),
                            RegionalGeology.rock(seed, placement.x(), placement.z()));
                    if (write != null) {
                        if (write.groundReplacement() == null) {
                            break;
                        }

                        if (world.setBlockState(groundPos, write.groundReplacement(), 2)) {
                            changed = true;
                            break;
                        }
                    }
                }
            }

            if (write.aboveReplacement() != null) {
                changed |= world.setBlockState(abovePos, write.aboveReplacement(), 2);
            }

            if (placement.material() == MountainSurfaceMaterials.Material.ROCK) {
                Block backing = RegionalGeology.rock(seed, placement.x(), placement.z());
                final int surfaceY = groundY;
                final MountainSurfacePlanning.Placement surfacePlacement = placement;
                int depth = MountainSurfacePlanning.rockSubsurfaceDepth(offsetx -> {
                    int y = surfaceY - offsetx;
                    if (y < world.getBottomY()) {
                        return false;
                    }

                    BlockState below = world.getBlockState(new BlockPos(surfacePlacement.x(), y, surfacePlacement.z()));
                    return naturalRockSubsurface(below) && below.getFluidState().isEmpty();
                });

                for (int offset = 1; offset <= depth; offset++) {
                    BlockPos belowPos = new BlockPos(placement.x(), groundY - offset, placement.z());
                    if (!world.getBlockState(belowPos).isOf(backing)) {
                        changed |= world.setBlockState(belowPos, backing.getDefaultState(), 2);
                    }
                }
            }
        }
    }

    static MountainSurfaceFeature.SurfaceWrite planWrite(
            BlockState ground, BlockState above, MountainSurfaceMaterials.Material material) {
        return planWrite(ground, above, material, Blocks.STONE);
    }

    private static SurfaceWrite planWrite(
            BlockState ground, BlockState above, MountainSurfaceMaterials.Material material, Block rock) {
        boolean singleSnowCap = above.isOf(Blocks.SNOW) && above.get(SnowBlock.LAYERS) == 1;

        Block replacement =
                switch (material) {
                    case ROCK -> rock;
                    case MORAINE -> Blocks.GRAVEL;
                    case SCREE -> Blocks.GRAVEL;
                    case SNOW -> Blocks.SNOW_BLOCK;
                    case NONE, THIN_SNOW -> null;
                };
        MountainSurfacePlanning.WriteDecision decision = MountainSurfacePlanning.decideWrite(
                material,
                naturalGround(ground),
                ground.getFluidState().isEmpty(),
                above.getFluidState().isEmpty(),
                above.isAir(),
                singleSnowCap,
                replacement != null && ground.isOf(replacement));
        if (decision == null) {
            return null;
        }

        BlockState newGround = decision.replaceGround() ? replacement.getDefaultState() : null;
        BlockState newAbove = decision.placeThinSnow()
                ? Blocks.SNOW.getDefaultState()
                : (decision.clearSnowCap() ? Blocks.AIR.getDefaultState() : null);
        return new MountainSurfaceFeature.SurfaceWrite(newGround, newAbove);
    }

    private static boolean naturalGround(BlockState state) {
        return state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.PODZOL)
                || state.isOf(Blocks.MYCELIUM)
                || state.isOf(Blocks.STONE)
                || state.isOf(Blocks.ANDESITE)
                || state.isOf(Blocks.DIORITE)
                || state.isOf(Blocks.GRANITE)
                || state.isOf(Blocks.CALCITE)
                || state.isOf(Blocks.TUFF)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.SNOW_BLOCK);
    }

    private static boolean naturalRockSubsurface(BlockState state) {
        return naturalGround(state) && !state.isOf(Blocks.SNOW_BLOCK) && !state.isOf(Blocks.GRASS_BLOCK);
    }

    record SurfaceWrite(BlockState groundReplacement, BlockState aboveReplacement) {}
}

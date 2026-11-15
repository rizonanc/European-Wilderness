package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.WorkTimeline.Stage.TREE_DECORATION;

import com.github.xandergos.terraindiffusionmc.compat.roadweaver.RoadWeaverTerrain;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanEcology;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.ForestTrail;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.GenerationProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.PerformanceProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.PreparedFallback;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

public final class EuropeanTreeFeature extends Feature<DefaultFeatureConfig> {
    static boolean acceptsBiome(short biomeId) {
        return VegetationDistribution.acceptsBiome(biomeId);
    }

    public EuropeanTreeFeature() {
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
            throw new IllegalStateException("Missing tree terrain repository");
        }

        long seed = world.getSeed();
        int chunkX = Math.floorDiv(context.getOrigin().getX(), 16);
        int chunkZ = Math.floorDiv(context.getOrigin().getZ(), 16);
        int scale = session.profile().worldScale();
        PerformanceProfile.PreparationMode mode = PreparedFallback.mode();
        TreeTerrainView terrain = new TreeTerrainView(
                repository.tileSize(),
                seed,
                session.fingerprint(),
                scale,
                world.getTopYInclusive(),
                TreeTerrainView.adaptChecked(PreparedFallback.requiredTilesFor(repository, mode)::get));

        long trace = PerformanceDiagnostics.begin(TREE_DECORATION);
        try {
            return TreeFeaturePlanning.planAndWrite(
                    mode,
                    () -> planDecoration(world, terrain, seed, chunkX, chunkZ, session.profile()),
                    plan -> writeDecoration(world, plan));
        } catch (TreeTerrainView.LookupException failed) {
            throw new IllegalStateException("European tree terrain lookup failed", failed.getCause());
        } finally {
            PerformanceDiagnostics.end(trace);
        }
    }

    private static EuropeanTreeFeature.DecorationPlan planDecoration(
            StructureWorldAccess world,
            TreeTerrainView terrain,
            long seed,
            int chunkX,
            int chunkZ,
            GenerationProfile profile) {
        Map<TreeCandidates.Candidate, EuropeanTreePlanner.PreparedTree> prepared = new HashMap<>();
        Set<TreeCandidates.Candidate> rejected = new HashSet<>();
        Function<TreeCandidates.Candidate, EuropeanTreePlanner.PreparedTree> prepare = candidate -> {
            EuropeanTreePlanner.PreparedTree cached = prepared.get(candidate);
            if (cached == null && !rejected.contains(candidate)) {
                EuropeanTreePlanner.PreparedTree evaluated =
                        prepare(candidate, seed, terrain, world.getBottomY(), world.getTopYInclusive(), profile);
                if (evaluated != null) {
                    int baseY = evaluated.baseY();
                    if (!TreePlacementValidator.rootsAvoid(
                            evaluated.shape(),
                            candidate.x(),
                            candidate.z(),
                            (x, z) -> RoadWeaverTerrain.isOnRoad(world, new BlockPos(x, baseY, z)))) evaluated = null;
                }
                if (evaluated == null) {
                    rejected.add(candidate);
                } else {
                    prepared.put(candidate, evaluated);
                }

                return evaluated;
            } else {
                return cached;
            }
        };
        Predicate<TreeCandidates.Candidate> accepted = candidate -> prepare.apply(candidate) != null;
        ArrayList<TreeCandidates.Candidate> candidates =
                new ArrayList<>(TreeCandidates.adultRootsForChunk(seed, chunkX, chunkZ, accepted));
        candidates.addAll(TreeCandidates.regenerationRootsForChunk(seed, chunkX, chunkZ, accepted, accepted));
        List<EuropeanTreePlanner.PreparedTree> trees = new ArrayList<>(candidates.size());

        for (TreeCandidates.Candidate candidate : candidates) {
            EuropeanTreePlanner.PreparedTree tree = prepare.apply(candidate);
            if (tree != null
                    && TreePlacementValidator.touchesChunk(
                            candidate.x(), candidate.z(), tree.shape().radius(), chunkX, chunkZ)) {
                trees.add(tree);
            }
        }

        trees.removeIf(treex -> !TreePlacementValidator.isWholeTreeWorldSupported(
                treex.shape(),
                treex.candidate().x(),
                treex.baseY(),
                treex.candidate().z(),
                (x, z) -> worldFirstAir(world, x, treex.baseY(), z, world.getBottomY())));
        EuropeanTreePlanner.ChunkPlan treePlan =
                EuropeanTreePlanner.planRootChunk(trees, chunkX, chunkZ, (tree, voxel) -> {
                    BlockPos pos = new BlockPos(voxel.x(), voxel.y(), voxel.z());
                    if (!world.isValidForSetBlock(pos)) return false;
                    BlockState old = world.getBlockState(pos);
                    boolean soft =
                            old.isAir() || old.isIn(BlockTags.REPLACEABLE_BY_TREES) || old.isIn(BlockTags.LEAVES);
                    return TreePlacementValidator.mayPlaceWholeVoxel(
                            voxel.log(),
                            voxel.y() - tree.baseY(),
                            !old.getFluidState().isEmpty(),
                            soft,
                            old.isIn(BlockTags.LOGS),
                            naturalRootGround(old));
                });
        List<EuropeanTreeFeature.TrailPlacement> trails = planTrails(world, terrain, seed, chunkX, chunkZ);
        return new EuropeanTreeFeature.DecorationPlan(treePlan, trails);
    }

    private static boolean writeDecoration(StructureWorldAccess world, EuropeanTreeFeature.DecorationPlan plan) {
        int written = EuropeanTreePlanner.place(plan.trees(), voxel -> {
            BlockPos pos = new BlockPos(voxel.x(), voxel.y(), voxel.z());
            BlockState old = world.getBlockState(pos);
            if (old.isIn(BlockTags.LOGS) || !old.getFluidState().isEmpty()) return false;
            Block block = voxel.log() ? log(voxel.species()) : leaf(voxel.species());
            BlockState state = voxel.log()
                    ? block.getDefaultState()
                    : block.getDefaultState()
                            .with(LeavesBlock.DISTANCE, voxel.leafDistance())
                            .with(LeavesBlock.PERSISTENT, false);
            return world.setBlockState(pos, state, 2);
        });
        int trailsWritten = placeTrails(world, plan.trails());
        return written > 0 || trailsWritten > 0;
    }

    private static int worldFirstAir(StructureWorldAccess world, int x, int baseY, int z, int bottomY) {
        int floor = Math.max(bottomY, Math.subtractExact(baseY, 8));

        for (int y = Math.addExact(baseY, 1); y >= floor; y--) {
            BlockState old = world.getBlockState(new BlockPos(x, y, z));
            if (old.getFluidState().isEmpty()) {
                boolean soft = old.isAir()
                        || old.isIn(BlockTags.REPLACEABLE_BY_TREES)
                        || old.isIn(BlockTags.LEAVES)
                        || old.isIn(BlockTags.LOGS);
                if (!soft) {
                    return Math.addExact(y, 1);
                }
            }
        }

        return Math.subtractExact(floor, 1);
    }

    private static List<EuropeanTreeFeature.TrailPlacement> planTrails(
            StructureWorldAccess world, TreeTerrainView terrain, long seed, int chunkX, int chunkZ) {
        List<EuropeanTreeFeature.TrailPlacement> placements = new ArrayList<>();
        int baseX = Math.multiplyExact(chunkX, 16);
        int baseZ = Math.multiplyExact(chunkZ, 16);

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = Math.addExact(baseX, dx);
                int z = Math.addExact(baseZ, dz);
                ForestTrail.Kind kind = ForestTrail.kind(seed, x, z);
                if (kind != ForestTrail.Kind.NONE
                        && acceptsBiome(terrain.sample(x, z).biome())) {
                    int groundY = Math.subtractExact(terrain.firstAir(x, z), 1);
                    if (groundY >= world.getBottomY()) {
                        BlockPos groundPos = new BlockPos(x, groundY, z);
                        if (world.getBlockState(groundPos).isOf(Blocks.GRASS_BLOCK)) {
                            Block trail = kind == ForestTrail.Kind.PATH ? Blocks.DIRT_PATH : Blocks.COARSE_DIRT;
                            placements.add(new EuropeanTreeFeature.TrailPlacement(groundPos, trail));
                        }
                    }
                }
            }
        }

        return placements;
    }

    private static int placeTrails(StructureWorldAccess world, List<EuropeanTreeFeature.TrailPlacement> placements) {
        int placed = 0;

        for (EuropeanTreeFeature.TrailPlacement placement : placements) {
            if (world.getBlockState(placement.position()).isOf(Blocks.GRASS_BLOCK)
                    && world.setBlockState(
                            placement.position(), placement.block().getDefaultState(), 2)) {
                placed++;
            }
        }

        return placed;
    }

    private static EuropeanTreePlanner.PreparedTree prepare(
            TreeCandidates.Candidate candidate,
            long seed,
            TreeTerrainView terrain,
            int bottomY,
            int topY,
            GenerationProfile profile) {
        int x = candidate.x();
        int z = candidate.z();
        if (terrain.waterY(x, z) != Integer.MIN_VALUE) return null;
        TreeTerrainView.Sample sample = terrain.sample(x, z);
        if (!acceptsBiome(sample.biome())
                || (profile.largerLandscapes()
                        && sample.biome() == 229
                        && EuropeanEcology.openField(seed, x, z, profile.biomeBalance()))) {
            return null;
        } else {
            double dx = (terrain.firstAir(Math.addExact(x, 1), z) - terrain.firstAir(Math.addExact(x, -1), z)) / 2.0;
            double dz = (terrain.firstAir(x, Math.addExact(z, 1)) - terrain.firstAir(x, Math.addExact(z, -1))) / 2.0;
            EuropeanEcology.ForestCondition ecology = EuropeanEcology.evaluate(
                    seed,
                    sample.temperatureC(),
                    sample.precipitationMm(),
                    sample.heightMeters(),
                    Math.hypot(dx, dz),
                    Double.POSITIVE_INFINITY,
                    x,
                    z,
                    profile.largerLandscapes(),
                    profile.biomeBalance());
            boolean accepted = candidate.age() == TreeAge.YOUNG
                    ? VegetationDistribution.acceptsYoung(candidate, seed, ecology)
                    : VegetationDistribution.acceptsAdult(candidate, seed, ecology);
            if (!accepted) {
                return null;
            } else {
                TreeSpecies species = ForestComposition.at(seed, x, z, ecology).choose(candidate.speciesRoll());
                TreeShapeGenerator.Shape shape =
                        TreeShapeGenerator.generate(species, candidate.age(), candidate.seed());
                if (!TreePlacementValidator.fitsWorldCoordinates(x, z, shape.radius())) {
                    return null;
                } else {
                    int baseY = TreePlacementValidator.anchorBaseY(shape, x, z, terrain::firstAir);
                    long highestY = (long) baseY + shape.height() - 1L;
                    if (baseY < bottomY || highestY > topY) {
                        return null;
                    } else {
                        return !TreePlacementValidator.isWholeTreeClear(
                                        shape,
                                        x,
                                        baseY,
                                        z,
                                        terrain::firstAir,
                                        terrain::clearanceFirstAir,
                                        terrain::waterY)
                                ? null
                                : new EuropeanTreePlanner.PreparedTree(candidate, species, shape, baseY);
                    }
                }
            }
        }
    }

    private static TreeSpecies species(double roll, EuropeanEcology.ForestCondition ecology) {
        if (roll < ecology.oakShare()) {
            return TreeSpecies.OAK;
        }

        roll -= ecology.oakShare();
        if (roll < ecology.birchShare()) {
            return TreeSpecies.BIRCH;
        }

        roll -= ecology.birchShare();
        return roll < ecology.spruceShare() ? TreeSpecies.SPRUCE : TreeSpecies.PINE;
    }

    private static boolean naturalRootGround(BlockState state) {
        return state.isIn(BlockTags.DIRT)
                || state.isIn(BlockTags.SAND)
                || state.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.STONE)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.CLAY)
                || state.isOf(Blocks.DIRT_PATH)
                || state.isOf(Blocks.SNOW_BLOCK);
    }

    private static Block log(TreeSpecies species) {
        return switch (species) {
            case OAK -> Blocks.OAK_LOG;
            case BIRCH -> Blocks.BIRCH_LOG;
            case SPRUCE, PINE -> Blocks.SPRUCE_LOG;
        };
    }

    private static Block leaf(TreeSpecies species) {
        return switch (species) {
            case OAK -> Blocks.OAK_LEAVES;
            case BIRCH -> Blocks.BIRCH_LEAVES;
            case SPRUCE, PINE -> Blocks.SPRUCE_LEAVES;
        };
    }

    private record DecorationPlan(
            EuropeanTreePlanner.ChunkPlan trees, List<EuropeanTreeFeature.TrailPlacement> trails) {}

    private record TrailPlacement(BlockPos position, Block block) {}
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.DoubleBlockHalf;

public final class RiverWaterFeature extends Feature<DefaultFeatureConfig> {
    public RiverWaterFeature() {
        super(DefaultFeatureConfig.CODEC);
    }

    @Override
    public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        if (!(context.getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            return false;
        }

        WorldSession session = WorldSession.current();
        WorldSession.requireActive(session);
        WorldSession.RiverOverlay overlay = session.riverOverlay();
        if (overlay == null) {
            return false;
        }

        final StructureWorldAccess world = context.getWorld();
        int chunkX = Math.floorDiv(context.getOrigin().getX(), 16);
        int chunkZ = Math.floorDiv(context.getOrigin().getZ(), 16);
        // Seal beds and banks after carvers, before water. The one-column halo is
        // deterministic and shared by neighbouring chunks, regardless of feature order.
        boolean lined = sealBedsAndBanks(world, overlay, session.worldSeed(), chunkX * 16, chunkZ * 16);
        RiverWaterPlanning.ColumnAccess columns =
                new RiverWaterPlanning.ColumnAccess() {
                    @Override
                    public boolean hasBedSupport(int x, int y, int z) {
                        if (isSolidDry(x, y, z)) return true;
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = world.getBlockState(pos);
                        return riverSlab(state)
                                && state.get(Properties.SLAB_TYPE) == SlabType.BOTTOM
                                && state.get(Properties.WATERLOGGED)
                                && world.getBlockState(pos.down()).isSolidBlock(world, pos.down());
                    }

                    @Override
                    public boolean isWaterloggedBankCap(int x, int y, int z) {
                        return overlay.waterY(x, z) == PreparedWaterArea.NONE
                                && y == overlay.bedY(x, z, 0)
                                && hasBedSupport(x, y, z)
                                && world.getBlockState(new BlockPos(x, y, z)).getFluidState().isIn(FluidTags.WATER);
                    }

                    @Override
                    public boolean isWater(int x, int y, int z) {
                        return world.getBlockState(new BlockPos(x, y, z)).getFluidState().isIn(FluidTags.WATER);
                    }

                    @Override
                    public boolean isAir(int x, int y, int z) {
                        return world.getBlockState(new BlockPos(x, y, z)).isAir();
                    }

                    @Override
                    public boolean isSolidDry(int x, int y, int z) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = world.getBlockState(pos);
                        return state.getFluidState().isEmpty() && state.isSolidBlock(world, pos);
                    }
                };
        List<BlockPos> placements =
                RiverWaterPlanning.planChunk(
                        overlay, chunkX, chunkZ, world.getBottomY(), world.getTopYInclusive(), columns);
        if (placements.isEmpty()) {
            return placeWaterfalls(world, overlay, chunkX * 16, chunkZ * 16)
                    | decorate(world, overlay, session.worldSeed(), chunkX * 16, chunkZ * 16)
                    | lined;
        }

        WorldSession.requireActive(session);
        boolean changed = lined;

        for (BlockPos pos : placements) {
            if (world.getBlockState(pos).isAir()) {
                changed |= world.setBlockState(pos, Blocks.WATER.getDefaultState(), 2);
                if (pos.getY() == overlay.waterY(pos.getX(), pos.getZ())) world.scheduleFluidTick(pos, Fluids.WATER, 1);
            }
        }

        return placeWaterfalls(world, overlay, chunkX * 16, chunkZ * 16)
                | decorate(world, overlay, session.worldSeed(), chunkX * 16, chunkZ * 16)
                | changed;
    }

    private static boolean riverSlab(BlockState state) {
        return state.isOf(RiverBlocks.SAND_SLAB)
                || state.isOf(RiverBlocks.DIRT_SLAB)
                || state.isOf(RiverBlocks.TURF_SLAB);
    }

    private static boolean placeWaterfalls(
            StructureWorldAccess world, WorldSession.RiverOverlay overlay, int baseX, int baseZ) {
        boolean[] changed = {false};
        WaterfallPlacement.forChunk(
                overlay,
                baseX,
                baseZ,
                world.getBottomY(),
                world.getTopYInclusive(),
                p -> {
                    BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
                    BlockState old = world.getBlockState(pos);
                    if (!old.isAir() && !old.isOf(Blocks.WATER)) return;
                    BlockState state =
                            Blocks.WATER
                                    .getDefaultState()
                                    .with(FluidBlock.LEVEL, p.kind() == WaterfallPlacement.Kind.SOURCE ? 0 : 8);
                    if (!old.equals(state)) changed[0] |= world.setBlockState(pos, state, 2);
                    world.scheduleFluidTick(pos, Fluids.WATER, 1);
                });
        return changed[0];
    }

    private static boolean decorate(
            StructureWorldAccess world, WorldSession.RiverOverlay overlay, long seed, int baseX, int baseZ) {
        boolean[] changed = {false};
        RiverDetailPlanning.forChunk(
                overlay,
                seed,
                baseX,
                baseZ,
                world.getBottomY(),
                world.getTopYInclusive(),
                detail -> {
                    BlockPos pos = new BlockPos(detail.x(), detail.y(), detail.z());
                    BlockState existing = world.getBlockState(pos), support = world.getBlockState(pos.down());
                    if (detail.kind().slab()) {
                        // Repeated neighbouring decoration must preserve already placed slabs/plants.
                        boolean embedded = detail.y() == overlay.bedY(detail.x(), detail.z(), 0);
                        boolean capGround =
                                existing.isOf(Blocks.SAND)
                                        || existing.isIn(BlockTags.DIRT)
                                        || existing.isOf(Blocks.GRAVEL)
                                        || existing.isOf(Blocks.CLAY);
                        if (!(embedded ? capGround : existing.isAir())) return;
                        if (!support.isSolidBlock(world, pos.down())) return;
                        if (!embedded
                                && detail.kind() == RiverDetailPlanning.Kind.TURF_SLAB
                                && !support.isOf(Blocks.GRASS_BLOCK)
                                && !support.isOf(Blocks.DIRT)
                                && !support.isIn(BlockTags.DIRT)
                                && !support.isOf(Blocks.MOSS_BLOCK)) return;
                        var block =
                                switch (detail.kind()) {
                                    case SAND_SLAB -> RiverBlocks.SAND_SLAB;
                                    case DIRT_SLAB -> RiverBlocks.DIRT_SLAB;
                                    case STONE_SLAB -> Blocks.COBBLESTONE_SLAB;
                                    default -> RiverBlocks.TURF_SLAB;
                                };
                        BlockState slab =
                                block.getDefaultState()
                                        .with(Properties.SLAB_TYPE, SlabType.BOTTOM)
                                        .with(Properties.WATERLOGGED, detail.waterlogged());
                        changed[0] |= world.setBlockState(pos, slab, 2);
                        if (detail.waterlogged()) world.scheduleFluidTick(pos, Fluids.WATER, 1);
                    } else {
                        if (!existing.isOf(Blocks.WATER) || !existing.getFluidState().isStill()) return;
                        boolean tall = detail.kind() == RiverDetailPlanning.Kind.TALL_SEAGRASS;
                        BlockState grass =
                                tall
                                        ? Blocks.TALL_SEAGRASS
                                                .getDefaultState()
                                                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                                        : Blocks.SEAGRASS.getDefaultState();
                        if (!grass.canPlaceAt(world, pos)) return;
                        if (tall) {
                            BlockState above = world.getBlockState(pos.up());
                            if (!above.isOf(Blocks.WATER) || !above.getFluidState().isStill()) return;
                            changed[0] |= world.setBlockState(pos, grass, 2);
                            changed[0] |=
                                    world.setBlockState(
                                            pos.up(),
                                            grass.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER),
                                            2);
                        } else changed[0] |= world.setBlockState(pos, grass, 2);
                    }
                });
        return changed[0];
    }

    private static boolean sealBedsAndBanks(
            StructureWorldAccess world, WorldSession.RiverOverlay overlay, long seed, int baseX, int baseZ) {
        boolean[] changed = {false};
        WaterLining.forChunk(
                overlay,
                baseX,
                baseZ,
                world.getBottomY(),
                world.getTopYInclusive(),
                (x, y, z, sand) -> changed[0] |= writeLining(world, overlay, seed, x, y, z, sand));
        // Apply the same coordinate palette to the halo as to the chunk itself,
        // so a later neighbour cannot turn its already finished bank back into sand.
        for (int dz = -1; dz <= 16; dz++)
            for (int dx = -1; dx <= 16; dx++) {
                int x = baseX + dx, z = baseZ + dz;
                if (!overlay.isReady(x, z)
                        || overlay.waterY(x, z) != PreparedWaterArea.NONE
                        || overlay.waterfallAt(x, z) != null) continue;
                var shore = RiverShorePalette.nearby(overlay, x, z);
                if (shore.level() == PreparedWaterArea.NONE) continue;
                int y = overlay.bedY(x, z, 0);
                if (y < shore.level() || y > shore.level() + 3) continue;
                changed[0] |= writeLining(world, overlay, seed, x, y, z, true);
            }
        return changed[0];
    }

    private static boolean writeLining(
            StructureWorldAccess world,
            WorldSession.RiverOverlay overlay,
            long seed,
            int x,
            int y,
            int z,
            boolean sand) {
        if (y < world.getBottomY() || y > world.getTopYInclusive()) return false;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState existing = world.getBlockState(pos);
        BlockState replacement = liningReplacement(existing, sand);
        Waterfall fall = overlay.waterfallAt(x, z);
        if (fall != null && fall.rockColumn(x, z) && replacement != null) replacement = Blocks.STONE.getDefaultState();
        if (fall == null
                && sand
                && replacement != null
                && overlay.waterY(x, z) == PreparedWaterArea.NONE
                && y == overlay.bedY(x, z, 0)) {
            var shore = RiverShorePalette.nearby(overlay, x, z);
            if (shore.level() != PreparedWaterArea.NONE)
                replacement =
                        switch (RiverShorePalette.material(seed, x, z, shore.distance())) {
                            case DIRT -> Blocks.DIRT.getDefaultState();
                            case COARSE_DIRT -> Blocks.COARSE_DIRT.getDefaultState();
                            case GRAVEL ->
                                    com.github.xandergos.terraindiffusionmc.worldgen.mountain.RegionalGeology.gravel(
                                                    seed, x, z)
                                            .getDefaultState();
                            case GRASS -> Blocks.GRASS_BLOCK.getDefaultState();
                            case SAND -> Blocks.SAND.getDefaultState();
                        };
        }
        return replacement != null && !existing.equals(replacement) && world.setBlockState(pos, replacement, 2);
    }

    static BlockState liningReplacement(BlockState existing, boolean sand) {
        boolean open = existing.isAir() || existing.isOf(Blocks.WATER);
        if (!sand) return open ? Blocks.SANDSTONE.getDefaultState() : null;
        boolean natural =
                existing.isOf(Blocks.DIRT)
                        || existing.isOf(Blocks.GRASS_BLOCK)
                        || existing.isOf(Blocks.STONE)
                        || existing.isIn(BlockTags.DIRT)
                        || existing.isIn(BlockTags.BASE_STONE_OVERWORLD)
                        || existing.isIn(BlockTags.SAND)
                        || existing.isOf(Blocks.GRAVEL)
                        || existing.isOf(Blocks.CLAY)
                        || existing.isOf(Blocks.MUD)
                        || existing.isOf(Blocks.SNOW_BLOCK);
        return open || natural ? Blocks.SAND.getDefaultState() : null;
    }
}

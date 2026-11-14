package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;

final class MountainLandforms {
    private MountainLandforms() {}

    static boolean generate(StructureWorldAccess world, MountainTerrainView terrain, long seed, int chunkX, int chunkZ,
                            WorldSession.RiverOverlay water) {
        int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
        double chance = MountainSurfaceMaterials.valueNoise01(seed ^ 0x196aea5L, x, z, 16);
        if (chance < 0.82) return false;
        boolean limestone = RegionalGeology.region(seed, x, z) == RegionalGeology.Rock.LIMESTONE;
        var center = terrain.sample(x, z);
        int y = center.firstAir();
        boolean tarn = RegionalGeology.glacial(center.temperatureC(), y);
        boolean karst = !tarn && limestone && y >= 110 && chance >= 0.88;
        if (!tarn && !karst) return limestone && y >= 85 && y <= 155 && chance > 0.83
            && spring(world, terrain, water, x, y, z);
        if (y < world.getBottomY() + 25 || y > world.getTopYInclusive() - 10) return false;

        int outlet = 1;
        int edge = terrain.firstAir(x + 6, z);
        if (terrain.firstAir(x - 6, z) < edge) outlet = -1;
        for (int dz = -6; dz <= 6; dz++) for (int dx = -6; dx <= 6; dx++) {
            if (dx * dx + dz * dz > 36) continue;
            int wx = x + dx, wz = z + dz;
            int air = terrain.firstAir(wx, wz);
            if (Math.abs(air - y) > (tarn ? 3 : 6)
                || water != null && (water.waterY(wx, wz) != Integer.MIN_VALUE || water.waterfallAt(wx, wz) != null)) return false;
            var top = world.getBlockState(new BlockPos(wx, air - 1, wz));
            if (!top.getFluidState().isEmpty() || !(top.isIn(BlockTags.BASE_STONE_OVERWORLD)
                || top.isIn(BlockTags.DIRT) || top.isOf(Blocks.CALCITE) || top.isOf(Blocks.GRAVEL)
                || top.isOf(Blocks.SNOW_BLOCK) || top.isOf(Blocks.PACKED_ICE))) return false;
        }
        return tarn ? tarn(world, seed, x, y, z) : karst(world, terrain, x, y, z, outlet);
    }

    private static boolean spring(StructureWorldAccess world, MountainTerrainView terrain, WorldSession.RiverOverlay water,
                                  int x, int y, int z) {
        var source = new BlockPos(x, y - 1, z);
        if (water != null && water.waterY(x, z) != Integer.MIN_VALUE
            || !world.getBlockState(source).isIn(BlockTags.BASE_STONE_OVERWORLD)
            || !world.getBlockState(source.down()).isSolidBlock(world, source.down())) return false;
        for (var direction : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            var front = source.offset(direction);
            var back = source.offset(direction.getOpposite());
            if (terrain.firstAir(back.getX(), back.getZ()) < y + 2
                || terrain.firstAir(front.getX(), front.getZ()) > y - 2
                || !world.getBlockState(front).isAir()) continue;
            if (!world.setBlockState(source, Blocks.WATER.getDefaultState(), 2)) return false;
            world.scheduleFluidTick(source, net.minecraft.fluid.Fluids.WATER, 1);
            return true;
        }
        return false;
    }

    private static boolean tarn(StructureWorldAccess world, long seed, int x, int y, int z) {
        boolean changed = false;
        int level = y - 4;
        for (int dz = -5; dz <= 5; dz++) for (int dx = -5; dx <= 5; dx++) {
            int radius = dx * dx + dz * dz;
            if (radius > 25) continue;
            var pos = new BlockPos(x + dx, level - 3, z + dz);
            if (naturalOrAir(world.getBlockState(pos)))
                changed |= world.setBlockState(pos, RegionalGeology.rock(seed, x + dx, z + dz).getDefaultState(), 2);
            for (int dy = -2; dy <= 6; dy++) {
                var target = pos.up(3 + dy);
                var material = radius > 16 && dy <= 0 ? Blocks.GRAVEL : dy <= 0 ? Blocks.WATER : Blocks.AIR;
                if (naturalOrAir(world.getBlockState(target))) {
                    changed |= world.setBlockState(target, material.getDefaultState(), 2);
                }
            }
        }
        return changed;
    }

    private static boolean naturalOrAir(net.minecraft.block.BlockState state) {
        return state.isAir() || state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.DIRT)
            || state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.SNOW) || state.isOf(Blocks.PACKED_ICE)
            || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.CALCITE);
    }

    private static boolean karst(StructureWorldAccess world, MountainTerrainView terrain, int x, int y, int z, int outlet) {
        boolean changed = false;
        for (int dz = -5; dz <= 5; dz++) for (int dx = -5; dx <= 5; dx++) {
            int r2 = dx * dx + dz * dz;
            if (r2 > 25) continue;
            int surface = terrain.firstAir(x + dx, z + dz);
            int floor = y - 10 + (int)Math.sqrt(r2) * 2;
            for (int wy = floor; wy < surface; wy++) {
                changed |= cut(world, new BlockPos(x + dx, wy, z + dz), false);
            }
        }
        // Sinkhole floor opens into a low gallery with a shallow watercourse.
        for (int s = -1; s <= 6; s++) for (int dz = -1; dz <= 1; dz++) {
            int wx = x + s * outlet;
            for (int wy = y - 11; wy <= y - 6; wy++) {
                if (wy == y - 11 && dz != 0) continue;
                changed |= cut(world, new BlockPos(wx, wy, z + dz), wy == y - 11 && s == 6);
            }
        }
        return changed;
    }

    private static boolean cut(StructureWorldAccess world, BlockPos pos, boolean wet) {
        var state = world.getBlockState(pos);
        if (!state.isIn(BlockTags.BASE_STONE_OVERWORLD) && !state.isIn(BlockTags.DIRT)
            && !state.isOf(Blocks.CALCITE) && !state.isOf(Blocks.GRAVEL)) return false;
        if (!state.getFluidState().isEmpty()) return false;
        if (wet) {
            if (!naturalOrAir(world.getBlockState(pos.down()))) return false;
            world.setBlockState(pos.down(), Blocks.CALCITE.getDefaultState(), 2);
            if (!world.setBlockState(pos, Blocks.WATER.getDefaultState(), 2)) return false;
            world.scheduleFluidTick(pos, net.minecraft.fluid.Fluids.WATER, 1);
            return true;
        }
        return world.setBlockState(pos, Blocks.CAVE_AIR.getDefaultState(), 2);
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;

final class StoneLandmark {
    enum Shape { ARCH, PLATEAU, SPIRE }

    private StoneLandmark() {}

    static boolean generate(StructureWorldAccess world, MountainTerrainView terrain, long seed, int chunkX, int chunkZ) {
        int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
        if (MountainSurfaceMaterials.valueNoise01(seed ^ 0x31bfadL, x, z, 16) < 0.996) return false;
        int y = terrain.firstAir(x, z);
        if (y < 80 || y > world.getTopYInclusive() - 48) return false;
        var random = new java.util.SplittableRandom(seed ^ ((long)x << 32) ^ z);
        Shape shape = Shape.values()[random.nextInt(3)];
        boolean eastWest = random.nextBoolean();
        int height = switch (shape) {
            case ARCH -> random.nextInt(13, 19);
            case PLATEAU -> random.nextInt(12, 24);
            case SPIRE -> random.nextInt(20, 40);
        };
        var rock = RegionalGeology.rock(seed, x, z).getDefaultState();
        boolean changed = false;
        for (int dz = -7; dz <= 7; dz++) for (int dx = -7; dx <= 7; dx++) {
            int wx = x + dx, wz = z + dz;
            int floor = terrain.firstAir(wx, wz);
            if (Math.abs(floor - y) > 7) continue;
            var base = new BlockPos(wx, floor - 1, wz);
            var surface = world.getBlockState(base);
            if (!surface.isSolidBlock(world, base) || !surface.getFluidState().isEmpty()
                || !world.getFluidState(base.up()).isEmpty()) continue;
            int u = eastWest ? dx : dz, v = eastWest ? dz : dx;
            for (int rise = Math.min(0, floor - y); rise <= height; rise++) {
                var pos = new BlockPos(wx, y + rise, wz);
                if (pos.getY() < floor || !solid(shape, u, v, Math.max(0, rise), height)) continue;
                BlockState old = world.getBlockState(pos);
                if (old.isAir() || old.isIn(BlockTags.REPLACEABLE_BY_TREES) && old.getFluidState().isEmpty())
                    changed |= world.setBlockState(pos, rock, 2);
            }
        }
        return changed;
    }

    static boolean solid(Shape shape, int x, int z, int y, int height) {
        double t = y / (double)height;
        return switch (shape) {
            case ARCH -> {
                double outer = x * x / 49.0 + t * t;
                double inner = x * x / 16.0 + t * t / 0.53;
                yield Math.abs(z) <= (t < 0.4 ? 2 : 1) && outer <= 1 && inner >= 1;
            }
            case PLATEAU -> {
                double radius = t < 0.14 ? 7 : t < 0.34 ? 6 : t < 0.85 ? 5.2 : 4.8;
                yield x * x + z * z * 1.3 <= radius * radius;
            }
            case SPIRE -> {
                double radius = 0.9 + 4.5 * (1 - t);
                double lean = t * 2;
                yield (x - lean) * (x - lean) + z * z <= radius * radius;
            }
        };
    }
}

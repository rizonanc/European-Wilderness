package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.WorldHeightProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.PreparedFallback;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

public final class TropicalDecorationFeature extends Feature<DefaultFeatureConfig> {
    private static final int[][] DIRECTIONS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
    private static final int[] OASIS_PROBES = {-24, 0, 24};

    public TropicalDecorationFeature() {
        super(DefaultFeatureConfig.CODEC);
    }

    @Override
    public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        if (!(context.getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return false;
        WorldSession session = WorldSession.current();
        if (session == null || !session.isActive() || !session.profile().tropicalLandforms()) return false;
        var world = context.getWorld();
        int x = context.getOrigin().getX() & ~15, z = context.getOrigin().getZ() & ~15;
        long seed = session.worldSeed();
        var oasis = TropicalLandforms.oasis(
                seed, Math.floorDiv(x, TropicalLandforms.OASIS_CELL), Math.floorDiv(z, TropicalLandforms.OASIS_CELL));
        boolean nearOasis = Math.abs(oasis.x() - x - 8) <= TropicalLandforms.OASIS_REACH + 8
                && Math.abs(oasis.z() - z - 8) <= TropicalLandforms.OASIS_REACH + 8;
        var random = TropicalLandforms.random(seed, x >> 4, z >> 4, 23);
        int palmX = x + random.nextInt(4, 12), palmZ = z + random.nextInt(4, 12);
        boolean island =
                world.getBiome(new BlockPos(palmX, 64, palmZ)).matchesKey(TerrainDiffusionBiomeSource.PALM_ISLAND);
        if (!nearOasis && !island) return false;

        var terrain = new Terrain(session, world.getTopYInclusive());
        try {
            int waterY = nearOasis ? oasisLevel(session, terrain, oasis) : Integer.MIN_VALUE;
            boolean changed = waterY != Integer.MIN_VALUE && shapeOasis(world, terrain, oasis, x, z, waterY);
            if (waterY != Integer.MIN_VALUE) {
                double angle =
                        TropicalLandforms.random(seed, oasis.x(), oasis.z(), 24).nextDouble() * Math.TAU;
                for (int i = 0; i < 4; i++) {
                    double theta = angle + i * Math.TAU / 4;
                    int rootX = oasis.x() + (int) Math.round(Math.cos(theta) * oasis.radius() * 0.72);
                    int rootZ = oasis.z() + (int) Math.round(Math.sin(theta) * oasis.radius() * 0.72);
                    if ((rootX & ~15) != x || (rootZ & ~15) != z) continue;
                    int air = waterY + TropicalLandforms.oasisOffset(oasis.distance(rootX, rootZ));
                    if (air > waterY) changed |= palm(world, new BlockPos(rootX, air, rootZ), seed);
                }
            }
            if (island && random.nextDouble() < 0.52 && terrain.biome(palmX, palmZ) == TropicalLandforms.PALM_ISLAND) {
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, palmX, palmZ);
                changed |= palm(world, new BlockPos(palmX, y, palmZ), seed);
            }
            return changed;
        } catch (PreparedFallback.PreparedTileMissingException unavailable) {
            return false;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not decorate tropical terrain at " + x + ", " + z, failure);
        }
    }

    private static int oasisLevel(WorldSession session, Terrain terrain, TropicalLandforms.Oasis oasis)
            throws Exception {
        int level = terrain.firstAir(oasis.x(), oasis.z()) - 1;
        if (level < 72 || level > terrain.top - 18) return Integer.MIN_VALUE;
        for (int dz : OASIS_PROBES)
            for (int dx : OASIS_PROBES) {
                int x = oasis.x() + dx, z = oasis.z() + dz;
                if (terrain.biome(x, z) != 5 || Math.abs(terrain.firstAir(x, z) - 1 - level) > 6)
                    return Integer.MIN_VALUE;
                var river = session.riverOverlay();
                if (river != null && (river.waterY(x, z) != Integer.MIN_VALUE || river.waterfallAt(x, z) != null))
                    return Integer.MIN_VALUE;
            }
        return level;
    }

    private static boolean shapeOasis(
            StructureWorldAccess world, Terrain terrain, TropicalLandforms.Oasis oasis, int x, int z, int level)
            throws Exception {
        boolean changed = false;
        for (int dz = 0; dz < 16; dz++)
            for (int dx = 0; dx < 16; dx++) {
                int wx = x + dx, wz = z + dz;
                double distance = oasis.distance(wx, wz);
                if (distance >= 1) continue;
                int oldAir = terrain.firstAir(wx, wz) + 1;
                int air = level + TropicalLandforms.oasisOffset(distance);
                if (distance >= 0.82) {
                    double rim = EuropeanEcology.smoothstep(0.82, 1, distance);
                    air = (int) Math.round(air + (Math.max(level + 1, oldAir) - air) * rim);
                }
                var ground = distance < 0.58 || distance > 0.82 ? Blocks.SAND : Blocks.GRASS_BLOCK;
                for (int y = Math.min(oldAir, air) - 2; y <= Math.max(oldAir - 1, Math.max(air - 1, level)); y++) {
                    var pos = new BlockPos(wx, y, wz);
                    BlockState material = y < air - 1
                            ? Blocks.SANDSTONE.getDefaultState()
                            : y == air - 1
                                    ? ground.getDefaultState()
                                    : y <= level ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState();
                    changed |= world.setBlockState(pos, material, 2);
                }
                if (ground == Blocks.GRASS_BLOCK && air > level) {
                    double growth = EuropeanEcology.hashNoise01(wx, wz, (int) oasis.seed());
                    var pos = new BlockPos(wx, air, wz);
                    if (world.getBlockState(pos).isAir()) {
                        if (air == level + 1
                                && growth < 0.18
                                && Blocks.SUGAR_CANE.getDefaultState().canPlaceAt(world, pos)) {
                            for (int stem = 0;
                                    stem < 2
                                            && world.getBlockState(pos.up(stem)).isAir();
                                    stem++) {
                                changed |= world.setBlockState(pos.up(stem), Blocks.SUGAR_CANE.getDefaultState(), 2);
                            }
                        } else if (growth < 0.4)
                            changed |= world.setBlockState(pos, Blocks.SHORT_GRASS.getDefaultState(), 2);
                    }
                }
            }
        return changed;
    }

    private static boolean palm(StructureWorldAccess world, BlockPos root, long seed) {
        if (root.getY() + 13 > world.getTopYInclusive()) return false;
        var support = world.getBlockState(root.down());
        if (!support.isOf(Blocks.GRASS_BLOCK) && !support.isOf(Blocks.SAND) && !support.isOf(Blocks.DIRT)) return false;
        var random = TropicalLandforms.random(seed, root.getX(), root.getZ(), 25);
        int height = random.nextInt(7, 11);
        var lean = DIRECTIONS[random.nextInt(DIRECTIONS.length) & ~1];
        Map<BlockPos, BlockState> shape = new LinkedHashMap<>();
        var vertical = Blocks.JUNGLE_LOG.getDefaultState();
        var horizontal = vertical.with(Properties.AXIS, lean[0] == 0 ? Direction.Axis.Z : Direction.Axis.X);
        var leaves = Blocks.JUNGLE_LEAVES.getDefaultState().with(Properties.PERSISTENT, true);
        int x = 0, z = 0;
        for (int y = 0; y < height; y++) {
            if (y > 1 && y % 2 == 0) {
                shape.put(root.add(x, y, z), horizontal);
                x += lean[0];
                z += lean[1];
            }
            shape.put(root.add(x, y, z), vertical);
        }
        var crown = root.add(x, height, z);
        shape.put(crown, leaves);
        shape.put(crown.up(), leaves);
        for (int[] direction : DIRECTIONS) {
            int length = direction[0] * direction[1] == 0 ? random.nextInt(4, 6) : random.nextInt(3, 5);
            for (int step = 1; step <= length; step++) {
                int y = step == 1 ? 1 : step == length ? -1 : 0;
                var tip = crown.add(direction[0] * step, y, direction[1] * step);
                shape.putIfAbsent(tip, leaves);
                if (step == length) shape.putIfAbsent(tip.up(), leaves);
            }
        }
        for (var entry : shape.entrySet()) {
            var pos = entry.getKey();
            if (!world.isValidForSetBlock(pos)) return false;
            var state = world.getBlockState(pos);
            if (!state.isAir() && !state.isOf(Blocks.SHORT_GRASS) && !state.isOf(Blocks.JUNGLE_LEAVES)) return false;
        }
        boolean changed = false;
        for (var entry : shape.entrySet()) changed |= world.setBlockState(entry.getKey(), entry.getValue(), 2);
        return changed;
    }

    private static final class Terrain {
        private final WorldSession session;
        private final int tileSize;
        private final int top;
        private final Map<TerrainTileKey, TerrainTile> tiles = new HashMap<>();

        Terrain(WorldSession session, int top) {
            this.session = session;
            this.top = top;
            tileSize = session.repository().tileSize();
        }

        private TerrainTile tile(int x, int z) throws Exception {
            var key = TerrainTileKey.forBlock(x, z, tileSize, session.worldSeed(), session.fingerprint());
            var tile = tiles.get(key);
            if (tile == null) {
                tile = PreparedFallback.requiredTilesFor(session.repository(), PreparedFallback.mode())
                        .get(key);
                tiles.put(key, tile);
            }
            return tile;
        }

        short biome(int x, int z) throws Exception {
            var tile = tile(x, z);
            return tile.biomeAt(tile.key().localX(x), tile.key().localZ(z));
        }

        int firstAir(int x, int z) throws Exception {
            var tile = tile(x, z);
            return MountainHeightField.sampleForWorld(
                            session.worldSeed(),
                            x,
                            z,
                            tile.heightAt(tile.key().localX(x), tile.key().localZ(z)),
                            session.profile().worldScale(),
                            WorldHeightProfile.surfaceCapY(top))
                    .surfaceY();
        }
    }
}

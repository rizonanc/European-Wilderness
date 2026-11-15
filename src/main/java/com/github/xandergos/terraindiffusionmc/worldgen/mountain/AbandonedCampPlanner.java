package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AbandonedCampPlanner {
    private static final long CAMP_SALT = -919676771051557371L;
    private static final int TILE_MARGIN = 20;
    private static final int MAX_CANDIDATES = 16;
    private static final int MIN_SOURCE_METRES = 950;
    private static final int MIN_GROUND_Y = 150;

    private AbandonedCampPlanner() {}

    public static Optional<AbandonedCampPlanner.CampPlan> plan(
            long seed, TerrainTile tile, int worldScale, int worldTopYInclusive, MountainPassPlanner.PassPlan pass) {
        Objects.requireNonNull(tile, "tile");
        Objects.requireNonNull(pass, "pass");
        TerrainTileKey key = tile.key();
        if (key.seed() == seed && key.tileX() == pass.tileX() && key.tileZ() == pass.tileZ()) {
            int size = key.tileSize();
            if (size >= 96 && pass.route().size() >= 12) {
                long identity =
                        mix(
                                seed
                                        ^ pass.id()
                                        ^ -919676771051557371L
                                        ^ key.tileX() * -7046029254386353131L
                                        ^ key.tileZ() * -3335678366873096957L);
                if ((identity & 1L) != 0L) {
                    return Optional.empty();
                }

                AbandonedCampPlanner.Family family =
                        AbandonedCampPlanner.Family.values()[
                                (int)
                                        Long.remainderUnsigned(
                                                identity >>> 2, AbandonedCampPlanner.Family.values().length)];
                int tents = family == AbandonedCampPlanner.Family.EXPEDITION ? 5 : ((identity & 2L) == 0L ? 4 : 5);
                List<MountainPassPlanner.RoutePoint> route = pass.route();
                int baseIndex = route.size() / 2;
                int stride = Math.max(1, (route.size() - 8) / 16);
                int considered = 0;

                for (int delta = 0; considered < 16; delta += stride) {
                    int index = baseIndex + ((considered & 1) == 0 ? delta : -delta);
                    considered++;
                    if (index >= 4 && index < route.size() - 4) {
                        MountainPassPlanner.RoutePoint anchor = route.get(index);
                        MountainPassPlanner.RoutePoint before = route.get(index - 4);
                        MountainPassPlanner.RoutePoint after = route.get(index + 4);
                        boolean alongX = Math.abs(after.x() - before.x()) >= Math.abs(after.z() - before.z());
                        int side = (identity >>> 5 & 1L) == 0L ? 1 : -1;
                        int centerX = anchor.x() + (alongX ? 0 : side * 20);
                        int centerZ = anchor.z() + (alongX ? side * 20 : 0);
                        Optional<AbandonedCampPlanner.CampPlan> candidate =
                                atSite(
                                        seed,
                                        tile,
                                        worldScale,
                                        worldTopYInclusive,
                                        identity,
                                        family,
                                        tents,
                                        anchor.x(),
                                        anchor.z(),
                                        centerX,
                                        centerZ,
                                        alongX);
                        if (candidate.isPresent()) {
                            return candidate;
                        }
                    }
                }

                return Optional.empty();
            } else {
                return Optional.empty();
            }
        } else {
            throw new IllegalArgumentException("Pass and terrain tile must share generation identity");
        }
    }

    private static Optional<AbandonedCampPlanner.CampPlan> atSite(
            long seed,
            TerrainTile tile,
            int scale,
            int top,
            long identity,
            AbandonedCampPlanner.Family family,
            int count,
            int routeX,
            int routeZ,
            int centerX,
            int centerZ,
            boolean alongX) {
        TerrainTileKey key = tile.key();
        int size = key.tileSize();
        int localX = centerX - key.blockStartX();
        int localZ = centerZ - key.blockStartZ();
        if (localX >= 20 && localZ >= 20 && localX < size - 20 && localZ < size - 20) {
            int centerY = ground(seed, tile, scale, top, centerX, centerZ);
            if (centerY >= 150 && centerY <= top - 9) {
                int lastY = ground(seed, tile, scale, top, routeX, routeZ);
                if (Math.abs(centerY - lastY) > 8) {
                    return Optional.empty();
                }

                for (int step = 1; step <= 20; step++) {
                    int x = routeX + (alongX ? 0 : Integer.signum(centerX - routeX) * step);
                    int z = routeZ + (alongX ? Integer.signum(centerZ - routeZ) * step : 0);
                    if (wet(tile, x, z)) {
                        return Optional.empty();
                    }

                    int y = ground(seed, tile, scale, top, x, z);
                    if (Math.abs(y - lastY) > 2) {
                        return Optional.empty();
                    }

                    lastY = y;
                }

                int[][] offsets =
                        new int[][] {
                            {-9, -7},
                            {9, -7},
                            {-9, 7},
                            {9, 7},
                            family == AbandonedCampPlanner.Family.EXPEDITION ? new int[] {0, 0} : new int[] {0, 15}
                        };
                List<AbandonedCampPlanner.TentSite> sites = new ArrayList<>(count);

                for (int index = 0; index < count; index++) {
                    int u = offsets[index][0];
                    int v = offsets[index][1];
                    int x = centerX + (alongX ? u : v);
                    int z = centerZ + (alongX ? v : u);
                    boolean large = family == AbandonedCampPlanner.Family.EXPEDITION && index == count - 1;
                    int halfWidth = large ? 2 : 1;
                    int halfLength = large ? 3 : 2;
                    int y = ground(seed, tile, scale, top, x, z);
                    if (Math.abs(y - centerY) > 2 || y > top - 7) {
                        return Optional.empty();
                    }

                    for (int dz = -halfLength - 1; dz <= halfLength + 1; dz++) {
                        for (int dx = -halfWidth - 1; dx <= halfWidth + 1; dx++) {
                            int sampleX = x + (alongX ? dz : dx);
                            int sampleZ = z + (alongX ? dx : dz);
                            if (!inTileWithMargin(key, sampleX, sampleZ, 2)) {
                                return Optional.empty();
                            }

                            int sampleY = ground(seed, tile, scale, top, sampleX, sampleZ);
                            if (Math.abs(sampleY - y) > 1 || Math.abs(sampleY - centerY) > 3) {
                                return Optional.empty();
                            }

                            if (wet(tile, sampleX, sampleZ)) {
                                return Optional.empty();
                            }
                        }
                    }

                    sites.add(new AbandonedCampPlanner.TentSite(x, y, z, halfWidth, halfLength));
                }

                if (wet(tile, centerX, centerZ)) {
                    return Optional.empty();
                }

                Map<AbandonedCampPlanner.Position, AbandonedCampPlanner.Voxel> result = new LinkedHashMap<>();

                for (int i = 0; i < sites.size(); i++) {
                    buildTent(result, sites.get(i), alongX, family, identity, i);
                }

                int fireX = centerX + (family == AbandonedCampPlanner.Family.EXPEDITION && !alongX ? 7 : 0);
                int fireZ = centerZ + (family == AbandonedCampPlanner.Family.EXPEDITION && alongX ? 7 : 0);
                add(
                        result,
                        fireX,
                        ground(seed, tile, scale, top, fireX, fireZ),
                        fireZ,
                        AbandonedCampPlanner.Material.CAMPFIRE_UNLIT);
                add(
                        result,
                        fireX + 2,
                        ground(seed, tile, scale, top, fireX + 2, fireZ),
                        fireZ,
                        AbandonedCampPlanner.Material.BARREL);
                add(
                        result,
                        fireX - 2,
                        ground(seed, tile, scale, top, fireX - 2, fireZ),
                        fireZ,
                        AbandonedCampPlanner.Material.SPRUCE_PLANKS);
                add(
                        result,
                        fireX + 3,
                        ground(seed, tile, scale, top, fireX + 3, fireZ + 3),
                        fireZ + 3,
                        AbandonedCampPlanner.Material.BARREL);

                for (int[] offset : new int[][] {{-3, 3}, {3, -3}}) {
                    int x = fireX + offset[0];
                    int z = fireZ + offset[1];
                    add(
                            result,
                            x,
                            ground(seed, tile, scale, top, x, z),
                            z,
                            AbandonedCampPlanner.Material.SPRUCE_PLANKS);
                }

                return Optional.of(
                        new AbandonedCampPlanner.CampPlan(identity, family, count, List.copyOf(result.values())));
            } else {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
    }

    private static void buildTent(
            Map<AbandonedCampPlanner.Position, AbandonedCampPlanner.Voxel> out,
            AbandonedCampPlanner.TentSite site,
            boolean alongX,
            AbandonedCampPlanner.Family family,
            long identity,
            int tentIndex) {
        AbandonedCampPlanner.Material canvas =
                switch (family) {
                    case EXPEDITION ->
                            tentIndex == 4
                                    ? AbandonedCampPlanner.Material.GRAY_WOOL
                                    : AbandonedCampPlanner.Material.BROWN_WOOL;
                    case ROAD -> AbandonedCampPlanner.Material.BROWN_WOOL;
                    case SNOW_RUINED -> AbandonedCampPlanner.Material.WHITE_WOOL;
                };

        for (int length = -site.halfLength; length <= site.halfLength; length++) {
            for (int width = -site.halfWidth; width <= site.halfWidth; width++) {
                int x = site.x + (alongX ? length : width);
                int z = site.z + (alongX ? width : length);
                int roofY = site.y + site.halfWidth + 2 - Math.abs(width);
                long wear = mix(identity ^ tentIndex * -4658895280553007687L ^ length * 41L ^ width * 73L);
                boolean accent =
                        family == AbandonedCampPlanner.Family.SNOW_RUINED
                                && length == ((tentIndex & 1) == 0 ? -1 : 1)
                                && width == 0;
                boolean missing = family == AbandonedCampPlanner.Family.SNOW_RUINED && !accent && (wear & 7L) < 2L;
                if (!missing) {
                    AbandonedCampPlanner.Material roof =
                            family != AbandonedCampPlanner.Family.SNOW_RUINED || !accent && (wear >>> 3 & 7L) != 0L
                                    ? canvas
                                    : AbandonedCampPlanner.Material.GRAY_WOOL;
                    add(out, x, roofY, z, roof);
                }

                if (length == site.halfLength && width != 0) {
                    add(out, x, site.y, z, canvas);
                }
            }
        }

        for (int length : new int[] {-site.halfLength, site.halfLength}) {
            for (int width : new int[] {-site.halfWidth, site.halfWidth}) {
                int x = site.x + (alongX ? length : width);
                int z = site.z + (alongX ? width : length);
                add(out, x, site.y, z, AbandonedCampPlanner.Material.SPRUCE_FENCE);
            }

            int x = site.x + (alongX ? length : 0);
            int z = site.z + (alongX ? 0 : length);
            add(out, x, site.y + site.halfWidth + 1, z, AbandonedCampPlanner.Material.SPRUCE_LOG);
        }

        if (family == AbandonedCampPlanner.Family.SNOW_RUINED) {
            add(out, site.x, site.y, site.z, AbandonedCampPlanner.Material.SNOW);
        }
    }

    private static int ground(long seed, TerrainTile tile, int scale, int top, int x, int z) {
        TerrainTileKey key = tile.key();
        short source = tile.heightAt(key.localX(x), key.localZ(z));
        return MountainHeightField.sampleForWorld(seed, x, z, source, scale, WorldHeightProfile.surfaceCapY(top))
                .surfaceY();
    }

    private static boolean wet(TerrainTile tile, int x, int z) {
        TerrainTileKey key = tile.key();
        short metres = tile.heightAt(key.localX(x), key.localZ(z));
        short biome = tile.biomeAt(key.localX(x), key.localZ(z));
        return metres < 950 || biome == 6 || biome == 41 || biome == 44 || biome == 46 || biome == 48;
    }

    private static boolean inTileWithMargin(TerrainTileKey key, int x, int z, int margin) {
        int localX = x - key.blockStartX();
        int localZ = z - key.blockStartZ();
        return localX >= margin
                && localZ >= margin
                && localX < key.tileSize() - margin
                && localZ < key.tileSize() - margin;
    }

    private static void add(
            Map<AbandonedCampPlanner.Position, AbandonedCampPlanner.Voxel> out,
            int x,
            int y,
            int z,
            AbandonedCampPlanner.Material material) {
        out.putIfAbsent(new AbandonedCampPlanner.Position(x, y, z), new AbandonedCampPlanner.Voxel(x, y, z, material));
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * -4658895280553007687L;
        value = (value ^ value >>> 27) * -7723592293110705685L;
        return value ^ value >>> 31;
    }

    public record CampPlan(
            long id, AbandonedCampPlanner.Family family, int tentCount, List<AbandonedCampPlanner.Voxel> voxels) {
        public CampPlan(
                long id, AbandonedCampPlanner.Family family, int tentCount, List<AbandonedCampPlanner.Voxel> voxels) {
            Objects.requireNonNull(family, "family");
            if (tentCount >= 4 && tentCount <= 5) {
                voxels = List.copyOf(voxels);
                this.id = id;
                this.family = family;
                this.tentCount = tentCount;
                this.voxels = voxels;
            } else {
                throw new IllegalArgumentException("tentCount must be 4..5");
            }
        }

        public List<AbandonedCampPlanner.Voxel> voxelsForChunk(int chunkX, int chunkZ) {
            return this.voxels.stream()
                    .filter(voxel -> Math.floorDiv(voxel.x(), 16) == chunkX && Math.floorDiv(voxel.z(), 16) == chunkZ)
                    .toList();
        }
    }

    public enum Family {
        EXPEDITION,
        ROAD,
        SNOW_RUINED;
    }

    public enum Material {
        SPRUCE_LOG,
        SPRUCE_FENCE,
        SPRUCE_PLANKS,
        GRAY_WOOL,
        BROWN_WOOL,
        WHITE_WOOL,
        SNOW,
        BARREL,
        CAMPFIRE_UNLIT;
    }

    private record Position(int x, int y, int z) {}

    private record TentSite(int x, int y, int z, int halfWidth, int halfLength) {}

    public record Voxel(int x, int y, int z, AbandonedCampPlanner.Material material) {
        public Voxel {
            Objects.requireNonNull(material, "material");
        }
    }
}

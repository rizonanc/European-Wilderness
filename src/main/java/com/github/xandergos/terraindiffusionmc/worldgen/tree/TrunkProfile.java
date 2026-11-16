package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public record TrunkProfile(List<TrunkProfile.Point> wood, TreeAge.Footprint base, int maxRadius) {
    public static final Comparator<TrunkProfile.Point> POINT_ORDER = Comparator.comparingInt(TrunkProfile.Point::y)
            .thenComparingInt(TrunkProfile.Point::x)
            .thenComparingInt(TrunkProfile.Point::z);
    private static final long SEED_SALT = 7640891576956012809L;

    public TrunkProfile(List<TrunkProfile.Point> wood, TreeAge.Footprint base, int maxRadius) {
        List<TrunkProfile.Point> ordered = new ArrayList<>(List.copyOf(Objects.requireNonNull(wood, "wood")));
        base = Objects.requireNonNull(base, "base");
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("trunk wood must not be empty");
        }

        if (maxRadius >= 0 && maxRadius <= 4) {
            Set<TrunkProfile.Point> unique = new HashSet<>();
            boolean containsOrigin = false;
            int derivedRadius = 0;
            int minX = -base.width() / 2;
            int minZ = -base.depth() / 2;
            int maxX = minX + base.width() - 1;
            int maxZ = minZ + base.depth() - 1;
            int baseCells = 0;

            for (TrunkProfile.Point point : ordered) {
                if (!unique.add(point)) {
                    throw new IllegalArgumentException("trunk wood must be unique");
                }

                if (point.y() >= 0
                        && point.y() < 36
                        && point.x() >= -4
                        && point.x() <= 4
                        && point.z() >= -4
                        && point.z() <= 4) {
                    long rootReach = outsideDistance(point.x(), minX, maxX) + outsideDistance(point.z(), minZ, maxZ);
                    if (rootReach <= 2L && (rootReach <= 0L || point.y() < 2)) {
                        if (point.y() == 0 && rootReach == 0L) {
                            baseCells++;
                        }

                        containsOrigin |= point.equals(new TrunkProfile.Point(0, 0, 0));
                        int pointRadius = (int) Math.max(Math.abs((long) point.x()), Math.abs((long) point.z()));
                        derivedRadius = Math.max(derivedRadius, pointRadius);
                        continue;
                    }

                    throw new IllegalArgumentException("trunk wood exceeds root-flare bounds");
                }

                throw new IllegalArgumentException("trunk wood exceeds geometry bounds");
            }

            if (!containsOrigin) {
                throw new IllegalArgumentException("trunk wood must contain the base origin");
            }

            if (maxRadius != derivedRadius) {
                throw new IllegalArgumentException("trunk radius must match wood coordinates");
            }

            int maximumCornerOmissions =
                    base.width() >= 3 && base.depth() >= 3 && Math.max(base.width(), base.depth()) >= 4 ? 2 : 0;
            if (baseCells < base.width() * base.depth() - maximumCornerOmissions) {
                throw new IllegalArgumentException("trunk base must match selected footprint");
            }

            ordered.sort(POINT_ORDER);
            wood = List.copyOf(ordered);
            this.wood = wood;
            this.base = base;
            this.maxRadius = maxRadius;
            return;
        } else {
            throw new IllegalArgumentException("trunk radius must be between zero and four");
        }
    }

    public static TrunkProfile generate(TreeSpecies species, TreeAge age, long seed, int height) {
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(age, "age");
        if (height >= 1 && height <= 36) {
            Random random = new Random(mix(seed, species, age));
            List<TreeAge.Footprint> options = age.footprints();
            TreeAge.Footprint selected = options.get(random.nextInt(options.size()));
            if (selected.width() != selected.depth() && random.nextBoolean()) {
                selected = selected.rotated();
            }

            TrunkProfile.Dimensions base = new TrunkProfile.Dimensions(selected.width(), selected.depth());
            TrunkProfile.Dimensions first = shrinkLarger(base, random.nextBoolean());
            TrunkProfile.Dimensions second = shrinkLarger(first, random.nextBoolean());
            TrunkProfile.Dimensions upper = upperDimensions(age);
            int firstLevel = Math.max(1, (int) Math.ceil(height * 0.2));
            int secondLevel = Math.max(firstLevel + 1, (int) Math.ceil(height * 0.45));
            int upperLevel = Math.max(secondLevel + 1, (int) Math.ceil(height * 0.7));
            firstLevel = Math.min(firstLevel, height);
            secondLevel = Math.min(secondLevel, height);
            upperLevel = Math.min(upperLevel, height);
            Set<TrunkProfile.Point> points = new HashSet<>();
            long cornerSeed = random.nextLong();

            for (int y = 0; y < height; y++) {
                TrunkProfile.Dimensions dimensions =
                        y < firstLevel ? base : (y < secondLevel ? first : (y < upperLevel ? second : upper));
                addSection(points, dimensions, y, cornerSeed);
            }

            if (species == TreeSpecies.OAK && (age == TreeAge.OLD || age == TreeAge.RARE_LARGE)) {
                addRootFlares(points, base, height, random);
            }

            List<TrunkProfile.Point> ordered = new ArrayList<>(points);
            ordered.sort(POINT_ORDER);
            int radius = ordered.stream()
                    .mapToInt(point -> Math.max(Math.abs(point.x()), Math.abs(point.z())))
                    .max()
                    .orElseThrow();
            return new TrunkProfile(ordered, selected, radius);
        } else {
            throw new IllegalArgumentException("trunk height must be between one and 36");
        }
    }

    /** Same seeded base selection as generate, without allocating trunk voxels. */
    public static TreeAge.Footprint previewFootprint(TreeSpecies species, TreeAge age, long seed) {
        Random random = new Random(mix(seed, species, age));
        var options = age.footprints();
        var selected = options.get(random.nextInt(options.size()));
        return selected.width() != selected.depth() && random.nextBoolean() ? selected.rotated() : selected;
    }

    private static TrunkProfile.Dimensions shrinkLarger(TrunkProfile.Dimensions dimensions, boolean preferX) {
        int width = dimensions.width;
        int depth = dimensions.depth;
        if ((width <= depth || width <= 1) && (width != depth || !preferX || width <= 1)) {
            if (depth > 1) {
                depth--;
            } else if (width > 1) {
                width--;
            }
        } else {
            width--;
        }

        return new TrunkProfile.Dimensions(width, depth);
    }

    private static TrunkProfile.Dimensions upperDimensions(TreeAge age) {
        int side = age == TreeAge.RARE_LARGE ? 2 : 1;
        return new TrunkProfile.Dimensions(side, side);
    }

    private static void addSection(
            Set<TrunkProfile.Point> points, TrunkProfile.Dimensions dimensions, int y, long cornerSeed) {
        int minX = -dimensions.width / 2;
        int minZ = -dimensions.depth / 2;
        int maxX = minX + dimensions.width - 1;
        int maxZ = minZ + dimensions.depth - 1;
        int omittedCorners =
                dimensions.width >= 3 && dimensions.depth >= 3 && Math.max(dimensions.width, dimensions.depth) >= 4
                        ? (int) Math.floorMod(cornerSeed ^ dimensions.width * 31L ^ dimensions.depth * 17L, 3L)
                        : 0;
        int cornerOffset = (int) Math.floorMod(cornerSeed >>> 7, 4L);

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int corner = cornerIndex(x, z, minX, maxX, minZ, maxZ);
                if (corner < 0 || !isOmitted(corner, cornerOffset, omittedCorners)) {
                    points.add(new TrunkProfile.Point(x, y, z));
                }
            }
        }
    }

    private static int cornerIndex(int x, int z, int minX, int maxX, int minZ, int maxZ) {
        if (x == minX && z == minZ) {
            return 0;
        } else if (x == minX && z == maxZ) {
            return 1;
        } else if (x == maxX && z == maxZ) {
            return 2;
        } else {
            return x == maxX && z == minZ ? 3 : -1;
        }
    }

    private static boolean isOmitted(int corner, int offset, int count) {
        for (int index = 0; index < count; index++) {
            if (corner == (offset + index * 2) % 4) {
                return true;
            }
        }

        return false;
    }

    private static long outsideDistance(int coordinate, int minimum, int maximum) {
        if (coordinate < minimum) {
            return (long) minimum - coordinate;
        } else {
            return coordinate > maximum ? (long) coordinate - maximum : 0L;
        }
    }

    private static void addRootFlares(
            Set<TrunkProfile.Point> points, TrunkProfile.Dimensions base, int height, Random random) {
        int minX = -base.width / 2;
        int minZ = -base.depth / 2;
        int maxX = minX + base.width - 1;
        int maxZ = minZ + base.depth - 1;
        int start = random.nextInt(4);
        int count = 2 + random.nextInt(3);

        for (int index = 0; index < count; index++) {
            switch ((start + index) % 4) {
                case 0:
                    addFlare(points, maxX, 0, 1, 0, height);
                    break;
                case 1:
                    addFlare(points, 0, maxZ, 0, 1, height);
                    break;
                case 2:
                    addFlare(points, minX, 0, -1, 0, height);
                    break;
                case 3:
                    addFlare(points, 0, minZ, 0, -1, height);
                    break;
                default:
                    throw new IllegalStateException("unreachable root direction");
            }
        }
    }

    private static void addFlare(Set<TrunkProfile.Point> points, int x, int z, int dx, int dz, int height) {
        points.add(new TrunkProfile.Point(x + dx, 0, z + dz));
        points.add(new TrunkProfile.Point(x + 2 * dx, 0, z + 2 * dz));
        if (height > 1) {
            points.add(new TrunkProfile.Point(x + dx, 1, z + dz));
        }
    }

    private static long mix(long seed, TreeSpecies species, TreeAge age) {
        long value = seed ^ 7640891576956012809L;
        value ^= -7046029254386353131L * (species.ordinal() + 1L);
        value ^= -4417276706812531889L * (age.ordinal() + 1L);
        value ^= value >>> 30;
        value *= -4658895280553007687L;
        value ^= value >>> 27;
        value *= -7723592293110705685L;
        return value ^ value >>> 31;
    }

    private record Dimensions(int width, int depth) {
        private Dimensions(int width, int depth) {
            if (width >= 1 && depth >= 1 && width <= 4 && depth <= 4) {
                this.width = width;
                this.depth = depth;
            } else {
                throw new IllegalArgumentException("trunk dimensions must be between one and four");
            }
        }
    }

    public record Point(int x, int y, int z) {}
}

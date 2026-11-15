package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public final class TreeShapeGenerator {
    public static final Comparator<TreeShapeGenerator.Voxel> VOXEL_ORDER =
            Comparator.comparingInt(TreeShapeGenerator.Voxel::y)
                    .thenComparingInt(TreeShapeGenerator.Voxel::x)
                    .thenComparingInt(TreeShapeGenerator.Voxel::z);
    private static final int[][] DIRECTIONS =
            new int[][] {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int MAX_HEIGHT = 36;
    private static final int MAX_RADIUS = 8;
    private static final int MAX_VOXELS = 12000;

    private TreeShapeGenerator() {}

    /** Cheap deterministic dimensions for distant foliage; no voxel sets or chunk features. */
    public record Preview(int height, int crownBottom, int radius) {}

    public static Preview preview(TreeSpecies species, TreeAge age, long seed) {
        Random random = new Random(shapeSeed(species, age, seed));
        int height = selectHeight(species, age, random);
        if (species == TreeSpecies.SPRUCE || species == TreeSpecies.PINE) {
            int bottom = species == TreeSpecies.SPRUCE ? 5 + random.nextInt(3) : height / 2;
            if (age == TreeAge.YOUNG) bottom = Math.max(4, bottom - 1);
            return new Preview(height, bottom, age == TreeAge.YOUNG ? 3 : 4);
        }
        int radius = species == TreeSpecies.OAK ? (age == TreeAge.YOUNG ? 3 : 4 + random.nextInt(2)) : 3;
        return new Preview(height, Math.max(4, height - 10), radius + 1);
    }

    public static TreeShapeGenerator.Shape generate(TreeSpecies species, TreeAge age, long seed) {
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(age, "age");
        Random random = new Random(shapeSeed(species, age, seed));
        int height = selectHeight(species, age, random);
        int trunkHeight = Math.max(1, height - 2);
        TrunkProfile profile = TrunkProfile.generate(species, age, seed, trunkHeight);
        Set<TreeShapeGenerator.Point> logs = new HashSet<>();
        Set<TreeShapeGenerator.Point> leaves = new HashSet<>();

        for (TrunkProfile.Point point : profile.wood()) {
            logs.add(new TreeShapeGenerator.Point(point.x(), point.y(), point.z()));
        }

        int trunkTop = trunkHeight - 1;
        if (species != TreeSpecies.OAK && species != TreeSpecies.BIRCH) {
            addConiferCrown(species, age, random, height, trunkTop, logs, leaves);
        } else {
            addDeciduousCrown(species, age, random, height, trunkTop, logs, leaves);
        }

        leaves.removeAll(logs);
        int lowestLeaf = (species == TreeSpecies.PINE || species == TreeSpecies.SPRUCE) ? 3 : 4;
        leaves.removeIf(
                point -> Math.abs(point.x) > 8 || Math.abs(point.z) > 8 || point.y < lowestLeaf || point.y >= height);
        Map<TreeShapeGenerator.Point, Integer> distances = leafDistances(logs, leaves);
        List<TreeShapeGenerator.Voxel> voxels = new ArrayList<>(logs.size() + distances.size());

        for (TreeShapeGenerator.Point point : logs) {
            voxels.add(new TreeShapeGenerator.Voxel(point.x, point.y, point.z, true, 0));
        }

        for (TreeShapeGenerator.Point point : leaves) {
            Integer distance = distances.get(point);
            if (distance != null) {
                voxels.add(new TreeShapeGenerator.Voxel(point.x, point.y, point.z, false, distance));
            }
        }

        voxels.sort(VOXEL_ORDER);
        int actualHeight = voxels.stream().mapToInt(voxel -> voxel.y + 1).max().orElseThrow();
        int radius =
                voxels.stream().mapToInt(voxel -> Math.max(Math.abs(voxel.x), Math.abs(voxel.z))).max().orElseThrow();
        if (actualHeight <= 36 && radius <= 8 && voxels.size() <= 12000) {
            return new TreeShapeGenerator.Shape(voxels, actualHeight, radius, age, profile.base());
        } else {
            throw new IllegalStateException("generated tree exceeded geometry bounds");
        }
    }

    @Deprecated
    public static TreeShapeGenerator.Shape generate(TreeSpecies species, long seed) {
        return generate(species, TreeAge.ADULT, seed);
    }

    @Deprecated
    public static TreeShapeGenerator.Shape generate(TreeSpecies species, long seed, boolean young) {
        return generate(species, young ? TreeAge.YOUNG : TreeAge.ADULT, seed);
    }

    private static void addDeciduousCrown(
            TreeSpecies species,
            TreeAge age,
            Random random,
            int height,
            int trunkTop,
            Set<TreeShapeGenerator.Point> logs,
            Set<TreeShapeGenerator.Point> leaves) {
        int crownRadius = species == TreeSpecies.OAK ? (age == TreeAge.YOUNG ? 3 : 4 + random.nextInt(2)) : 3;
        blob(leaves, 0, height - 5, 0, crownRadius, 4, height);
        int count = species == TreeSpecies.OAK ? 5 + random.nextInt(3) : 3 + random.nextInt(2);
        if (age == TreeAge.YOUNG) {
            count = Math.max(2, count - 2);
        }

        for (int index = 0; index < count; index++) {
            double angle = (Math.PI * 2) * index / count + random.nextDouble() * 0.5;
            int reach = species == TreeSpecies.OAK ? 3 + random.nextInt(2) : 2;
            if (age == TreeAge.YOUNG) {
                reach = Math.min(reach, 2);
            }

            int x = (int) Math.round(Math.cos(angle) * reach);
            int z = (int) Math.round(Math.sin(angle) * reach);
            int start = Math.min(trunkTop, Math.max(0, height - 8 - random.nextInt(2)));
            int end = Math.min(trunkTop + 1, height - 5 + random.nextInt(2));
            branch(logs, start, x, end, z);
            blob(leaves, x, end, z, species == TreeSpecies.OAK ? 3 : 2, 3, height);
        }
    }

    private static void addConiferCrown(
            TreeSpecies species,
            TreeAge age,
            Random random,
            int height,
            int trunkTop,
            Set<TreeShapeGenerator.Point> logs,
            Set<TreeShapeGenerator.Point> leaves) {
        int bottom = species == TreeSpecies.SPRUCE ? 5 + random.nextInt(3) : height / 2;
        if (age == TreeAge.YOUNG) {
            bottom = Math.max(4, bottom - 1);
        }

        int denominator = Math.max(1, height - 1 - bottom);

        for (int y = bottom; y < height; y++) {
            double fraction = (double) (height - 1 - y) / denominator;
            int radius =
                    species == TreeSpecies.SPRUCE
                            ? (int) Math.ceil(4.0 * fraction)
                            : (int) Math.ceil(4.0 * Math.min(1.0, fraction * 2.0));
            if (age == TreeAge.YOUNG) {
                radius = Math.min(radius, 3);
            }

            if (species == TreeSpecies.SPRUCE && (y - bottom) % 3 == 2) {
                radius = Math.max(1, radius - 1);
            }

            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + z * z <= radius * radius + 1) {
                        leaves.add(new TreeShapeGenerator.Point(x, y, z));
                    }
                }
            }

            if (radius >= 2 && y <= trunkTop && (y - bottom) % 3 == 0) {
                int arm = Math.max(1, radius - 1);

                for (int offset = 1; offset <= arm; offset++) {
                    logs.add(new TreeShapeGenerator.Point(offset, y, 0));
                    logs.add(new TreeShapeGenerator.Point(-offset, y, 0));
                    logs.add(new TreeShapeGenerator.Point(0, y, offset));
                    logs.add(new TreeShapeGenerator.Point(0, y, -offset));
                    // Conceal the underside of each branch whorl as well as its sides.
                    // The central vertical trunk stays visible below the crown.
                    leaves.add(new TreeShapeGenerator.Point(offset, y - 1, 0));
                    leaves.add(new TreeShapeGenerator.Point(-offset, y - 1, 0));
                    leaves.add(new TreeShapeGenerator.Point(0, y - 1, offset));
                    leaves.add(new TreeShapeGenerator.Point(0, y - 1, -offset));
                }
            }
        }
    }

    private static Map<TreeShapeGenerator.Point, Integer> leafDistances(
            Set<TreeShapeGenerator.Point> logs, Set<TreeShapeGenerator.Point> leaves) {
        Map<TreeShapeGenerator.Point, Integer> distance = new HashMap<>();
        ArrayDeque<TreeShapeGenerator.Point> queue = new ArrayDeque<>();

        for (TreeShapeGenerator.Point point : logs) {
            distance.put(point, 0);
            queue.add(point);
        }

        while (!queue.isEmpty()) {
            TreeShapeGenerator.Point point = queue.remove();
            int next = distance.get(point) + 1;
            if (next <= 6) {
                for (int[] direction : DIRECTIONS) {
                    TreeShapeGenerator.Point neighbor =
                            new TreeShapeGenerator.Point(
                                    point.x + direction[0], point.y + direction[1], point.z + direction[2]);
                    if (leaves.contains(neighbor) && !distance.containsKey(neighbor)) {
                        distance.put(neighbor, next);
                        queue.add(neighbor);
                    }
                }
            }
        }

        return distance;
    }

    private static int selectHeight(TreeSpecies species, TreeAge age, Random random) {
        int[] band =
                switch (age) {
                    case YOUNG -> new int[] {10, 12};
                    case ADULT -> {
                        switch (species) {
                            case OAK:
                                yield new int[] {14, 24};
                            case BIRCH:
                                yield new int[] {12, 20};
                            case SPRUCE:
                                yield new int[] {18, 30};
                            case PINE:
                                yield new int[] {20, 34};
                            default:
                                throw new MatchException(null, null);
                        }
                    }
                    case OLD -> {
                        switch (species) {
                            case OAK:
                                yield new int[] {18, 27};
                            case BIRCH:
                                yield new int[] {16, 23};
                            case SPRUCE:
                                yield new int[] {22, 32};
                            case PINE:
                                yield new int[] {24, 35};
                            default:
                                throw new MatchException(null, null);
                        }
                    }
                    case RARE_LARGE -> {
                        switch (species) {
                            case OAK:
                                yield new int[] {24, 32};
                            case BIRCH:
                                yield new int[] {21, 28};
                            case SPRUCE:
                                yield new int[] {27, 35};
                            case PINE:
                                yield new int[] {29, 36};
                            default:
                                throw new MatchException(null, null);
                        }
                    }
                };
        return band[0] + random.nextInt(band[1] - band[0] + 1);
    }

    private static long shapeSeed(TreeSpecies species, TreeAge age, long seed) {
        long value = seed ^ -7046029254386353131L * (species.ordinal() + 1L);
        value ^= -2960836687051489901L * (age.ordinal() + 1L);
        value ^= value >>> 32;
        value *= -2960836687051489901L;
        return value ^ value >>> 32;
    }

    private static void branch(Set<TreeShapeGenerator.Point> logs, int startY, int endX, int endY, int endZ) {
        int x = 0;
        int y = startY;
        int z = 0;
        logs.add(new TreeShapeGenerator.Point(x, y, z));

        while (x != endX || y != endY || z != endZ) {
            if (y < endY) {
                logs.add(new TreeShapeGenerator.Point(x, ++y, z));
            }

            if (x != endX) {
                x += Integer.signum(endX - x);
                logs.add(new TreeShapeGenerator.Point(x, y, z));
            }

            if (z != endZ) {
                z += Integer.signum(endZ - z);
                logs.add(new TreeShapeGenerator.Point(x, y, z));
            }
        }
    }

    private static void blob(
            Set<TreeShapeGenerator.Point> leaves,
            int centerX,
            int centerY,
            int centerZ,
            int radius,
            int vertical,
            int height) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -vertical; y <= vertical; y++) {
                for (int z = -radius; z <= radius; z++) {
                    double norm =
                            (double) (x * x + z * z) / (radius * radius) + (double) (y * y) / (vertical * vertical);
                    if (norm <= 1.1 && centerY + y >= 4 && centerY + y < height) {
                        leaves.add(new TreeShapeGenerator.Point(centerX + x, centerY + y, centerZ + z));
                    }
                }
            }
        }
    }

    private record Point(int x, int y, int z) {}

    public record Shape(
            List<TreeShapeGenerator.Voxel> voxels, int height, int radius, TreeAge age, TreeAge.Footprint base) {
        public Shape(
                List<TreeShapeGenerator.Voxel> voxels, int height, int radius, TreeAge age, TreeAge.Footprint base) {
            List<TreeShapeGenerator.Voxel> ordered =
                    new ArrayList<>(List.copyOf(Objects.requireNonNull(voxels, "voxels")));
            age = Objects.requireNonNull(age, "age");
            base = Objects.requireNonNull(base, "base");
            if (height < 1 || height > 36) {
                throw new IllegalArgumentException("tree height must be between one and 36");
            }

            if (radius >= 0 && radius <= 8) {
                if (!ordered.isEmpty() && ordered.size() <= 12000) {
                    Set<TreeShapeGenerator.Point> coordinates = new HashSet<>();

                    for (TreeShapeGenerator.Voxel voxel : ordered) {
                        if (voxel.y() < 0
                                || voxel.y() >= height
                                || voxel.x() < -radius
                                || voxel.x() > radius
                                || voxel.z() < -radius
                                || voxel.z() > radius) {
                            throw new IllegalArgumentException("tree voxel exceeds declared geometry bounds");
                        }

                        if (!coordinates.add(new TreeShapeGenerator.Point(voxel.x(), voxel.y(), voxel.z()))) {
                            throw new IllegalArgumentException("tree voxel coordinates must be unique");
                        }
                    }

                    ordered.sort(TreeShapeGenerator.VOXEL_ORDER);
                    voxels = List.copyOf(ordered);
                    this.voxels = voxels;
                    this.height = height;
                    this.radius = radius;
                    this.age = age;
                    this.base = base;
                } else {
                    throw new IllegalArgumentException("tree voxel count must be between one and 12000");
                }
            } else {
                throw new IllegalArgumentException("tree radius must be between zero and eight");
            }
        }

        public Shape(List<TreeShapeGenerator.Voxel> voxels, int height, int radius) {
            this(voxels, height, radius, TreeAge.ADULT, new TreeAge.Footprint(1, 2));
        }
    }

    public record Voxel(int x, int y, int z, boolean log, int leafDistance) {
        public Voxel(int x, int y, int z, boolean log) {
            this(x, y, z, log, log ? 0 : 7);
        }
    }
}

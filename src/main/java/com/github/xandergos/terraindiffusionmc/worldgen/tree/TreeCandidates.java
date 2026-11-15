package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class TreeCandidates {
    public static final int ADULT_CELL_SIZE = 5;
    public static final int REGENERATION_CELL_SIZE = 11;
    public static final int MAX_TREE_RADIUS = 8;

    @Deprecated
    public static final int SPACING = 5;

    @Deprecated
    public static final int MAX_RADIUS = 8;

    private static final int MAX_ADULT_ROOT_RADIUS = 4;
    private static final int MAX_YOUNG_ROOT_RADIUS = 1;
    private static final int AGE_STAND_CELLS = 11;
    private static final long ADULT_LAYER = -6884282663029611473L;
    private static final long REGENERATION_LAYER = -1800455987208640293L;

    private TreeCandidates() {}

    public static List<TreeCandidates.Candidate> rawAdultRootsForChunk(long seed, int chunkX, int chunkZ) {
        return rawRoots(seed, chunkX, chunkZ, 5, -6884282663029611473L, false, 0);
    }

    public static List<TreeCandidates.Candidate> rawRegenerationRootsForChunk(long seed, int chunkX, int chunkZ) {
        return rawRoots(seed, chunkX, chunkZ, 11, -1800455987208640293L, true, 0);
    }

    public static List<TreeCandidates.Candidate> adultRootsForChunk(
            long seed, int chunkX, int chunkZ, Predicate<TreeCandidates.Candidate> accepted) {
        return selectAdults(seed, chunkX, chunkZ, 0, accepted);
    }

    public static List<TreeCandidates.Candidate> adultCandidatesForChunk(
            long seed, int chunkX, int chunkZ, Predicate<TreeCandidates.Candidate> accepted) {
        return selectAdults(seed, chunkX, chunkZ, 8, accepted);
    }

    public static List<TreeCandidates.Candidate> regenerationRootsForChunk(
            long seed,
            int chunkX,
            int chunkZ,
            Predicate<TreeCandidates.Candidate> acceptedAdult,
            Predicate<TreeCandidates.Candidate> acceptedYoung) {
        return selectYoung(seed, chunkX, chunkZ, 0, acceptedAdult, acceptedYoung);
    }

    public static List<TreeCandidates.Candidate> regenerationCandidatesForChunk(
            long seed,
            int chunkX,
            int chunkZ,
            Predicate<TreeCandidates.Candidate> acceptedAdult,
            Predicate<TreeCandidates.Candidate> acceptedYoung) {
        return selectYoung(seed, chunkX, chunkZ, 8, acceptedAdult, acceptedYoung);
    }

    public static List<TreeCandidates.Candidate> forChunk(long seed, int chunkX, int chunkZ) {
        return adultCandidatesForChunk(seed, chunkX, chunkZ, ignored -> true);
    }

    private static List<TreeCandidates.Candidate> selectAdults(
            long seed, int chunkX, int chunkZ, int margin, Predicate<TreeCandidates.Candidate> accepted) {
        Objects.requireNonNull(accepted, "accepted");
        Map<Long, TreeCandidates.RootEnvelope> envelopes = new HashMap<>();
        ArrayList<TreeCandidates.Candidate> result = new ArrayList<>();

        for (TreeCandidates.Candidate candidate :
                rawRoots(seed, chunkX, chunkZ, 5, -6884282663029611473L, false, margin)) {
            if (accepted.test(candidate) && winsAdultConflict(seed, candidate, accepted, envelopes)) {
                result.add(candidate);
            }
        }

        return List.copyOf(result);
    }

    private static List<TreeCandidates.Candidate> selectYoung(
            long seed,
            int chunkX,
            int chunkZ,
            int margin,
            Predicate<TreeCandidates.Candidate> acceptedAdult,
            Predicate<TreeCandidates.Candidate> acceptedYoung) {
        Objects.requireNonNull(acceptedAdult, "acceptedAdult");
        Objects.requireNonNull(acceptedYoung, "acceptedYoung");
        Map<Long, TreeCandidates.RootEnvelope> adultEnvelopes = new HashMap<>();
        Map<Long, TreeCandidates.RootEnvelope> youngEnvelopes = new HashMap<>();
        ArrayList<TreeCandidates.Candidate> result = new ArrayList<>();

        for (TreeCandidates.Candidate candidate :
                rawRoots(seed, chunkX, chunkZ, 11, -1800455987208640293L, true, margin)) {
            if (acceptedYoung.test(candidate)
                    && winsYoungConflict(seed, candidate, acceptedYoung, youngEnvelopes)
                    && !overlapsAcceptedAdult(seed, candidate, acceptedAdult, youngEnvelopes, adultEnvelopes)) {
                result.add(candidate);
            }
        }

        return List.copyOf(result);
    }

    private static boolean winsAdultConflict(
            long seed,
            TreeCandidates.Candidate candidate,
            Predicate<TreeCandidates.Candidate> accepted,
            Map<Long, TreeCandidates.RootEnvelope> envelopes) {
        int reach = candidate.maxRootRadius() + 4;
        long minCellX = Math.floorDiv((long) candidate.x() - reach, 5);
        long maxCellX = Math.floorDiv((long) candidate.x() + reach, 5);
        long minCellZ = Math.floorDiv((long) candidate.z() - reach, 5);
        long maxCellZ = Math.floorDiv((long) candidate.z() + reach, 5);

        for (long cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (long cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                TreeCandidates.Candidate other = candidate(seed, cellX, cellZ, 5, -6884282663029611473L, false);
                if (other != null
                        && other.identity() != candidate.identity()
                        && overlaps(candidate, other, envelopes, envelopes)
                        && accepted.test(other)
                        && beats(other, candidate)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean winsYoungConflict(
            long seed,
            TreeCandidates.Candidate candidate,
            Predicate<TreeCandidates.Candidate> acceptedYoung,
            Map<Long, TreeCandidates.RootEnvelope> envelopes) {
        int reach = candidate.maxRootRadius() + 1;
        long minCellX = Math.floorDiv((long) candidate.x() - reach, 11);
        long maxCellX = Math.floorDiv((long) candidate.x() + reach, 11);
        long minCellZ = Math.floorDiv((long) candidate.z() - reach, 11);
        long maxCellZ = Math.floorDiv((long) candidate.z() + reach, 11);

        for (long cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (long cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                TreeCandidates.Candidate other = candidate(seed, cellX, cellZ, 11, -1800455987208640293L, true);
                if (other != null
                        && other.identity() != candidate.identity()
                        && overlaps(candidate, other, envelopes, envelopes)
                        && acceptedYoung.test(other)
                        && beats(other, candidate)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean overlapsAcceptedAdult(
            long seed,
            TreeCandidates.Candidate young,
            Predicate<TreeCandidates.Candidate> acceptedAdult,
            Map<Long, TreeCandidates.RootEnvelope> youngEnvelopes,
            Map<Long, TreeCandidates.RootEnvelope> adultEnvelopes) {
        int reach = young.maxRootRadius() + 4;
        long minCellX = Math.floorDiv((long) young.x() - reach, 5);
        long maxCellX = Math.floorDiv((long) young.x() + reach, 5);
        long minCellZ = Math.floorDiv((long) young.z() - reach, 5);
        long maxCellZ = Math.floorDiv((long) young.z() + reach, 5);

        for (long cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (long cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                TreeCandidates.Candidate adult = candidate(seed, cellX, cellZ, 5, -6884282663029611473L, false);
                if (adult != null
                        && overlaps(young, adult, youngEnvelopes, adultEnvelopes)
                        && acceptedAdult.test(adult)
                        && winsAdultConflict(seed, adult, acceptedAdult, adultEnvelopes)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean overlaps(
            TreeCandidates.Candidate a,
            TreeCandidates.Candidate b,
            Map<Long, TreeCandidates.RootEnvelope> aEnvelopes,
            Map<Long, TreeCandidates.RootEnvelope> bEnvelopes) {
        int radii = a.maxRootRadius() + b.maxRootRadius();
        if (Math.abs((long) a.x() - b.x()) <= radii && Math.abs((long) a.z() - b.z()) <= radii) {
            TreeCandidates.RootEnvelope aEnvelope = rootEnvelope(a, aEnvelopes);
            TreeCandidates.RootEnvelope bEnvelope = rootEnvelope(b, bEnvelopes);

            for (long packed : aEnvelope.offsets()) {
                long worldX = (long) a.x() + (int) (packed >> 32);
                long worldZ = (long) a.z() + (int) packed;
                long relativeX = worldX - b.x();
                long relativeZ = worldZ - b.z();
                if (relativeX >= -2147483648L
                        && relativeX <= 2147483647L
                        && relativeZ >= -2147483648L
                        && relativeZ <= 2147483647L
                        && bEnvelope.offsets().contains(pack((int) relativeX, (int) relativeZ))) {
                    return true;
                }
            }

            return false;
        } else {
            return false;
        }
    }

    private static TreeCandidates.RootEnvelope rootEnvelope(
            TreeCandidates.Candidate candidate, Map<Long, TreeCandidates.RootEnvelope> cache) {
        return cache.computeIfAbsent(candidate.identity(), ignored -> {
            Set<Long> offsets = new HashSet<>();

            for (TreeSpecies species : TreeSpecies.values()) {
                TrunkProfile profile = TrunkProfile.generate(species, candidate.age(), candidate.seed(), 4);

                for (TrunkProfile.Point point : profile.wood()) {
                    offsets.add(pack(point.x(), point.z()));
                }
            }

            return new TreeCandidates.RootEnvelope(Set.copyOf(offsets));
        });
    }

    private static long pack(int x, int z) {
        return (long) x << 32 ^ z & 4294967295L;
    }

    private static boolean beats(TreeCandidates.Candidate a, TreeCandidates.Candidate b) {
        int priority = Long.compareUnsigned(a.priority(), b.priority());
        return priority > 0 || priority == 0 && Long.compareUnsigned(a.identity(), b.identity()) > 0;
    }

    private static List<TreeCandidates.Candidate> rawRoots(
            long seed, int chunkX, int chunkZ, int cellSize, long layerSalt, boolean young, int margin) {
        long minX = chunkX * 16L - margin;
        long minZ = chunkZ * 16L - margin;
        long maxX = chunkX * 16L + 15L + margin;
        long maxZ = chunkZ * 16L + 15L + margin;
        long firstCellX = Math.floorDiv(minX, cellSize);
        long lastCellX = Math.floorDiv(maxX, cellSize);
        long firstCellZ = Math.floorDiv(minZ, cellSize);
        long lastCellZ = Math.floorDiv(maxZ, cellSize);
        ArrayList<TreeCandidates.Candidate> result = new ArrayList<>();

        for (long cellX = firstCellX; cellX <= lastCellX; cellX++) {
            for (long cellZ = firstCellZ; cellZ <= lastCellZ; cellZ++) {
                TreeCandidates.Candidate candidate = candidate(seed, cellX, cellZ, cellSize, layerSalt, young);
                if (candidate != null
                        && candidate.x() >= minX
                        && candidate.x() <= maxX
                        && candidate.z() >= minZ
                        && candidate.z() <= maxZ) {
                    result.add(candidate);
                }
            }
        }

        return List.copyOf(result);
    }

    private static TreeCandidates.Candidate candidate(
            long seed, long cellX, long cellZ, int cellSize, long layerSalt, boolean young) {
        long identity = mix(seed ^ mix(cellX * -7046029254386353131L) ^ mix(cellZ * -3335678366873096957L) ^ layerSalt);
        long x = cellX * cellSize + Long.remainderUnsigned(mix(identity ^ -8161530843051276573L), cellSize);
        long z = cellZ * cellSize + Long.remainderUnsigned(mix(identity ^ 6384245875588680899L), cellSize);
        double ageRoll = wrapUnit(unit(mix(identity ^ 2129725606500045391L))
                + .15 * (VegetationDistribution.valueNoise(seed, (int) x, (int) z, 61, 192) - .5));
        TreeAge age = young
                ? TreeAge.YOUNG
                : (ageRoll < 0.68 ? TreeAge.ADULT : (ageRoll < 0.95 ? TreeAge.OLD : TreeAge.RARE_LARGE));

        int radius =
                switch (age) {
                    case YOUNG, ADULT -> 1;
                    case OLD, RARE_LARGE -> 4;
                };
        if (x - radius >= -2147483648L
                && x + radius <= 2147483647L
                && z - radius >= -2147483648L
                && z + radius <= 2147483647L) {
            double speciesRoll = unit(mix(identity ^ -5840758589994634535L));
            return new TreeCandidates.Candidate(
                    (int) x,
                    (int) z,
                    identity,
                    mix(identity ^ -7723592293110705685L),
                    unit(mix(identity ^ 7146057691288625177L)),
                    speciesRoll,
                    age,
                    radius);
        } else {
            return null;
        }
    }

    private static double wrapUnit(double value) {
        return value - Math.floor(value);
    }

    private static double unit(long value) {
        return (value >>> 11) * 1.1102230246251565E-16D;
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * -4658895280553007687L;
        value = (value ^ value >>> 27) * -7723592293110705685L;
        return value ^ value >>> 31;
    }

    public record Candidate(
            int x,
            int z,
            long seed,
            long priority,
            double acceptance,
            double speciesRoll,
            TreeAge age,
            int maxRootRadius) {
        public Candidate(
                int x,
                int z,
                long seed,
                long priority,
                double acceptance,
                double speciesRoll,
                TreeAge age,
                int maxRootRadius) {
            Objects.requireNonNull(age, "age");
            if (!Double.isFinite(acceptance)
                    || !Double.isFinite(speciesRoll)
                    || acceptance < 0.0
                    || acceptance >= 1.0
                    || speciesRoll < 0.0
                    || speciesRoll >= 1.0) {
                throw new IllegalArgumentException("candidate rolls must be in [0,1)");
            }

            if (maxRootRadius >= 1 && maxRootRadius <= 4) {
                if ((long) x - maxRootRadius >= -2147483648L
                        && (long) x + maxRootRadius <= 2147483647L
                        && (long) z - maxRootRadius >= -2147483648L
                        && (long) z + maxRootRadius <= 2147483647L) {
                    this.x = x;
                    this.z = z;
                    this.seed = seed;
                    this.priority = priority;
                    this.acceptance = acceptance;
                    this.speciesRoll = speciesRoll;
                    this.age = age;
                    this.maxRootRadius = maxRootRadius;
                } else {
                    throw new IllegalArgumentException("candidate root envelope exceeds int world coordinates");
                }
            } else {
                throw new IllegalArgumentException("invalid root exclusion radius");
            }
        }

        public long identity() {
            return this.seed;
        }
    }

    private record RootEnvelope(Set<Long> offsets) {}
}

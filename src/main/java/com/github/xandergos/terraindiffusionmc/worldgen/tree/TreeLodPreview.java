package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanEcology;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.BiomeBalance;
import java.util.*;

/** Prepared forest preview. Real tree geometry; root conflict validation remains approximate. */
public final class TreeLodPreview {
    public record Climate(short biome, short heightMeters, float temperatureC, float precipitationMm) {}

    public interface Field {
        Climate climate(int x, int z) throws Exception;

        default BiomeBalance biomeBalance() {
            return BiomeBalance.DEFAULT;
        }

        default boolean largerLandscapes() {
            return false;
        }

        int firstAir(int x, int z) throws Exception;

        int waterY(int x, int z) throws Exception;
    }

    @FunctionalInterface
    public interface Writer {
        void set(int x, int y, int z, TreeSpecies species, boolean log);
    }

    private record Tree(
            TreeCandidates.Candidate candidate,
            TreeSpecies species,
            TreeAge.Footprint base,
            int y,
            TreeShapeGenerator.Preview crown) {
        int minX() {
            return candidate.x() - base.width() / 2;
        }

        int minZ() {
            return candidate.z() - base.depth() / 2;
        }
    }

    public static int paint(long seed, int x, int z, int side, int bottom, int top, Field field, Writer writer)
            throws Exception {
        if (side < 1 || side > 128) throw new IllegalArgumentException("LOD forest extent");
        List<Tree> adults = new ArrayList<>(), young = new ArrayList<>();
        // A fixed halo includes crown roots and their competing neighbours, even across chunk boundaries.
        for (int cz = Math.floorDiv(z - 16, 16); cz <= Math.floorDiv(z + side + 15, 16); cz++)
            for (int cx = Math.floorDiv(x - 16, 16); cx <= Math.floorDiv(x + side + 15, 16); cx++) {
                for (var candidate : TreeCandidates.rawAdultRootsForChunk(seed, cx, cz)) {
                    var tree = prepare(seed, candidate, bottom, top, field);
                    if (tree != null) adults.add(tree);
                }
                for (var candidate : TreeCandidates.rawRegenerationRootsForChunk(seed, cx, cz)) {
                    var tree = prepare(seed, candidate, bottom, top, field);
                    if (tree != null) young.add(tree);
                }
            }
        List<Tree> selected = new ArrayList<>();
        for (var tree : adults) if (wins(tree, adults)) selected.add(tree);
        int count = selected.size();
        for (var tree : young)
            if (wins(tree, young) && selected.subList(0, count).stream().noneMatch(other -> overlap(tree, other)))
                selected.add(tree);
        int painted = 0;
        for (var tree : selected) {
            int radius = TreeCandidates.MAX_TREE_RADIUS, tx = tree.candidate.x(), tz = tree.candidate.z();
            if (tx + radius < x || tz + radius < z || tx - radius >= x + side || tz - radius >= z + side) continue;
            painted++;
            var shape = TreeShapeGenerator.generate(tree.species, tree.candidate.age(), tree.candidate.seed());
            for (var voxel : shape.voxels()) {
                int wx = tx + voxel.x(), wz = tz + voxel.z(), wy = tree.y + voxel.y();
                if (wx < x || wz < z || wx >= x + side || wz >= z + side || wy < bottom || wy > top) continue;
                int water = field.waterY(wx, wz),
                        first = Math.max(field.firstAir(wx, wz), water == Integer.MIN_VALUE ? bottom : water + 1);
                if (wy >= first) writer.set(wx, wy, wz, tree.species, voxel.log());
            }
        }
        return painted;
    }

    private static Tree prepare(long seed, TreeCandidates.Candidate candidate, int bottom, int top, Field field)
            throws Exception {
        int x = candidate.x(), z = candidate.z();
        var sample = field.climate(x, z);
        if (sample == null
                || !VegetationDistribution.acceptsBiome(sample.biome())
                || field.waterY(x, z) != Integer.MIN_VALUE) return null;
        boolean largerLandscapes = field.largerLandscapes();
        if (largerLandscapes && sample.biome() == 229 && EuropeanEcology.openField(seed, x, z, field.biomeBalance()))
            return null;
        double dx = (field.firstAir(x + 1, z) - field.firstAir(x - 1, z)) / 2.0,
                dz = (field.firstAir(x, z + 1) - field.firstAir(x, z - 1)) / 2.0;
        var ecology = EuropeanEcology.evaluate(
                seed,
                sample.temperatureC(),
                sample.precipitationMm(),
                sample.heightMeters(),
                Math.hypot(dx, dz),
                Double.POSITIVE_INFINITY,
                x,
                z,
                largerLandscapes,
                field.biomeBalance());
        if (!(candidate.age() == TreeAge.YOUNG
                ? VegetationDistribution.acceptsYoung(candidate, seed, ecology)
                : VegetationDistribution.acceptsAdult(candidate, seed, ecology))) return null;
        TreeSpecies species = ForestComposition.at(seed, x, z, ecology).choose(candidate.speciesRoll());
        var base = TrunkProfile.previewFootprint(species, candidate.age(), candidate.seed());
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int bz = 0; bz < base.depth(); bz++)
            for (int bx = 0; bx < base.width(); bx++) {
                int wx = x - base.width() / 2 + bx, wz = z - base.depth() / 2 + bz;
                if (field.waterY(wx, wz) != Integer.MIN_VALUE) return null;
                int air = field.firstAir(wx, wz);
                low = Math.min(low, air);
                high = Math.max(high, air);
            }
        var crown = TreeShapeGenerator.preview(species, candidate.age(), candidate.seed());
        int y = low - 1;
        return high - low > 3 || y < bottom || (long) y + crown.height() > top + 1L
                ? null
                : new Tree(candidate, species, base, y, crown);
    }

    private static boolean overlap(Tree a, Tree b) {
        return a.minX() < b.minX() + b.base.width()
                && b.minX() < a.minX() + a.base.width()
                && a.minZ() < b.minZ() + b.base.depth()
                && b.minZ() < a.minZ() + a.base.depth();
    }

    private static boolean wins(Tree tree, List<Tree> peers) {
        for (var other : peers)
            if (tree != other && overlap(tree, other)) {
                int order = Long.compareUnsigned(other.candidate.priority(), tree.candidate.priority());
                if (order > 0
                        || order == 0
                                && Long.compareUnsigned(other.candidate.identity(), tree.candidate.identity()) > 0)
                    return false;
            }
        return true;
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WholeTreeWriteTest {
    private record Pos(int x, int y, int z) {}

    private EuropeanTreePlanner.PreparedTree tree(int x, int z) {
        var candidate = new TreeCandidates.Candidate(x, z, 17, 23, .1, .1, TreeAge.ADULT, 1);
        return new EuropeanTreePlanner.PreparedTree(
                candidate, TreeSpecies.BIRCH, TreeShapeGenerator.generate(TreeSpecies.BIRCH, TreeAge.ADULT, 17), 100);
    }

    @Test
    void rootWritesTheCompleteCanopyAtPositiveAndNegativeChunkCorners() {
        for (int origin : new int[] {15, -1, -16, 16}) {
            var t = tree(origin, origin);
            int root = Math.floorDiv(origin, 16);
            Set<Pos> written = new HashSet<>();
            var plan =
                    EuropeanTreePlanner.planRootChunk(
                            List.of(t),
                            root,
                            root,
                            (tree, v) -> {
                                assertTrue(Math.abs(Math.floorDiv(v.x(), 16) - root) <= 1);
                                assertTrue(Math.abs(Math.floorDiv(v.z(), 16) - root) <= 1);
                                return true;
                            });
            assertEquals(t.shape().voxels().size(), plan.voxels().size());
            EuropeanTreePlanner.place(plan, v -> written.add(new Pos(v.x(), v.y(), v.z())));
            assertEquals(t.shape().voxels().size(), written.size());
            for (var v : t.shape().voxels())
                assertTrue(written.contains(new Pos(origin + v.x(), 100 + v.y(), origin + v.z())));
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++)
                    if (dx != 0 || dz != 0)
                        assertTrue(
                                EuropeanTreePlanner.planRootChunk(List.of(t), root + dx, root + dz, (tree, v) -> true)
                                        .voxels()
                                        .isEmpty());
        }
    }

    @Test
    void changingDecorationBetweenChunkPassesUsedToLeaveASeveredCanopy() {
        var t = tree(15, 15);
        var first = EuropeanTreePlanner.planChunk(List.of(t), 0, 0, v -> true);
        assertFalse(first.voxels().isEmpty());
        // The neighbour now sees an obstacle anywhere in the already placed tree.
        var obstacle = first.voxels().getFirst();
        assertTrue(EuropeanTreePlanner.planChunk(List.of(t), 1, 0, v -> !v.equals(obstacle)).voxels().isEmpty());
        // A single root invocation has no later per-chunk veto after writing the trunk.
        var complete = EuropeanTreePlanner.planRootChunk(List.of(t), 0, 0, (tree, v) -> true);
        assertEquals(t.shape().voxels().size(), complete.voxels().size());
        assertTrue(complete.voxels().stream().anyMatch(v -> v.x() >= 16));
    }

    @Test
    void obstructionOrUnwritablePositionRejectsTheWholeTreeBeforeAnyWrite() {
        var t = tree(15, 15);
        for (var blocked : List.of(t.shape().voxels().getFirst(), t.shape().voxels().getLast())) {
            var plan =
                    EuropeanTreePlanner.planRootChunk(
                            List.of(t),
                            0,
                            0,
                            (tree, v) ->
                                    !(v.x() == 15 + blocked.x()
                                            && v.y() == 100 + blocked.y()
                                            && v.z() == 15 + blocked.z()));
            assertTrue(plan.voxels().isEmpty());
            assertEquals(
                    0,
                    EuropeanTreePlanner.place(
                            plan,
                            v -> {
                                fail("No partial write");
                                return false;
                            }));
        }
    }

    @Test
    void allConiferBranchUndersidesAreCoveredButBareTrunksRemainBare() {
        for (var species : List.of(TreeSpecies.PINE, TreeSpecies.SPRUCE))
            for (var age : TreeAge.values())
                for (int seed = 0; seed < 50; seed++) {
                    var s = TreeShapeGenerator.generate(species, age, seed);
                    Map<Pos, TreeShapeGenerator.Voxel> map = new HashMap<>();
                    for (var v : s.voxels()) map.put(new Pos(v.x(), v.y(), v.z()), v);
                    int arms = 0;
                    for (var v : s.voxels())
                        if (v.log() && v.y() > 3 && (v.x() == 0 ^ v.z() == 0)) {
                            // Only high horizontal branches: roots/buttresses may also be off-axis.
                            if (!map.containsKey(new Pos(v.x(), v.y() + 1, v.z()))
                                    || !map.get(new Pos(v.x(), v.y() + 1, v.z())).log()) {
                                assertNotNull(
                                        map.get(new Pos(v.x(), v.y() - 1, v.z())),
                                        "Exposed underside " + species + " " + age + " " + seed + " " + v);
                                arms++;
                            }
                        }
                    assertTrue(arms > 0);
                    assertFalse(
                            map.containsKey(new Pos(1, 2, 0)) && !map.get(new Pos(1, 2, 0)).log(),
                            "Do not wrap the bare trunk in foliage");
                }
    }
}

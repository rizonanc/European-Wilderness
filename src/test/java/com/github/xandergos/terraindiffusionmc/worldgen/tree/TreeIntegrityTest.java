package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import static org.junit.jupiter.api.Assertions.*;

import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.RiverDetailPlanning;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.Waterfall;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

class TreeIntegrityTest {
    private static final IntBinaryOperator NO_WATER = (x, z) -> Integer.MIN_VALUE;

    private record Pos(int x, int y, int z) {}

    private static TreeShapeGenerator.Shape shape() {
        List<TreeShapeGenerator.Voxel> v = new ArrayList<>();
        for (int y = 0; y < 6; y++) v.add(new TreeShapeGenerator.Voxel(0, y, 0, true, 0));
        v.add(new TreeShapeGenerator.Voxel(-1, 4, 0, false, 1));
        v.add(new TreeShapeGenerator.Voxel(1, 4, 0, false, 1));
        v.add(new TreeShapeGenerator.Voxel(2, 4, 0, false, 2));
        v.add(new TreeShapeGenerator.Voxel(1, 4, 1, false, 2));
        return new TreeShapeGenerator.Shape(v, 6, 2, TreeAge.ADULT, new TreeAge.Footprint(1, 1));
    }

    private static EuropeanTreePlanner.PreparedTree tree(int x, int z, int base) {
        return new EuropeanTreePlanner.PreparedTree(
                new TreeCandidates.Candidate(x, z, 17, 23, .1, .1, TreeAge.ADULT, 1), TreeSpecies.OAK, shape(), base);
    }

    private static boolean clear(
            TreeShapeGenerator.Shape s, int x, int y, int z, IntBinaryOperator air, IntBinaryOperator water) {
        return TreePlacementValidator.isWholeTreeClear(s, x, y, z, air, air, water);
    }

    @Test
    void testRiverOutsideTheRootsMustNotPullTheTrunkBelowItsBank() {
        IntBinaryOperator bank = (x, z) -> x < 16 ? 101 : 95;
        assertEquals(
                94,
                TreePlacementValidator.anchorBaseY(shape().base(), 15, 8, bank),
                "reproduce the old wide anchor dipping into the river");
        assertEquals(100, TreePlacementValidator.anchorBaseY(shape(), 15, 8, bank));
        IntBinaryOperator water = (x, z) -> x >= 16 ? 96 : Integer.MIN_VALUE;
        assertFalse(clear(shape(), 15, 94, 8, bank, water));
        assertTrue(clear(shape(), 15, 100, 8, bank, water), "the complete tree can still grow on the dry bank");
    }

    @Test
    void testTerrainCollisionInOneChunkRejectsEveryPartOfABuriedTree() {
        IntBinaryOperator bank = (x, z) -> x < 16 ? 101 : 95;
        Predicate<EuropeanTreePlanner.PlannedVoxel> allowed = v -> v.log() || v.y() >= bank.applyAsInt(v.x(), v.z());
        var tree = tree(15, 8, 94);
        assertTrue(
                TreePlacement.parts(tree.shape(), 15, 94, 8, 1, 0).stream().allMatch(v -> !v.log() && v.y() >= 95),
                "the neighbour's crown used to look placeable on its own");
        assertTrue(EuropeanTreePlanner.planChunk(List.of(tree), 0, 0, allowed)
                .voxels()
                .isEmpty());
        assertTrue(EuropeanTreePlanner.planChunk(List.of(tree), 1, 0, allowed)
                .voxels()
                .isEmpty());
    }

    @Test
    void testOneWetRootRejectsTheWholeTree() {
        var voxels = new ArrayList<>(shape().voxels());
        voxels.add(new TreeShapeGenerator.Voxel(1, 0, 0, true, 0));
        var shape = new TreeShapeGenerator.Shape(voxels, 6, 2, TreeAge.ADULT, new TreeAge.Footprint(2, 1));
        assertFalse(clear(shape, 15, 100, 8, (x, z) -> 101, (x, z) -> x == 16 ? 100 : Integer.MIN_VALUE));
    }

    @Test
    void testHighCanopyMayOverhangWater() {
        assertTrue(
                clear(shape(), 15, 100, 8, (x, z) -> x >= 16 ? 95 : 101, (x, z) -> x >= 16 ? 96 : Integer.MIN_VALUE));
    }

    @Test
    void testBlockedTrunkAndBlockedRemoteLeafBothRejectAllChunkParts() {
        var t = tree(15, 15, 100);
        for (Pos obstacle : List.of(new Pos(15, 102, 15), new Pos(17, 104, 15))) {
            for (int cx = 0; cx < 2; cx++)
                for (int cz = 0; cz < 2; cz++) {
                    var plan = EuropeanTreePlanner.planChunk(
                            List.of(t), cx, cz, v -> !new Pos(v.x(), v.y(), v.z()).equals(obstacle));
                    assertTrue(plan.voxels().isEmpty());
                    assertTrue(plan.representedTrees().isEmpty());
                }
        }
    }

    @Test
    void testCompleteTreeAcrossFourChunksIsIndependentOfTheirOrder() {
        var t = tree(15, 15, 100);
        Set<Pos> expected = new HashSet<>();
        for (var v : t.shape().voxels()) expected.add(new Pos(15 + v.x(), 100 + v.y(), 15 + v.z()));
        assertEquals(expected, generate(t, false));
        assertEquals(expected, generate(t, true));
    }

    private static Set<Pos> generate(EuropeanTreePlanner.PreparedTree t, boolean reverse) {
        Map<Pos, Boolean> placed = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            int chunk = reverse ? 3 - i : i, cx = chunk % 2, cz = chunk / 2;
            var plan = EuropeanTreePlanner.planChunk(List.of(t), cx, cz, (tree, v) -> {
                Boolean previous = placed.get(new Pos(v.x(), v.y(), v.z()));
                boolean soil = v.y() < 101;
                return TreePlacementValidator.mayPlaceWholeVoxel(
                        v.log(),
                        v.y() - tree.baseY(),
                        false,
                        previous != null && !previous || previous == null && !soil,
                        Boolean.TRUE.equals(previous),
                        soil);
            });
            EuropeanTreePlanner.place(plan, v -> {
                assertEquals(cx, Math.floorDiv(v.x(), 16));
                assertEquals(cz, Math.floorDiv(v.z(), 16));
                Pos p = new Pos(v.x(), v.y(), v.z());
                if (Boolean.TRUE.equals(placed.get(p))) return false;
                placed.put(p, v.log());
                return true;
            });
        }
        return placed.keySet();
    }

    @Test
    void rootSupportOutsideTheCurrentChunkIsStillRequired() {
        var v = new ArrayList<>(shape().voxels());
        v.add(new TreeShapeGenerator.Voxel(1, 0, 0, true, 0));
        var s = new TreeShapeGenerator.Shape(v, 6, 2, TreeAge.ADULT, new TreeAge.Footprint(2, 1));
        assertFalse(TreePlacementValidator.isWholeTreeWorldSupported(s, 15, 100, 8, (x, z) -> x == 16 ? 90 : 101));
    }

    @Test
    void testRoadUnderRootRejectsTree() {
        var voxels = new ArrayList<>(shape().voxels());
        voxels.add(new TreeShapeGenerator.Voxel(1, 0, 0, true, 0));
        var tree = new TreeShapeGenerator.Shape(voxels, 6, 2, TreeAge.ADULT, new TreeAge.Footprint(2, 1));
        assertFalse(TreePlacementValidator.rootsAvoid(tree, 15, 8, (x, z) -> x == 16 && z == 8));
        assertTrue(TreePlacementValidator.rootsAvoid(tree, 15, 8, (x, z) -> x == 17 && z == 8));
    }

    @Test
    void testWorldCollisionPolicyPreservesExistingWood() {
        assertTrue(TreePlacementValidator.mayPlaceWholeVoxel(true, 5, false, false, true, false));
        assertTrue(TreePlacementValidator.mayPlaceWholeVoxel(false, 5, false, false, true, false));
        assertFalse(TreePlacementValidator.mayPlaceWholeVoxel(true, 1, true, false, false, true));
        assertFalse(TreePlacementValidator.mayPlaceWholeVoxel(true, 1, false, false, false, false));
        assertFalse(TreePlacementValidator.mayPlaceWholeVoxel(false, 4, false, false, false, true));
        assertFalse(TreePlacementValidator.mayPlaceWholeVoxel(true, 4, false, false, false, true));
        assertTrue(TreePlacementValidator.mayPlaceWholeVoxel(true, 1, false, false, false, true));
    }

    @Test
    void missingRootsAndDeeplyBuriedRootsAreRejected() {
        var leafOnly = new TreeShapeGenerator.Shape(List.of(new TreeShapeGenerator.Voxel(0, 4, 0, false, 1)), 5, 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> TreePlacementValidator.anchorBaseY(leafOnly, 0, 0, (x, z) -> 101));
        assertFalse(clear(shape(), 0, 94, 0, (x, z) -> 101, NO_WATER));
        assertFalse(TreePlacementValidator.isWholeTreeWorldSupported(shape(), 0, 100, 0, (x, z) -> 90));
    }

    @Test
    void finalShoreRocksAreIncludedInWholeCanopyClearance() {
        WorldSession.RiverOverlay bank = new WorldSession.RiverOverlay() {
            public int carvedHeight(int x, int z, int raw) {
                return x >= 32 ? 100 : x == 31 ? 101 : x == 30 ? 103 : x == 29 ? 104 : 105;
            }

            public int bedY(int x, int z, int raw) {
                return carvedHeight(x, z, raw) - 1;
            }

            public int waterY(int x, int z) {
                return x >= 32 && x < 48 && z >= 8 && z < 56 ? 100 : Integer.MIN_VALUE;
            }

            public boolean isReady(int x, int z) {
                return x >= 0 && z >= 0 && x < 64 && z < 64;
            }
        };
        int row = -1;
        long chosenSeed = 0;
        outer:
        for (long seed = 0; seed < 1000; seed++)
            for (int z = 16; z < 48; z++)
                if (RiverDetailPlanning.clearanceFirstAir(bank, seed, 29, z, 104, -64, 320) == 105
                        && RiverDetailPlanning.clearanceFirstAir(bank, seed, 31, z, 101, -64, 320) == 101) {
                    row = z;
                    chosenSeed = seed;
                    break outer;
                }
        assertTrue(row >= 0, "fixture must include a real planned small shore rock");
        final long seed = chosenSeed;
        var v = new ArrayList<>(shape().voxels());
        v.add(new TreeShapeGenerator.Voxel(-2, 4, 0, false, 2));
        var s = new TreeShapeGenerator.Shape(v, 6, 2, TreeAge.ADULT, new TreeAge.Footprint(1, 1));
        IntBinaryOperator air = (x, z) -> bank.carvedHeight(x, z, 0);
        assertTrue(clear(s, 31, 100, row, air, bank::waterY));
        assertFalse(TreePlacementValidator.isWholeTreeClear(
                s,
                31,
                100,
                row,
                air,
                (x, z) -> RiverDetailPlanning.clearanceFirstAir(bank, seed, x, z, air.applyAsInt(x, z), -64, 320),
                bank::waterY));
    }

    @Test
    void testFallingCurtainCountsAsWaterAboveTheLowerPool() {
        var session = WorldSession.activate(GenerationProfile.defaultsFor(2, "model", "cpu", "cpu"), 7);
        try {
            var fall = new Waterfall(32, 32, 1, 0, 120, 108, 3, 12);
            session.attachRiverOverlay(new WorldSession.RiverOverlay() {
                public int carvedHeight(int x, int z, int raw) {
                    return raw;
                }

                public int waterY(int x, int z) {
                    return 108;
                }

                public Waterfall waterfallAt(int x, int z) {
                    return fall.contains(x, z) ? fall : null;
                }
            });
            var view = new TreeTerrainView(
                    256,
                    7,
                    session.fingerprint(),
                    key -> {
                        throw new AssertionError("No raw tile needed for water lookup");
                    },
                    metres -> 101);
            assertEquals(120, view.waterY(33, 32));
            assertEquals(108, view.waterY(35, 32));
            var foreign = new TreeTerrainView(
                    256,
                    8,
                    session.fingerprint(),
                    key -> {
                        throw new AssertionError();
                    },
                    metres -> 101);
            assertEquals(Integer.MIN_VALUE, foreign.waterY(33, 32));
        } finally {
            WorldSession.deactivate(session);
        }
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WaterfallTest {
    @TempDir Path temp;

    private record Terrain(int[][] raw, int[] air, int[] water) {}

    private static Terrain terrain() {
        int[][] raw = new int[128][128];
        int[] air = new int[128 * 128], water = new int[128 * 128];
        Arrays.fill(air, 140);
        Arrays.fill(water, PreparedWaterArea.NONE);
        for (int z = 0; z < 128; z++) Arrays.fill(raw[z], 140);
        for (int z = 60; z <= 68; z++)
            for (int x = 16; x <= 111; x++) {
                int level = 130 - Math.max(0, Math.min(12, x - 64));
                water[z * 128 + x] = level;
                air[z * 128 + x] = level - 2;
                raw[z][x] = level + 1;
            }
        return new Terrain(raw, air, water);
    }

    private static PreparedWaterArea area(int ox, int oz, Waterfall fall) {
        var t = terrain();
        WaterfallPlanner.apply(fall, ox, oz, 128, t.air, t.water);
        return new PreparedWaterArea(ox, oz, 128, t.air, t.water, List.of(fall));
    }

    private static Waterfall fall() {
        return new Waterfall(64, 64, 1, 0, 130, 118, 5, 12);
    }

    @Test
    void detectsAnExistingSteepRiverDescentAndRefusesFlatWater() {
        var t = terrain();
        var falls = WaterfallPlanner.select(0, 0, 128, t.raw, t.water);
        assertEquals(1, falls.size());
        assertEquals(12, falls.getFirst().upperY() - falls.getFirst().lowerY());
        assertEquals(falls, WaterfallPlanner.select(0, 0, 128, t.raw, t.water));
        for (int i = 0; i < t.water.length; i++) if (t.water[i] != PreparedWaterArea.NONE) t.water[i] = 130;
        assertTrue(WaterfallPlanner.select(0, 0, 128, t.raw, t.water).isEmpty());
    }

    @Test
    void refusesAnApparentDropThatDoesNotExistInRawTerrain() {
        var t = terrain();
        for (int[] row : t.raw) Arrays.fill(row, 131);
        assertTrue(WaterfallPlanner.select(0, 0, 128, t.raw, t.water).isEmpty());
    }

    @Test
    void cliffPoolAndSourceAreConnectedAndOnlyTheirFootprintChanges() {
        var t = terrain();
        int[] beforeAir = t.air.clone(), beforeWater = t.water.clone();
        var f = fall();
        WaterfallPlanner.apply(f, 0, 0, 128, t.air, t.water);
        var a = new PreparedWaterArea(0, 0, 128, t.air, t.water, List.of(f));
        for (int z = 0; z < 128; z++)
            for (int x = 0; x < 128; x++)
                if (!f.contains(x, z)) {
                    assertEquals(beforeAir[z * 128 + x], t.air[z * 128 + x]);
                    assertEquals(beforeWater[z * 128 + x], t.water[z * 128 + x]);
                }
        assertEquals(130, a.waterY(64, 64));
        assertEquals(118, a.waterY(65, 64));
        assertTrue(a.bedY(64, 64, 0) - a.bedY(65, 64, 0) >= 12);
        assertTrue(a.waterY(65, 64) - a.bedY(65, 64, 0) >= 5);
        assertTrue(f.rockColumn(64, 64));
        assertFalse(f.rockColumn(67, 64));
        List<RiverDetailPlanning.Detail> details = new ArrayList<>();
        RiverDetailPlanning.forChunk(a, 7, 64, 64, -64, 320, details::add);
        assertTrue(
                details.stream().noneMatch(d -> f.contains(d.x(), d.z())),
                "no plants or slabs across the falling curtain");
    }

    @Test
    void sourcesStayAtTheLipAndEveryVerticalCellHasAFeedAboveOrBesideIt() {
        var f = fall();
        var a = area(0, 0, f);
        List<WaterfallPlacement.Placement> placements = new ArrayList<>();
        for (int z = 0; z < 128; z += 16)
            for (int x = 0; x < 128; x += 16) WaterfallPlacement.forChunk(a, x, z, -64, 320, placements::add);
        Set<String> points = new HashSet<>();
        for (var p : placements) points.add(p.x() + "," + p.y() + "," + p.z());
        int sources = 0, falling = 0;
        for (var p : placements) {
            if (p.kind() == WaterfallPlacement.Kind.SOURCE) {
                sources++;
                assertEquals(130, p.y());
                assertEquals(64, p.x());
            } else {
                falling++;
                assertEquals(65, p.x());
                String feed = p.y() == 130 ? "64," + p.y() + "," + p.z() : p.x() + "," + (p.y() + 1) + "," + p.z();
                assertTrue(points.contains(feed));
            }
        }
        assertEquals(5, sources);
        assertEquals(5 * 12, falling);
    }

    @Test
    void splitChunksAndReloadKeepExactlyTheSameWaterfall() throws Exception {
        var f = new Waterfall(31, 64, 1, 0, 130, 118, 5, 12);
        var a = area(0, 0, f);
        var store = new PreparedWaterStore(temp, 7, "profile");
        store.save(a);
        var loaded = store.loadAll().getFirst();
        assertEquals(a.waterfalls(), loaded.waterfalls());
        assertArrayEquals(a.firstAirCopy(), loaded.firstAirCopy());
        assertArrayEquals(a.waterCopy(), loaded.waterCopy());
        Set<WaterfallPlacement.Placement> forward = new HashSet<>(), reverse = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            WaterfallPlacement.forChunk(
                    a,
                    i % 8 * 16,
                    i / 8 * 16,
                    -64,
                    320,
                    p -> {
                        assertTrue(forward.add(p), "duplicate across chunk edges");
                    });
            int j = 63 - i;
            WaterfallPlacement.forChunk(loaded, j % 8 * 16, j / 8 * 16, -64, 320, reverse::add);
        }
        assertEquals(65, forward.size());
        assertEquals(forward, reverse);
    }

    @Test
    void ordinaryUpperAndLowerWaterStillPassContainment() {
        var a = area(0, 0, fall());
        var columns =
                new RiverWaterPlanning.ColumnAccess() {
                    public boolean isAir(int x, int y, int z) {
                        return y >= a.carvedHeight(x, z, 140);
                    }

                    public boolean isSolidDry(int x, int y, int z) {
                        return !isAir(x, y, z);
                    }
                };
        int expected = 0, placed = 0;
        for (int z = 0; z < 128; z++)
            for (int x = 0; x < 128; x++)
                if (a.waterY(x, z) != PreparedWaterArea.NONE) expected += a.waterY(x, z) - a.bedY(x, z, 0);
        for (int cz = 0; cz < 8; cz++)
            for (int cx = 0; cx < 8; cx++) placed += RiverWaterPlanning.planChunk(a, cx, cz, -64, 320, columns).size();
        assertEquals(expected, placed);
    }

    @Test
    void oldVersionOneAreaLoadsWithoutInventingWaterfalls() throws Exception {
        var t = terrain();
        Path file = temp.resolve("water_0_0.ewh");
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(0x45574831);
            out.writeInt(1);
            out.writeLong(7);
            out.writeUTF("profile");
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(128);
            for (int h : t.air) out.writeInt(h);
            for (int h : t.water) out.writeInt(h);
        }
        var a = new PreparedWaterStore(temp, 7, "profile").loadAll().getFirst();
        assertTrue(a.waterfalls().isEmpty());
        assertArrayEquals(t.air, a.firstAirCopy());
    }

    @Test
    void rejectsMetadataOutsideTheAreaOrInconsistentWithTheStoredWater() {
        var t = terrain();
        assertThrows(
                IllegalArgumentException.class,
                () -> new PreparedWaterArea(0, 0, 128, t.air, t.water, List.of(fall())));
        assertThrows(IllegalArgumentException.class, () -> new Waterfall(64, 64, 1, 1, 130, 118, 5, 12));
        assertThrows(IllegalArgumentException.class, () -> area(0, 0, new Waterfall(9, 9, -1, 0, 130, 118, 5, 12)));
    }

    @Test
    void allDirectionsAndNegativeCoordinatesAreInsideTheirOwnFootprint() {
        for (int[] dir : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            var f = new Waterfall(-448, 576, dir[0], dir[1], 130, 118, 3, 12);
            var a = area(-512, 512, f);
            assertSame(f, a.waterfallAt(f.worldX(1, 0), f.worldZ(1, 0)));
            assertNull(a.waterfallAt(-512, 512));
            assertEquals(f.upperY(), a.waterY(f.worldX(0, 0), f.worldZ(0, 0)));
        }
    }

    @Test
    void refusesToPublishAPartialVerticalCurtainOutsideWorldHeight() {
        List<WaterfallPlacement.Placement> placements = new ArrayList<>();
        WaterfallPlacement.forChunk(area(0, 0, fall()), 64, 64, -64, 129, placements::add);
        assertTrue(placements.isEmpty());
    }

    @Test
    void malformedVersionTwoMetadataFailsDuringLoadEvenWithValidGzip() throws Exception {
        var a = area(0, 0, fall());
        Path file = temp.resolve("water_0_0.ewh");
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(0x45574831);
            out.writeInt(2);
            out.writeLong(7);
            out.writeUTF("profile");
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(128);
            for (int h : a.firstAirCopy()) out.writeInt(h);
            for (int h : a.waterCopy()) out.writeInt(h);
            out.writeInt(1);
            out.writeInt(64);
            out.writeInt(64);
            out.writeInt(1);
            out.writeInt(0);
            out.writeInt(131);
            out.writeInt(118);
            out.writeInt(5);
            out.writeInt(12);
        }
        assertThrows(IOException.class, () -> new PreparedWaterStore(temp, 7, "profile").loadAll());
    }
}

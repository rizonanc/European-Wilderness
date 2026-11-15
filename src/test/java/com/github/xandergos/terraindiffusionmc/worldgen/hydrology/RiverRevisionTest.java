package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RiverRevisionTest {
    @TempDir Path temp;
    private static final int NONE = PreparedWaterArea.NONE;

    @Test
    void testFrequencyUsesWholeStableBasins() {
        for (boolean lake : new boolean[] {false, true}) {
            int removed = 0;
            for (int i = 0; i < 20000; i++) {
                int x = i * 2 - 20000, z = i * 74 - 100000;
                boolean keep = WaterFrequency.keep(12345, x, z, lake);
                assertEquals(keep, WaterFrequency.keep(12345, x, z, lake));
                if (!keep) removed++;
            }
            assertTrue(removed > 1800 && removed < 2200, "Removed " + removed + " out of 20000");
        }
    }

    @Test
    void testRejectedLakeDisappearsAsAWhole() {
        int[][] raw = new int[64][64];
        for (int z = 0; z < 64; z++)
            for (int x = 0; x < 64; x++) raw[z][x] = x >= 12 && x < 52 && z >= 12 && z < 52 ? 83 : 103;
        boolean dropped = false, kept = false;
        for (int seed = 0; seed < 40; seed++) {
            var old = PreparedWaterArea.calculate(seed, 0, 0, raw, null, 1);
            var revised = PreparedWaterArea.calculate(seed, 0, 0, raw, null, 2);
            if (WaterFrequency.keep(seed, 12, 12, true)) {
                assertArrayEquals(old.water(), revised.water());
                assertArrayEquals(old.air(), revised.air());
                kept = true;
            } else {
                assertEquals(0, Arrays.stream(revised.water()).filter(w -> w != NONE).count());
                dropped = true;
            }
        }
        assertTrue(dropped && kept);
    }

    @Test
    void testCoastalConnectionCutsTheSandBar() {
        int side = 128;
        int[][] raw = new int[side][side];
        int[] air = new int[side * side], water = new int[air.length];
        Arrays.fill(water, NONE);
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) air[z * side + x] = raw[z][x] = x >= 80 ? 58 : 65;
        for (int x = 20; x <= 74; x++) for (int z = 62; z <= 66; z++) water[z * side + x] = 63;
        assertTrue(RiverMouths.connectOne(raw, water, 70, 64, 80, 64));
        RiverBanks.shape(7, 0, 0, side, air, water, new boolean[64 * 64]);
        for (int x = 70; x <= 86; x++) {
            assertEquals(62, water[64 * side + x]);
            assertTrue(air[64 * side + x] <= 62);
        }
        assertEquals(NONE, water[64 * side + 90]);
        assertEquals(58, air[64 * side + 90], "Natural ocean must not be filled to a retaining wall");
        var view = new PreparedWaterView(0, 0, side, air, water, List.of());
        var sealed = new ArrayList<String>();
        WaterLining.forChunk(
                view,
                80,
                64,
                -64,
                319,
                (x, y, z, sand) -> {
                    if (x == 90 && z == 64) sealed.add(y + ":" + sand);
                });
        assertTrue(sealed.isEmpty());
        var columns =
                new RiverWaterPlanning.ColumnAccess() {
                    public boolean isAir(int x, int y, int z) {
                        return y >= air[z * side + x] && !isWater(x, y, z);
                    }

                    public boolean isWater(int x, int y, int z) {
                        return raw[z][x] <= 62 && y >= air[z * side + x] && y <= 62;
                    }

                    public boolean isSolidDry(int x, int y, int z) {
                        return y < air[z * side + x];
                    }
                };
        int x = 89, z = 64, bed = view.bedY(x, z, 0);
        assertEquals(62, view.waterY(x, z));
        assertTrue(FluidContainment.canPlace(FluidContainment.Fluid.WATER, view, x, z, bed, 62, -64, 319, columns));
    }

    @Test
    void mouthConnectionDoesNotCutThroughAHighCoastalCliff() {
        int[][] raw = new int[64][64];
        for (var row : raw) Arrays.fill(row, 65);
        for (int z = 0; z < 64; z++) for (int x = 40; x < 64; x++) raw[z][x] = 58;
        for (int z = 0; z < 64; z++) raw[z][36] = 100;
        int[] water = new int[4096];
        Arrays.fill(water, NONE);
        assertFalse(RiverMouths.connectOne(raw, water, 30, 32, 40, 32));
        assertEquals(0, Arrays.stream(water).filter(w -> w != NONE).count());
    }

    @Test
    void revisedDrainageActuallyInvokesTheCoastalConnector() {
        int side = 128;
        int[][] raw = new int[side][side];
        for (int z = 0; z < side; z++)
            for (int x = 0; x < side; x++) raw[z][x] = x >= 80 ? 58 : 65 + (80 - x) / 6 + Math.abs(z - 64) / 16;
        boolean found = false;
        for (int seed = 0; seed < 8 && !found; seed++) {
            var result = PreparedWaterArea.calculate(seed, 0, 0, raw, null, 2);
            found = Arrays.stream(result.water()).anyMatch(w -> w == 62);
        }
        assertTrue(found, "The production drainage path must reach real sea level, not only the connector helper");
    }

    @Test
    void testRiverTraversesAWideDryBeachBeforeEndingAtTheActualSea() {
        int side = 128;
        int[][] raw = new int[side][side];
        for (int z = 0; z < side; z++)
            for (int x = 0; x < side; x++)
                raw[z][x] = x >= 100 ? 58 : x >= 40 ? 64 : 65 + (40 - x) / 3 + Math.abs(z - 64) / 8;
        boolean reached = false;
        for (int seed = 0; seed < 8 && !reached; seed++) {
            var result = PreparedWaterArea.calculate(seed, 0, 0, raw, null, 2);
            for (int z = 10; z < side - 10; z++)
                if (result.water()[z * side + 100] == 62) {
                    reached = true;
                    break;
                }
        }
        assertTrue(reached, "A y=64 beach must not be mistaken for the sea sixty blocks early");
    }

    @Test
    void oceanWaterIsAnOutletForWaterButNotForLava() {
        int[] air = new int[1024], water = new int[1024];
        Arrays.fill(air, 63);
        Arrays.fill(water, NONE);
        air[16 * 32 + 16] = 62;
        air[16 * 32 + 17] = 58;
        water[16 * 32 + 16] = 62;
        var view = new PreparedWaterView(0, 0, 32, air, water, List.of());
        var columns =
                new RiverWaterPlanning.ColumnAccess() {
                    public boolean isAir(int x, int y, int z) {
                        return x == 16 && z == 16 && y >= 62;
                    }

                    public boolean isWater(int x, int y, int z) {
                        return x == 17 && z == 16 && y >= 58 && y <= 62;
                    }

                    public boolean isSolidDry(int x, int y, int z) {
                        return y < air[z * 32 + x];
                    }
                };
        assertTrue(FluidContainment.canPlace(FluidContainment.Fluid.WATER, view, 16, 16, 61, 62, -64, 319, columns));
        assertFalse(FluidContainment.canPlace(FluidContainment.Fluid.LAVA, view, 16, 16, 61, 62, -64, 319, columns));
    }

    @Test
    void versionTwoPersistsSeaLevelAndAlgorithmRevision() throws Exception {
        var grid = new WorldWaterGrid(64, 32, 128);
        var cell = new WorldWaterGrid.Cell(0, 0);
        var store = new WorldWaterStore(temp, 7, "fp", 256, grid);
        var unit =
                new WorldWaterUnit(
                        grid, cell, new int[] {64 * 128 + 64}, new short[] {62}, new byte[] {0}, List.of(), 2);
        store.save(unit);
        assertEquals(2, store.loadUnit(cell).revision);
        assertArrayEquals(unit.levels, store.loadUnit(cell).levels);
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldWaterUnit(grid, cell, unit.indices, unit.levels, unit.lakes, List.of(), 1));
        int[] air = new int[4096], water = new int[4096];
        Arrays.fill(air, 62);
        Arrays.fill(water, 62);
        store.save(new WorldWaterTile(grid, cell, air, water, List.of(), 2));
        assertEquals(2, store.loadTile(cell).revision);
        assertArrayEquals(water, store.loadTile(cell).water);
    }

    @Test
    void testRevisionFourRoundTrips() throws Exception {
        var grid = new WorldWaterGrid(64, 32, 128);
        var cell = new WorldWaterGrid.Cell(0, 0);
        var store = new WorldWaterStore(temp, 7, "fp", 256, grid);
        var old =
                new WorldWaterUnit(
                        grid, cell, new int[] {64 * 128 + 64}, new short[] {100}, new byte[] {0}, List.of(), 3);
        store.save(old);
        byte[] before = Files.readAllBytes(temp.resolve("units/0_0.eww"));
        assertThrows(
                IOException.class,
                () ->
                        store.save(
                                new WorldWaterUnit(
                                        grid, cell, old.indices, new short[] {102}, old.lakes, List.of(), 4)));
        assertArrayEquals(before, Files.readAllBytes(temp.resolve("units/0_0.eww")));
        assertEquals(3, store.loadUnit(cell).revision);
        int[] air = new int[4096], water = new int[4096];
        Arrays.fill(air, 100);
        Arrays.fill(water, 100);
        store.save(new WorldWaterTile(grid, cell, air, water, List.of(), 4));
        assertEquals(4, store.loadTile(cell).revision);
        assertArrayEquals(air, store.loadTile(cell).air);
    }

    private DataOutputStream legacy(String folder, int type) throws Exception {
        Files.createDirectories(temp.resolve(folder));
        var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp.resolve(folder + "/0_0.eww"))));
        out.writeInt(0x45575731);
        out.writeInt(1);
        out.writeInt(type);
        out.writeLong(7);
        out.writeUTF("fp");
        for (int value : new int[] {256, 64, 32, 128, 0, 0}) out.writeInt(value);
        return out;
    }

    @Test
    void versionOneFilesStillLoadAsFrozenRevisionOne() throws Exception {
        try (var out = legacy("units", 1)) {
            out.writeInt(1);
            out.writeInt(64 * 128 + 64);
            out.writeShort(102);
            out.writeByte(0);
            out.writeInt(0);
        }
        try (var out = legacy("tiles", 2)) {
            for (int i = 0; i < 4096; i++) out.writeShort(103);
            for (int i = 0; i < 4096; i++) out.writeShort(Short.MIN_VALUE);
            out.writeInt(0);
        }
        var store = new WorldWaterStore(temp, 7, "fp", 256, new WorldWaterGrid(64, 32, 128));
        var key = new WorldWaterGrid.Cell(0, 0);
        assertEquals(1, store.loadUnit(key).revision);
        assertEquals(1, store.loadTile(key).revision);
        assertEquals(103, store.loadTile(key).air[0]);
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LodBridgeTest {
    private LodColumn column() {
        int[] air = new int[1024], water = new int[1024];
        short[] biome = new short[1024];
        Arrays.fill(air, 101);
        Arrays.fill(water, Integer.MIN_VALUE);
        Arrays.fill(biome, (short) 1);
        return new LodColumn(-32, 0, -64, 319, air, water, biome);
    }

    @Test
    void testVerticalProfileHasStoneSoilSurfaceAndAir() {
        var c = column();
        assertEquals(LodColumn.Kind.STONE, c.kind(0, 96, 0));
        assertEquals(LodColumn.Kind.DIRT, c.kind(0, 99, 0));
        assertEquals(LodColumn.Kind.GRASS, c.kind(0, 100, 0));
        assertEquals(LodColumn.Kind.AIR, c.kind(0, 101, 0));
        assertEquals(LodColumn.Kind.AIR, c.kind(0, -65, 0));
        assertThrows(IllegalArgumentException.class, () -> c.kind(32, 0, 0));
    }

    @Test
    void testOceanRiverSnowAndDesertRemainDistinct() {
        int[] air = new int[1024], water = new int[1024];
        short[] biome = new short[1024];
        Arrays.fill(air, 61);
        Arrays.fill(water, 62);
        air[1] = 100;
        water[1] = 105;
        air[2] = 101;
        water[2] = Integer.MIN_VALUE;
        biome[2] = 3;
        air[3] = 101;
        water[3] = Integer.MIN_VALUE;
        biome[3] = 5;
        var c = new LodColumn(0, 0, -64, 319, air, water, biome);
        Arrays.fill(air, 0);
        Arrays.fill(water, 0);
        Arrays.fill(biome, (short) 0);
        assertEquals(LodColumn.Kind.SAND, c.kind(0, 60, 0));
        assertEquals(LodColumn.Kind.WATER, c.kind(0, 62, 0));
        assertEquals(LodColumn.Kind.AIR, c.kind(0, 63, 0));
        assertEquals(LodColumn.Kind.WATER, c.kind(1, 105, 0));
        assertEquals(LodColumn.Kind.AIR, c.kind(1, 106, 0));
        assertEquals(LodColumn.Kind.SNOW, c.kind(2, 100, 0));
        assertEquals(LodColumn.Kind.SAND, c.kind(3, 100, 0));
    }

    @Test
    void testExistingVoxyCubesAreNeverWritten() throws Exception {
        var occupied = new HashSet<Integer>(Set.of(0));
        var written = new ArrayList<Integer>();
        var sink = new LodPublisher.Sink() {
            public boolean active() {
                return true;
            }

            public boolean occupied(int x, int y, int z) {
                assertEquals(-1, x);
                assertEquals(0, z);
                return occupied.contains(y);
            }

            public void write(LodColumn column, int y) {
                assertTrue(occupied.add(y));
                written.add(y);
            }
        };
        var first = LodPublisher.publish(column(), sink, () -> true);
        assertTrue(first.complete());
        assertEquals(1, first.skipped());
        assertEquals(5, first.written());
        var second = LodPublisher.publish(column(), sink, () -> true);
        assertEquals(0, second.written());
        assertEquals(6, second.skipped());
        assertEquals(5, written.size());
    }

    @Test
    void testCancellationOrWorldRetirementStopsBetweenWholeCubes() throws Exception {
        var valid = new AtomicBoolean(true);
        var writes = new AtomicInteger();
        var sink = new LodPublisher.Sink() {
            public boolean active() {
                return true;
            }

            public boolean occupied(int x, int y, int z) {
                return false;
            }

            public void write(LodColumn column, int y) {
                writes.incrementAndGet();
                valid.set(false);
            }
        };
        var result = LodPublisher.publish(column(), sink, valid::get);
        assertFalse(result.complete());
        assertEquals(1, result.written());
        assertEquals(1, writes.get());
        assertEquals(0, LodPublisher.publish(column(), sink, () -> false).written());
    }

    @Test
    void inactiveEngineReceivesNoWrites() throws Exception {
        var result = LodPublisher.publish(
                column(),
                new LodPublisher.Sink() {
                    public boolean active() {
                        return false;
                    }

                    public boolean occupied(int x, int y, int z) {
                        fail();
                        return false;
                    }

                    public void write(LodColumn c, int y) {
                        fail();
                    }
                },
                () -> true);
        assertFalse(result.complete());
        assertEquals(0, result.written());
    }

    @Test
    void testScanIsBoundedUsesNegativeCoordinates() {
        var scan = new LodScan();
        scan.move(-1, -1, 64);
        var first = scan.next();
        assertEquals(new LodScan.Cell(-1, -1), first);
        scan.completed(first);
        assertNotEquals(first, scan.next());
        scan.move(1024, 0, 64);
        assertEquals(new LodScan.Cell(32, 0), scan.next());
        for (int i = 0; i < 18000; i++) scan.completed(new LodScan.Cell(i, 0));
        assertEquals(16384, scan.remembered());
        assertThrows(IllegalArgumentException.class, () -> scan.move(0, 0, 32769));
    }

    @Test
    void testUnsupportedVersionsAndCompetingSeedGeneratorFailClosed() {
        assertNull(VoxySupport.problem("0.2.16-beta", false));
        assertNotNull(VoxySupport.problem("0.2.18-beta", false));
        assertNotNull(VoxySupport.problem("0.2.16-beta", true));
        assertNotNull(VoxySupport.problem(null, false));
    }

    @Test
    void testCursorWritesAtMostOneCube() throws Exception {
        var writes = new AtomicInteger();
        var sink = new LodPublisher.Sink() {
            public boolean active() {
                return true;
            }

            public boolean occupied(int x, int y, int z) {
                return false;
            }

            public void write(LodColumn c, int y) {
                writes.incrementAndGet();
            }
        };
        var cursor = new LodPublisher.Cursor(column());
        assertEquals(0, cursor.step(sink, () -> false).written());
        for (int i = 0; i < 6; i++) {
            var result = cursor.step(sink, () -> true);
            assertEquals(1, result.written());
            assertEquals(i == 5, result.complete());
        }
        assertEquals(0, cursor.step(sink, () -> true).written());
        assertEquals(6, writes.get());
    }

    @Test
    void testReusableEncoderPreservesAxesWaterAndLight() throws Exception {
        int[] air = new int[1024], water = new int[1024];
        short[] biomes = new short[1024];
        Arrays.fill(air, 101);
        Arrays.fill(water, Integer.MIN_VALUE);
        for (int z = 0; z < 32; z++)
            for (int x = 16; x < 32; x++) {
                water[z * 32 + x] = 105;
                biomes[z * 32 + x] = 5;
            }
        var column = new LodColumn(-32, 32, -64, 319, air, water, biomes);
        var resolutions = new AtomicInteger();
        var encoder = new LodVoxelEncoder(column, b -> {
            resolutions.incrementAndGet();
            long[][] p = new long[7][2];
            for (int k = 0; k < 7; k++) for (int l = 0; l < 2; l++) p[k][l] = b * 10000 + k * 100 + l;
            return p;
        });
        assertEquals(1024, resolutions.get());
        long[] buffer = new long[4681];
        Arrays.fill(buffer, -999);
        assertEquals(1280, encoder.fill(0, 6, 0, buffer));
        assertEquals(301, buffer[4 * 256]);
        assertEquals(200, buffer[3 * 256]);
        assertEquals(1, buffer[15 * 256 + 255]);
        assertEquals(-999, buffer[4096]);
        assertEquals(2560, encoder.fill(1, 6, 1, buffer));
        assertEquals(50401, buffer[4 * 256]);
        assertEquals(50601, buffer[9 * 256]);
        assertEquals(0, encoder.fill(1, 7, 1, buffer));
        assertEquals(50001, buffer[0]);
        assertEquals(1024, resolutions.get());
    }

    @Test
    void testWorldGenCompatibilityIsVersionPinned() {
        assertTrue(WorldGenCompat.supports("2.2.4"));
        assertFalse(WorldGenCompat.supports("2.2.3"));
        assertEquals(1024, WorldGenCompat.radiusBlocks(64));
        assertEquals(32768, WorldGenCompat.radiusBlocks(Integer.MAX_VALUE));
        assertEquals(64, WorldGenCompat.radiusBlocks(-1));
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.tree.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LodForestTest {
    private LodColumn ground() {
        int[] air = new int[1024], water = new int[1024];
        Arrays.fill(air, 101);
        Arrays.fill(water, Integer.MIN_VALUE);
        return new LodColumn(-32, -32, -64, 319, air, water, new short[1024]);
    }

    @Test
    void testFoliageAndTrunksEncodeWithTheirOwnPalettes() throws Exception {
        var builder = new LodColumn.ForestBuilder(ground());
        builder.set(-17, 130, -16, TreeSpecies.BIRCH, true);
        builder.set(-17, 130, -16, TreeSpecies.OAK, false);
        builder.set(-16, 131, -17, TreeSpecies.PINE, false);
        builder.set(-17, 100, -16, TreeSpecies.OAK, true);
        var column = builder.build();
        assertEquals(131, column.maxY);
        assertEquals(LodColumn.Kind.BIRCH_LOG, column.kind(15, 130, 16));
        assertEquals(LodColumn.Kind.SPRUCE_LEAVES, column.kind(16, 131, 15));
        assertEquals(LodColumn.Kind.GRASS, column.kind(15, 100, 16));
        assertThrows(IllegalStateException.class, () -> builder.set(-17, 132, -16, TreeSpecies.BIRCH, true));
        var encoder =
                new LodVoxelEncoder(
                        column,
                        b -> {
                            long[][] palette = new long[LodColumn.Kind.values().length][2];
                            for (int k = 0; k < palette.length; k++) Arrays.fill(palette[k], k + 100);
                            return palette;
                        });
        long[] data = new long[4681];
        encoder.fill(0, 8, 1, data);
        assertEquals(LodColumn.Kind.BIRCH_LOG.ordinal() + 100, data[2 * 256 + 15]);
        encoder.fill(1, 8, 0, data);
        assertEquals(LodColumn.Kind.SPRUCE_LEAVES.ordinal() + 100, data[3 * 256 + 15 * 16]);
    }

    @Test
    void testCursorPublishesCanopy() throws Exception {
        var builder = new LodColumn.ForestBuilder(ground());
        builder.set(-20, 140, -20, TreeSpecies.OAK, false);
        var column = builder.build();
        List<Integer> order = new ArrayList<>();
        var cursor = new LodPublisher.Cursor(column);
        var sink =
                new LodPublisher.Sink() {
                    public boolean active() {
                        return true;
                    }

                    public boolean occupied(int x, int y, int z) {
                        return false;
                    }

                    public void write(LodColumn c, int y) {
                        order.add(y);
                    }
                };
        cursor.step(sink, () -> true);
        assertEquals(List.of(4), order);
        cursor.step(sink, () -> false);
        assertEquals(List.of(4), order);
        while (!cursor.step(sink, () -> true).complete()) {}
        assertEquals(List.of(4, 3, 2, 1, 0, -1, -2), order);
    }

    @Test
    void testStaleHighLodIsClearedOnlyWhereDataExists() throws Exception {
        var column = ground();
        var cursor = new LodPublisher.Cursor(column, true);
        List<Integer> writes = new ArrayList<>();
        var sink =
                new LodPublisher.Sink() {
                    public boolean active() {
                        return true;
                    }

                    public boolean occupied(int x, int y, int z) {
                        return false;
                    }

                    public boolean existing(int x, int y, int z) {
                        return y == 7;
                    }

                    public void write(LodColumn c, int y) {
                        writes.add(y);
                        if (y == 7) assertEquals(LodColumn.Kind.AIR, c.kind(0, y * 32, 0));
                    }
                };
        while (!cursor.step(sink, () -> true).complete()) {}
        assertEquals(List.of(3, 2, 1, 0, -1, -2, 7), writes);
    }
}

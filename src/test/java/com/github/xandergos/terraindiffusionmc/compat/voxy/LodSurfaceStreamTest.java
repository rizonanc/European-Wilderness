package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.tree.TreeSpecies;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LodSurfaceStreamTest {
    private LodColumn ground(int x, int z) {
        int[] air = new int[1024], water = new int[1024];
        Arrays.fill(air, 101);
        Arrays.fill(water, Integer.MIN_VALUE);
        return new LodColumn(x, z, -64, 2031, air, water, new short[1024]);
    }

    @Test
    void testSurfaceCompletesBeforeBuriedCubes() throws Exception {
        var builder = new LodColumn.ForestBuilder(ground(-32, 32));
        builder.set(-20, 140, 40, TreeSpecies.OAK, false);
        var column = builder.build();
        List<Integer> order = new ArrayList<>();
        var sink =
                new LodPublisher.Sink() {
                    public boolean active() {
                        return true;
                    }

                    public boolean occupied(int x, int y, int z) {
                        return false;
                    }

                    public boolean existing(int x, int y, int z) {
                        return y == 9;
                    }

                    public void write(LodColumn c, int y) {
                        order.add(y);
                    }
                };
        var cursor = LodPublisher.Cursor.visible(column, 9);
        while (!cursor.step(sink, () -> true).complete()) {}
        assertEquals(List.of(4, 3, 2, 9), order);
        var progress = cursor.progress();
        assertEquals(1, progress.y());
        assertFalse(progress.forestPending());
        boolean complete = false;
        while (!complete) {
            cursor = LodPublisher.Cursor.resume(column, progress);
            var result = cursor.step(sink, () -> true);
            complete = result.complete();
            progress = cursor.progress();
        }
        assertEquals(List.of(4, 3, 2, 9, 1, 0, -1, -2), order);
    }

    @Test
    void testForestUpgradeRepublishesNewCanopy() throws Exception {
        var column = ground(0, 0).awaitingForest();
        var cursor = LodPublisher.Cursor.visible(column, -3);
        List<Integer> order = new ArrayList<>();
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
        while (!cursor.step(sink, () -> true).complete()) {}
        assertTrue(cursor.progress().forestPending());
        var trees = new LodColumn.ForestBuilder(ground(0, 0));
        trees.set(16, 135, 16, TreeSpecies.OAK, false);
        cursor = LodPublisher.Cursor.visible(trees.build(), 3);
        while (!cursor.step(sink, () -> true).complete()) {}
        assertFalse(cursor.progress().forestPending());
        assertEquals(List.of(3, 2, 4, 3, 2), order);
    }

    @Test
    void testHighStaleTerrainIsClearedBeforeWaitingForForest() throws Exception {
        var column = ground(0, 0).awaitingForest();
        var cursor = LodPublisher.Cursor.visible(column, 9);
        Set<Integer> stale = new HashSet<>(List.of(5, 7, 9));
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
                        return stale.contains(y);
                    }

                    public void write(LodColumn c, int y) {
                        writes.add(y);
                        if (y > 3) {
                            assertEquals(LodColumn.Kind.AIR, c.kind(0, y * 32, 0));
                            stale.remove(y);
                        }
                    }
                };
        cursor.step(sink, () -> true);
        assertFalse(cursor.step(sink, () -> false).complete());
        while (!cursor.step(sink, () -> true).complete()) {}
        assertTrue(stale.isEmpty());
        assertEquals(List.of(3, 2, 5, 7, 9), writes);
        assertTrue(cursor.progress().forestPending());
        assertEquals(1, cursor.progress().y());
        var resume = LodPublisher.Cursor.resume(ground(0, 0), cursor.progress());
        while (!resume.step(sink, () -> true).complete()) {}
        assertEquals(List.of(3, 2, 5, 7, 9, 1, 0, -1, -2), writes);
    }

    @Test
    void testScanSharesTimeWithDetailsRotatesPendingColumns() {
        var scan = new LodScan();
        scan.move(0, 0, 64);
        var saved = new LodPublisher.Progress(1, 5, 9, false);
        var first = scan.next();
        scan.surfaceCompleted(first, saved);
        var second = scan.next();
        scan.surfaceCompleted(second, saved);
        scan.completed(scan.next());
        scan.completed(scan.next());
        assertEquals(first, scan.next());
        scan.surfaceCompleted(first, saved);
        for (int i = 0; i < 4; i++) {
            var cell = scan.next();
            assertNull(scan.progress(cell));
            scan.completed(cell);
        }
        assertEquals(second, scan.next(), "one pending column must not monopolise all detail work");
        for (int i = 0; i < 17000; i++) scan.surfaceCompleted(new LodScan.Cell(i, 100), saved);
        assertEquals(16384, scan.remembered());
    }

    private record Section(int level, int x, int y, int z) {}

    private static final class Index implements LodSkyIndex.Reader {
        final Map<Section, Integer> data = new HashMap<>();
        int reads;

        static int child(int x, int y, int z) {
            return (x & 1) | ((y & 1) << 2) | ((z & 1) << 1);
        }

        void add(int x, int y, int z) {
            data.put(new Section(0, x, y, z), 1);
            for (int level = 1; level <= 4; level++)
                data.merge(
                        new Section(level, x >> level, y >> level, z >> level),
                        1 << child(x >> (level - 1), y >> (level - 1), z >> (level - 1)),
                        (a, b) -> a | b);
        }

        public int mask(int level, int x, int y, int z) {
            reads++;
            return data.getOrDefault(new Section(level, x, y, z), 0);
        }

        public boolean childPresent(int mask, int x, int y, int z) {
            return (mask & (1 << child(x, y, z))) != 0;
        }
    }

    @Test
    void testHierarchyIgnoresNeighbours() throws Exception {
        var index = new Index();
        index.add(-1, -2, 1);
        index.add(-1, 9, 1);
        index.add(-2, 60, 1);
        index.add(-1, 61, 2);
        assertEquals(9, LodSkyIndex.highest(ground(-32, 32), index));
        assertTrue(index.reads <= 12, "reads=" + index.reads);
    }

    @Test
    void testEmptyWorldRequiresOnlyRootLookups() throws Exception {
        var index = new Index();
        assertEquals(-3, LodSkyIndex.highest(ground(0, 0), index));
        assertEquals(5, index.reads);
        index = new Index();
        index.add(0, -2, 0);
        assertEquals(-2, LodSkyIndex.highest(ground(0, 0), index));
    }
}

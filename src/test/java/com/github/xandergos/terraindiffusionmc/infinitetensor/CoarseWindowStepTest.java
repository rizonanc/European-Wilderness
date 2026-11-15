package com.github.xandergos.terraindiffusionmc.infinitetensor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 4.3 regression: prepareIndependentWindowStep computes exactly one uncached
 * window per call and reports an explicit status (1=computed, 0=slice already complete),
 * so the coarse LOD controller can finish a whole patch inside one background claim.
 * Window ranges include the boundary window below pixel 0 (getLowestIntersection yields
 * -1 when the first pixels fall inside window -1's span), so the expected counts here
 * follow the production enumeration. Composition VALUES (weighted overlap blending via
 * addFrom) belong to the production design and are intentionally not re-specified here. */
class CoarseWindowStepTest {
    private static final int IN = 64, STRIDE = 32;

    private static InfiniteTensor tensor(MemoryTileStore store, String id, long tag) {
        return store.getOrCreate(
                id,
                new Integer[] {7, null, null},
                (wi, args) -> {
                    var out = new FloatTensor(new int[] {7, IN, IN});
                    for (int c = 0; c < 7; c++)
                        for (int p = 0; p < IN * IN; p++)
                            out.data[c * IN * IN + p] =
                                    tag * 1_000_000F + wi[1] * 10_000F + wi[2] * 10F + c + p * 1e-4F;
                    return out;
                },
                new TensorWindow(new int[] {7, IN, IN}, new int[] {7, STRIDE, STRIDE}),
                new InfiniteTensor[0],
                new TensorWindow[0],
                104857600L);
    }

    private static int drain(InfiniteTensor t, int[] start, int[] end, int bound) {
        int computed = 0;
        while (computed < bound) {
            int status = t.prepareIndependentWindowStep(start, end);
            assertTrue(status >= 0, "status must be 0 or 1, got " + status);
            if (status == 0) return computed;
            computed++;
        }
        fail("slice did not complete within " + bound + " window steps");
        return -1;
    }

    @Test
    void sliceReportsOnePerWindowThenComplete() {
        var store = new MemoryTileStore();
        var t = tensor(store, "aligned", 1L);
        int[] start = {0, 0, 0}, end = {7, 32, 128};
        long before = store.getTotalComputedWindowCount();
        // x windows: lo=-1..hi=floorDiv(127,32)=3 (5), y windows: -1..0 (2) -> 10 windows.
        assertEquals(10, drain(t, start, end, 64), "window count for [0,128)");
        assertEquals(0, t.prepareIndependentWindowStep(start, end), "idempotent once complete");
        assertTrue(t.prepareIndependentSliceStep(start, end), "legacy boolean sees the completed slice");
        assertEquals(10, store.getTotalComputedWindowCount() - before, "exactly one computation per window");
    }

    @Test
    void oddRemainderSliceComputesEveryWindowOnce() {
        var store = new MemoryTileStore();
        var t = tensor(store, "remainder", 2L);
        int[] start = {0, 0, 0}, end = {7, 32, 160};
        // x windows -1..4 (6) * y windows 2 -> 12.
        assertEquals(12, drain(t, start, end, 64));
        assertEquals(0, t.prepareIndependentWindowStep(start, end));
    }

    @Test
    void adjacentSliceSkipsAlreadyCachedWindows() {
        var store = new MemoryTileStore();
        var t = tensor(store, "continue", 4L);
        int[] first = {0, 0, 0}, firstEnd = {7, 32, 128}, second = {0, 0, 128}, secondEnd = {7, 32, 256};
        assertEquals(10, drain(t, first, firstEnd, 64));
        long afterFirst = store.getTotalComputedWindowCount();
        // Second slice: x windows 3..7 (5) * y 2 = 10, of which x=3 (2 windows) are cached -> 8 new.
        assertEquals(8, drain(t, second, secondEnd, 64));
        assertEquals(8, store.getTotalComputedWindowCount() - afterFirst);
        // The first slice stays complete.
        assertEquals(0, t.prepareIndependentWindowStep(first, firstEnd));
    }

    @Test
    void sliceOutputIsFiniteAndCorrectlyShaped() {
        var store = new MemoryTileStore();
        var t = tensor(store, "shape", 5L);
        int[] start = {0, 0, 0}, end = {7, 32, 128};
        drain(t, start, end, 64);
        var slice = t.getSlice(start, end);
        assertArrayEquals(new int[] {7, 32, 128}, slice.shape);
        for (float value : slice.data) assertTrue(Float.isFinite(value), "composed slice must be finite");
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoarseMeshLifecycleTest {
    @Test
    void retirementKeepsCleanupKeysAfterDisablingTheNormalPublisher() {
        var tracker = new CoarseMeshTracker(4);
        long a = 4L << 60, b = (3L << 60) | 7;
        tracker.request(a);
        tracker.built(a);
        tracker.request(b);
        tracker.built(b);
        tracker.request(9);
        tracker.retire();
        assertFalse(tracker.current(a, 1));
        assertEquals(-1, tracker.request(10));
        var keys = tracker.virtualKeys();
        java.util.Arrays.sort(keys);
        long[] expected = {a, b};
        java.util.Arrays.sort(expected);
        assertArrayEquals(
                expected, keys, "Cleanup enumerates built meshes, not the last radius or stopped waiting queue");
    }

    @Test
    void changedOwnershipRejectsAnInFlightSnapshotButNotUnrelatedSections() {
        var tracker = new CoarseMeshTracker(4);
        long version = tracker.request(1);
        tracker.request(2);
        tracker.invalidate(1);
        assertFalse(tracker.current(1, version));
        assertTrue(tracker.current(2, 1));
        assertEquals(2, tracker.request(1));
    }

    @Test
    void testStaleVoxyBakeCannotPublish() {
        var tracker = new CoarseMeshTracker(4);
        long version = tracker.request(1);
        Object snapshot = new Object();
        var provider =
                new CoarseRenderHooks.Provider() {
                    public Object render(Object e, long p, Object o) {
                        return o;
                    }

                    public byte children(Object e, Object s, byte actual) {
                        return actual;
                    }

                    public boolean meshCurrent(long position, long revision) {
                        return tracker.current(position, revision);
                    }
                };
        try {
            CoarseRenderHooks.attach(provider);
            CoarseRenderHooks.tag(snapshot, provider, 1, version);
            assertTrue(CoarseRenderHooks.meshCurrent(snapshot));
            tracker.invalidate(1);
            assertFalse(CoarseRenderHooks.meshCurrent(snapshot));
            CoarseRenderHooks.tag(snapshot, provider, 1, tracker.request(1));
            assertTrue(CoarseRenderHooks.meshCurrent(snapshot));
            Object built = new Object();
            CoarseRenderHooks.copyMeshStamp(snapshot, built);
            CoarseRenderHooks.meshFinished(snapshot);
            tracker.invalidate(1);
            assertFalse(
                    CoarseRenderHooks.meshCurrent(built),
                    "A change after CPU build must still be rejected at result delivery");
            CoarseRenderHooks.meshFinished(built);
            CoarseRenderHooks.tag(snapshot, provider, 1, tracker.request(1));
            tracker.retire();
            CoarseRenderHooks.detach(provider);
            assertFalse(CoarseRenderHooks.meshCurrent(snapshot));
            CoarseRenderHooks.meshFinished(snapshot);
            assertTrue(CoarseRenderHooks.meshCurrent(new Object()), "Stock exact snapshots bypass TD guards");
        } finally {
            CoarseRenderHooks.meshFinished(snapshot);
            CoarseRenderHooks.detach(provider);
        }
    }

    @Test
    void testTrackingIsBoundedWithoutEvictingBuiltCleanupEntries() {
        var tracker = new CoarseMeshTracker(1);
        assertEquals(1, tracker.request(1));
        tracker.built(1);
        assertEquals(-1, tracker.request(2));
        assertArrayEquals(new long[] {1}, tracker.virtualKeys());
        assertTrue(tracker.statistics().contains("residentMeshes=UNKNOWN"));
    }

    @Test
    void testSecondForcedInvalidationCannotBeLost() {
        var queue = new CoarseUpdateQueue(8, 8);
        long key = 4L << 60;
        queue.offer(key, true);
        var selected = queue.select(1, k -> true, k -> true).getFirst();
        queue.offer(key, true);
        assertFalse(
                queue.complete(selected),
                "The selected older invalidation must not remove a newer ownership/correction change");
        var latest = queue.select(1, k -> true, k -> true).getFirst();
        assertNotSame(selected, latest);
        assertTrue(queue.complete(latest));
    }

    @Test
    void repeatedRepairKeepsUrgentPriorityWhenItsNewEntryCannotFit() {
        var queue = new CoarseUpdateQueue(8, 1);
        long repair = 16, root = 4L << 60;
        queue.offer(repair, true);
        queue.offer(repair, true);
        queue.offer(root, false);
        var selected = queue.select(1, k -> true, k -> true).getFirst();
        assertEquals(
                repair,
                selected.key(),
                "The old urgent entry must resolve to the current repair, ahead of distant roots");
        assertTrue(queue.complete(selected));
        assertEquals(1, queue.size());
    }
}

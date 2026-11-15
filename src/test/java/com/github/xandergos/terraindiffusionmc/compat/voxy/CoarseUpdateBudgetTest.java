package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 5.3 adaptive publication budget. The confirmed backlog mechanism was the old
 * step collapse (pressure 3/4 -> 4/1 tasks) with an empty Voxy mesh queue: 48k waiting
 * sections drained at ~80/s or froze entirely. The adaptive budget keeps a floor while
 * the mesh queue has headroom, follows meshQueue caps, and recovers additively. */
class CoarseUpdateBudgetTest {
    @Test
    void severePressureWithFreeMeshQueueKeepsADrainFloor() {
        var budget = new CoarseUpdateBudget();
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < 64; i++) min = Math.min(min, budget.quota(4, 0));
        assertTrue(min >= 2, "pressure 4 must not collapse below the floor with meshQueue 0");
        assertTrue(budget.allowance() >= 2 && budget.allowance() <= 64);
    }

    @Test
    void pressure4NoLongerCollapsesTo1LikeTheOldStepFunction() {
        var budget = new CoarseUpdateBudget();
        for (int i = 0; i < 8; i++) budget.quota(4, 0); // old behavior: 1
        assertTrue(budget.allowance() >= 2, "adaptive floor must beat the old step collapse");
    }

    @Test
    void meshQueueCapsAreUnchanged() {
        var budget = new CoarseUpdateBudget();
        assertEquals(0, budget.quota(0, 512));
        assertEquals(0, budget.quota(4, 600));
        assertTrue(budget.quota(0, 256) <= 4);
        assertTrue(budget.quota(0, 128) <= 16);
        assertTrue(budget.quota(0, -1) <= 16);
    }

    @Test
    void growthIsAdditiveAndRecoveryReachesTheTarget() {
        var budget = new CoarseUpdateBudget();
        // Start from a collapsed state, then give cheap frames with a free mesh queue.
        for (int i = 0; i < 64; i++) budget.quota(4, 0);
        int low = budget.allowance();
        for (int i = 0; i < 40; i++) budget.quota(0, 0);
        assertTrue(budget.allowance() > low, "budget must recover additively after pressure ends");
        assertTrue(budget.allowance() <= 64);
    }

    @Test
    void spikeHalvesQuicklyWithoutOscillation() {
        var budget = new CoarseUpdateBudget();
        for (int i = 0; i < 64; i++) budget.quota(0, 0); // target 64
        int high = budget.allowance();
        budget.quota(4, 0); // one severe-pressure cycle
        assertTrue(budget.allowance() < high, "a spike must cut the budget immediately");
        // Repeated identical cycles converge to the pressure-4 target (64/8=8) and stay.
        for (int i = 0; i < 8; i++) budget.quota(4, 0);
        int converged = budget.allowance();
        assertEquals(8, converged, "target under pressure 4 with a free mesh queue");
        for (int i = 0; i < 16; i++) budget.quota(4, 0);
        assertEquals(converged, budget.allowance(), "converged state must stay stable");
    }

    @Test
    void sustainedBacklogDrainsAtAFloorBoundedRate() {
        // 48k sections at floor 2 per ~50 ms cycle still drains in ~20 minutes; the old
        // step function could stop entirely. The floor guarantees forward progress.
        var budget = new CoarseUpdateBudget();
        long published = 0;
        for (int i = 0; i < 200; i++) published += budget.quota(3, 0);
        assertTrue(published >= 200, "pressure 3 must keep publishing at least the floor per cycle");
    }
}

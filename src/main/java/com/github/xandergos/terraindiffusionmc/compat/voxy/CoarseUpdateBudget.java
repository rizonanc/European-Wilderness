package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Phase 5.3 adaptive publication budget (AIMD-lite). Replaces the collapsing step
 * function that pinned publication at 1/4 tasks under sustained moderate frame
 * pressure while the actual downstream (Voxy mesh queue) had headroom — the confirmed
 * mechanism behind the 48k-section waiting backlog.
 *
 * Control loop: the Voxy mesh queue is the primary pressure signal. Frame pressure
 * modulates the target but, while the mesh queue has real headroom, publication never
 * collapses below a small floor. Growth is additive (one step per controller cycle,
 * ~50 ms), decrease is multiplicative (halving towards the target), so a spike cuts
 * the rate quickly without the sustained starvation of a step collapse. The meshQueue
 * hard caps (0 at 512+, tight at 256+) are unchanged. */
public final class CoarseUpdateBudget {
    private int allowance = 16;
    private long adjustments;

    /** One controller cycle (~50 ms). Returns the publication quota for this cycle.
     * Phase 5.4.1: the hard mesh-queue caps apply to the ISSUED value immediately —
     * a warm budget falling onto a capped queue can no longer emit an oversized batch
     * on its first cycle. */
    public synchronized int quota(int pressure, int meshQueue) {
        adjustments++;
        if (meshQueue >= 512) {
            allowance = Math.max(1, allowance / 4);
            return 0;
        }
        int clamped = Math.max(0, Math.min(4, pressure));
        int base = meshQueue >= 256 ? 4 : meshQueue >= 128 ? 16 : 64;
        int factor = clamped >= 4 ? 8 : clamped == 3 ? 4 : clamped == 2 ? 2 : 1;
        // While the mesh queue has real headroom, keep a small floor so the backlog
        // drains even under sustained frame pressure; severe pressure only slows it.
        int floor = clamped >= 4 ? 2 : 1;
        int target = Math.max(floor, base / factor);
        int cap = meshQueue >= 256 ? 4 : meshQueue >= 128 ? 16 : meshQueue < 0 ? 16 : 64;
        allowance =
                allowance < target
                        ? Math.min(target, allowance + 1)
                        : allowance > target ? Math.max(target, allowance / 2) : allowance;
        return Math.min(allowance, cap);
    }

    synchronized int allowance() {
        return allowance;
    }

    synchronized long adjustments() {
        return adjustments;
    }
}

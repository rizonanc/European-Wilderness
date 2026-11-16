package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Adjust coarse publication to frame pressure and Voxy mesh capacity. */
public final class CoarseUpdateBudget {
    private int allowance = 16;
    private long adjustments;

    /** Apply the mesh queue cap to the current batch. */
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
        allowance = allowance < target
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

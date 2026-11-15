package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Coarse ownership requires stored voxel data. */
public final class CoarseOwnership {
    public enum Decision {
        OPEN,
        EXACT_STORED
    }

    private CoarseOwnership() {}

    public static Decision decide(boolean storedKnownContains) {
        return storedKnownContains ? Decision.EXACT_STORED : Decision.OPEN;
    }

    public static boolean exact(Decision decision) {
        return decision == Decision.EXACT_STORED;
    }
}

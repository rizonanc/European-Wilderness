package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Phase 5.4.1 pure ownership decision for the renderer-only coarse composer.
 *
 * A column suppresses the neural preview ONLY when actual stored Voxy data exists for
 * it: the 512-block stored-column probe (per-column bits over the full vertical range)
 * is the single data-backed signal. Ownership evidence without stored data — a loaded
 * but not-yet-ingested chunk, a coverage mark, a saved Anvil region with no Voxy
 * representation, an unread/uncertain region header — used to produce "protected air":
 * a vertical wall against neighbouring neural columns. Those inputs no longer make a
 * column exact; the neural preview fills the gap until stored data arrives, then the
 * region probe is refreshed (invalidate on chunk load), the column is protected, and
 * the preview is invalidated.
 *
 * Loaded real chunks are additionally preserved directly from their in-memory section
 * (preserveOriginal in CoarseSectionComposer): voxels AND air stay real even before
 * the ingest, without filling virtual stone into real air. */
public final class CoarseOwnership {
    public enum Decision { OPEN, EXACT_STORED }
    private CoarseOwnership() {}
    public static Decision decide(boolean storedKnownContains) {
        return storedKnownContains?Decision.EXACT_STORED:Decision.OPEN;
    }
    public static boolean exact(Decision decision){return decision==Decision.EXACT_STORED;}
}

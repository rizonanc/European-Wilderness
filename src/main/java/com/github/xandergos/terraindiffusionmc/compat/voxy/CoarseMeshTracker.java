package com.github.xandergos.terraindiffusionmc.compat.voxy;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Bounded CPU provenance, not GPU residency. Entries live until provider retirement. */
final class CoarseMeshTracker {
    private static final class Entry {
        long revision = 1;
        boolean data, virtual, notified;
    }

    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
    private final int limit;
    private boolean retired, limited;
    private long stale;

    CoarseMeshTracker(int limit) {
        if (limit < 1) throw new IllegalArgumentException();
        this.limit = limit;
    }

    synchronized long request(long key) {
        if (retired) return -1;
        var e = entries.get(key);
        if (e == null) {
            if (entries.size() >= limit) {
                limited = true;
                return -1;
            }
            e = new Entry();
            entries.put(key, e);
        }
        return e.revision;
    }

    synchronized void built(long key) {
        var e = entries.get(key);
        if (e != null) {
            e.data = true;
            e.virtual = true;
        }
    }

    synchronized void notified(long key) {
        var e = entries.get(key);
        if (e != null) e.notified = true;
    }

    synchronized void invalidate(long key) {
        var e = entries.get(key);
        if (e != null) e.revision++;
    }

    synchronized boolean current(long key, long revision) {
        var e = entries.get(key);
        return !retired && e != null && e.revision == revision;
    }

    synchronized void stale() {
        stale++;
    }

    synchronized void retire() {
        retired = true;
    }

    synchronized long[] virtualKeys() {
        return entries.long2ObjectEntrySet().stream()
                .filter(e -> e.getValue().virtual)
                .mapToLong(e -> e.getLongKey())
                .toArray();
    }

    synchronized String describe(long key) {
        var e = entries.get(key);
        return e == null
                ? "UNKNOWN"
                : "cpuRevision="
                        + e.revision
                        + ", dataReady="
                        + e.data
                        + ", virtualBuilt="
                        + e.virtual
                        + ", invalidationQueued="
                        + e.notified
                        + ", retired="
                        + retired;
    }

    synchronized String statistics() {
        int[] requested = new int[5], data = new int[5], built = new int[5], notified = new int[5];
        for (var item : entries.long2ObjectEntrySet()) {
            int l = (int) (item.getLongKey() >>> 60);
            if (l > 4) continue;
            var e = item.getValue();
            requested[l]++;
            if (e.data) data[l]++;
            if (e.virtual) built[l]++;
            if (e.notified) notified[l]++;
        }
        return "uniqueCPU={requested="
                + java.util.Arrays.toString(requested)
                + ", dataReady="
                + java.util.Arrays.toString(data)
                + ", virtualBuilt="
                + java.util.Arrays.toString(built)
                + ", notified="
                + java.util.Arrays.toString(notified)
                + ", staleRejected="
                + stale
                + ", limited="
                + limited
                + "}; residentMeshes=UNKNOWN visibleCoverage=UNKNOWN";
    }
}

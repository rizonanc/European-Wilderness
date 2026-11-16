package com.github.xandergos.terraindiffusionmc.worldgen.cache;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Process-local accounting. Files, never a persisted counter, are authoritative on reopen. */
final class DiskQuota {
    private static final Map<Path, WeakReference<DiskQuota>> OPEN = new WeakHashMap<>();

    static synchronized DiskQuota open(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        var reference = OPEN.get(root);
        var quota = reference == null ? null : reference.get();
        if (quota == null) {
            quota = new DiskQuota(root);
            OPEN.put(root, new WeakReference<>(quota));
        }
        // A new store may follow external maintenance while the world was closed.
        quota.invalidate();
        return quota;
    }

    final Path root;
    private final Map<Path, Long> sizes = new HashMap<>();
    private final Set<Path> writing = new HashSet<>();
    private boolean initialized;
    private long used, reserved, scans, sizeQueries;

    private DiskQuota(Path root) {
        this.root = root;
    }

    synchronized void invalidate() {
        initialized = false;
    }

    synchronized long usedBytes() throws IOException {
        if (!initialized) reconcile();
        return used;
    }

    synchronized long reconcile() throws IOException {
        // Build privately: a failed scan must never publish an underestimate.
        var found = new HashMap<Path, Long>();
        long bytes = 0;
        initialized = false;
        scans++;
        boolean present;
        try {
            Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes.class);
            present = true;
        } catch (NoSuchFileException absent) {
            present = false;
        }
        if (present) {
            try (var files = Files.list(root)) {
                for (var iterator = files.iterator(); iterator.hasNext(); ) {
                    Path file = iterator.next();
                    String name = file.getFileName().toString();
                    if ((!name.endsWith(".ewt") && !name.contains(".ewt.tmp-")) || writing.contains(file)) continue;
                    long size = size(file);
                    found.put(file, size);
                    bytes = Math.addExact(bytes, size);
                }
            }
        }
        sizes.clear();
        sizes.putAll(found);
        used = bytes;
        initialized = true;
        return used;
    }

    private long size(Path file) throws IOException {
        sizeQueries++;
        try {
            return Files.size(file);
        } catch (NoSuchFileException removed) {
            return 0;
        }
    }

    synchronized DiskTerrainStore.Reservation reserve(long bytes, long limit) throws IOException {
        if (bytes < 0 || limit <= 0) throw new IllegalArgumentException("Invalid disk reservation");
        long committed = usedBytes();
        if (committed > limit || reserved > limit - committed || bytes > limit - committed - reserved) return null;
        reserved += bytes;
        return new DiskTerrainStore.Reservation(this, bytes);
    }

    synchronized void release(DiskTerrainStore.Reservation reservation) {
        if (!reservation.closed) {
            reserved -= reservation.bytes;
            reservation.closed = true;
        }
    }

    synchronized void beginWrite(DiskTerrainStore.Reservation reservation, Path temporary, long bytes)
            throws IOException {
        if (reservation.quota != this || reservation.closed || reservation.bytes < bytes)
            throw new IllegalArgumentException("Foreign, closed or undersized disk reservation");
        usedBytes();
        writing.add(temporary);
    }

    // Called under this monitor around the filesystem mutation, never around encoding/inference.
    void refresh(Path file) throws IOException {
        setSize(file, size(file));
    }

    void setSize(Path file, long bytes) {
        Long previous = bytes == 0 ? sizes.remove(file) : sizes.put(file, bytes);
        used = Math.addExact(used - (previous == null ? 0 : previous), bytes);
    }

    void finishWrite(Path temporary) throws IOException {
        writing.remove(temporary);
        // Includes a failed-to-delete partial write in subsequent quota checks.
        refresh(temporary);
    }

    synchronized DiskTerrainStore.QuotaStatistics statistics() {
        return new DiskTerrainStore.QuotaStatistics(used, reserved, scans, sizeQueries, initialized);
    }
}

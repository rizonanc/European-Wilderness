package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.*;

/** Bounded scan and remembered results; old locations cannot grow memory forever. */
public final class LodScan {
    public static final int MAX_RADIUS_BLOCKS = 32768;

    public record Cell(int x, int z) {}

    private final LinkedHashMap<Cell, Boolean> done = new LinkedHashMap<>(256, .75F, true);
    private final LinkedHashMap<Cell, LodPublisher.Progress> pending = new LinkedHashMap<>();
    private Cell center;
    private int radius, ring, position;
    private int deferredRing = Integer.MAX_VALUE;
    private int fresh;
    private boolean exhausted, detailsExhausted;

    public void move(int x, int z, int blocks) {
        if (blocks < 64 || blocks > MAX_RADIUS_BLOCKS)
            throw new IllegalArgumentException("LOD radius must be 64..32768 blocks");
        var next = new Cell(Math.floorDiv(x, 32), Math.floorDiv(z, 32));
        if (next.equals(center) && radius == blocks) return;
        center = next;
        ring = position = fresh = 0;
        deferredRing = Integer.MAX_VALUE;
        exhausted = detailsExhausted = false;
        radius = blocks;
    }

    public Cell next() {
        if (fresh >= 4) {
            var detail = nextDetail();
            if (detail != null) {
                fresh = 0;
                return detail;
            }
        }
        if (center == null) return null;
        if (!exhausted)
            for (int i = 0; i < 512; i++) {
                var cell = nextCell();
                if (cell == null) {
                    exhausted = true;
                    break;
                }
                if (Math.abs((long) cell.x * 32) < 29_990_000
                        && Math.abs((long) cell.z * 32) < 29_990_000
                        && !done.containsKey(cell)
                        && !pending.containsKey(cell)) {
                    fresh++;
                    return cell;
                }
            }
        return nextDetail();
    }

    private Cell nextCell() {
        int n = (radius + 31) / 32;
        while (ring <= n) {
            if (ring == 0) {
                ring = 1;
                return center;
            }
            int r = ring, side = position / (2 * r), along = position % (2 * r), dx, dz;
            switch (side) {
                case 0 -> {
                    dx = -r + along;
                    dz = -r;
                }
                case 1 -> {
                    dx = r;
                    dz = -r + along;
                }
                case 2 -> {
                    dx = r - along;
                    dz = r;
                }
                default -> {
                    dx = -r;
                    dz = r - along;
                }
            }
            if (++position == 8 * r) {
                ring++;
                position = 0;
            }
            if ((long) dx * dx + (long) dz * dz <= (long) n * n) return new Cell(center.x + dx, center.z + dz);
        }
        return null;
    }

    private Cell nextDetail() {
        if (detailsExhausted || center == null) return null;
        long n = (radius + 31) / 32;
        for (var cell : pending.keySet()) {
            long dx = (long) cell.x - center.x, dz = (long) cell.z - center.z;
            if (dx * dx + dz * dz <= n * n) return cell;
        }
        detailsExhausted = true;
        return null;
    }

    public LodPublisher.Progress progress(Cell cell) {
        return pending.get(cell);
    }

    public void surfaceCompleted(Cell cell, LodPublisher.Progress progress) {
        pending.remove(cell);
        pending.put(cell, progress);
        detailsExhausted = false;
        trim();
    }

    public void defer(Cell cell) {
        if (center != null)
            deferredRing = Math.min(deferredRing, Math.max(Math.abs(cell.x - center.x), Math.abs(cell.z - center.z)));
    }

    public void reconsider() {
        if (deferredRing != Integer.MAX_VALUE) {
            ring = Math.min(ring, deferredRing);
            position = 0;
            exhausted = false;
            deferredRing = Integer.MAX_VALUE;
        }
        detailsExhausted = false;
    }

    public void completed(Cell cell) {
        pending.remove(cell);
        done.put(cell, true);
        trim();
    }

    private void trim() {
        while (done.size() + pending.size() > 16384) {
            if (!done.isEmpty()) done.remove(done.keySet().iterator().next());
            else pending.remove(pending.keySet().iterator().next());
        }
    }

    public int remembered() {
        return done.size() + pending.size();
    }

    public int pendingDetails() {
        return pending.size();
    }

    public int pendingForests() {
        int count = 0;
        for (var progress : pending.values()) if (progress.forestPending()) count++;
        return count;
    }
}

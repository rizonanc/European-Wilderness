package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import java.util.*;
import java.util.function.LongSupplier;

/** Fixed-size timeline, including work still running when the frame spike is observed. */
public final class WorkTimeline {
    public enum Stage {
        INFERENCE_QUEUE,
        INFERENCE_EXECUTE,
        WATER_IO_READ,
        WATER_IO_WRITE,
        WATER_PLAN,
        WATER_BANKS,
        WATER_HEIGHT,
        WATER_FOREGROUND_WAIT,
        RAW_IO_READ,
        RAW_IO_WRITE,
        LOD_SAMPLE,
        LOD_CLIENT_WAIT,
        LOD_CUBE,
        LOD_INSERT,
        TREE_DECORATION
    }

    public record Event(
            Stage stage, String thread, long startNanos, long endNanos, boolean active, long overlapNanos) {}

    private final LongSupplier clock;
    private final long[] ids, starts, ends;
    private final Stage[] stages;
    private final String[] threads;
    private final boolean[] running;
    private long sequence, dropped;
    private int nextSlot;
    private volatile boolean enabled;

    public WorkTimeline(int capacity, LongSupplier clock) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.clock = clock;
        ids = new long[capacity];
        starts = new long[capacity];
        ends = new long[capacity];
        running = new boolean[capacity];
        stages = new Stage[capacity];
        threads = new String[capacity];
    }

    public synchronized void enabled(boolean value) {
        if (value == enabled) return;
        enabled = value;
        Arrays.fill(ids, 0);
        Arrays.fill(running, false);
        Arrays.fill(stages, null);
        Arrays.fill(threads, null);
    }

    public boolean enabled() {
        return enabled;
    }

    public long begin(Stage stage) {
        if (!enabled) return 0;
        synchronized (this) {
            if (!enabled) return 0;
            int slot = reserve();
            if (slot < 0) return 0;
            long id = ++sequence * ids.length + slot + 1;
            ids[slot] = id;
            starts[slot] = clock.getAsLong();
            running[slot] = true;
            stages[slot] = stage;
            threads[slot] = Thread.currentThread().getName();
            return id;
        }
    }

    public void end(long token) {
        if (token == 0 || !enabled) return;
        synchronized (this) {
            int slot = (int) ((token - 1) % ids.length);
            if (ids[slot] == token) {
                ends[slot] = clock.getAsLong();
                running[slot] = false;
            }
        }
    }

    public void record(Stage stage, long start, long end) {
        if (!enabled) return;
        synchronized (this) {
            if (!enabled) return;
            int slot = reserve();
            if (slot < 0) return;
            long id = ++sequence * ids.length + slot + 1;
            ids[slot] = id;
            starts[slot] = start;
            ends[slot] = Math.max(start, end);
            running[slot] = false;
            stages[slot] = stage;
            threads[slot] = Thread.currentThread().getName();
        }
    }

    private int reserve() {
        for (int i = 0; i < ids.length; i++) {
            int slot = (nextSlot + i) % ids.length;
            if (!running[slot]) {
                nextSlot = (slot + 1) % ids.length;
                return slot;
            }
        }
        dropped++;
        return -1;
    }

    public synchronized List<Event> overlap(long from, long to) {
        if (to < from) throw new IllegalArgumentException("window");
        List<Event> result = new ArrayList<>();
        for (int i = 0; i < ids.length; i++)
            if (ids[i] != 0) {
                long end = running[i] ? to : ends[i], overlap = Math.min(end, to) - Math.max(starts[i], from);
                if (overlap > 0) result.add(new Event(stages[i], threads[i], starts[i], end, running[i], overlap));
            }
        result.sort(Comparator.comparingLong(Event::startNanos));
        return List.copyOf(result);
    }

    public int capacity() {
        return ids.length;
    }

    public synchronized long dropped() {
        return dropped;
    }
}

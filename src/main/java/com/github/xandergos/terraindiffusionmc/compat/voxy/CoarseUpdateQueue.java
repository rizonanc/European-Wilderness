package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongPredicate;

/** Deduplicated bounded work. Roots first; independent level iterators cannot starve roots
 * behind fine neighbours. Selection is worker-only; render workers just defer/promote. */
public final class CoarseUpdateQueue {
    public static final class Task {
        private final long key;
        private final boolean forced;
        private final long since;

        private Task(long key, boolean forced) {
            this(key, forced, System.nanoTime());
        }

        private Task(long key, boolean forced, long since) {
            this.key = key;
            this.forced = forced;
            this.since = since;
        }

        public long key() {
            return key;
        }

        public boolean forced() {
            return forced;
        }
    }

    private final int limit;
    private final AtomicInteger size = new AtomicInteger();
    private final List<ConcurrentHashMap<Long, Task>> levels = new ArrayList<>(5);
    private final List<Iterator<Task>> cursors = new ArrayList<>(5);
    private final ArrayBlockingQueue<Task> urgent;

    public CoarseUpdateQueue(int limit, int urgentLimit) {
        if (limit < 1 || urgentLimit < 1) throw new IllegalArgumentException();
        this.limit = limit;
        urgent = new ArrayBlockingQueue<>(urgentLimit);
        for (int i = 0; i < 5; i++) {
            levels.add(new ConcurrentHashMap<>());
            cursors.add(Collections.emptyIterator());
        }
    }

    private static int level(long key) {
        int level = (int) ((key >>> 60) & 15);
        if (level > 4) throw new IllegalArgumentException("LOD level");
        return level;
    }

    public int size() {
        return size.get();
    }

    public int levelSize(int level) {
        return levels.get(level).size();
    }

    public boolean offer(long key, boolean forced) {
        var map = levels.get(level(key));
        while (true) {
            var existing = map.get(key);
            if (existing != null) {
                if (forced) {
                    var promoted = new Task(key, true, existing.since);
                    if (!map.replace(key, existing, promoted)) continue;
                    urgent.offer(promoted);
                }
                return true;
            }
            if (size.incrementAndGet() > limit) {
                size.decrementAndGet();
                return false;
            }
            var added = new Task(key, forced);
            existing = map.putIfAbsent(key, added);
            if (existing != null) {
                size.decrementAndGet();
                continue;
            }
            if (forced) urgent.offer(added);
            return true;
        }
    }

    /** Remove only the selected revision. A concurrently promoted cleanup stays queued. */
    public boolean complete(Task task) {
        if (levels.get(level(task.key())).remove(task.key(), task)) {
            size.decrementAndGet();
            return true;
        }
        return false;
    }

    private boolean current(Task task) {
        return levels.get(level(task.key())).get(task.key()) == task;
    }

    public void retryUrgent(List<Task> selected) {
        for (var task : selected) if (task.forced() && current(task)) urgent.offer(task);
    }

    public List<Task> select(int quota, LongPredicate within, LongPredicate ready) {
        if (quota <= 0) return List.of();
        var selected = new ArrayList<Task>(quota);
        int urgentQuota = Math.max(1, quota / 2);
        for (int i = 0; i < quota * 2 && selected.size() < urgentQuota; i++) {
            var queued = urgent.poll();
            if (queued == null) break;
            // Repeated invalidation may replace a task while the bounded urgent lane is full.
            var task = levels.get(level(queued.key())).get(queued.key());
            if (task == null || !task.forced()) continue;
            if (!within.test(task.key())) {
                complete(task);
                continue;
            }
            if (!selected.contains(task)) selected.add(task);
        }
        for (int level = 4; level >= 0 && selected.size() < quota; level--) {
            var cursor = cursors.get(level);
            if (!cursor.hasNext()) cursor = levels.get(level).values().iterator();
            for (int checked = 0; checked < 512 && cursor.hasNext() && selected.size() < quota; checked++) {
                var task = cursor.next();
                if (!current(task)) continue;
                if (!within.test(task.key())) {
                    complete(task);
                    continue;
                }
                if (!selected.contains(task) && (task.forced() || ready.test(task.key()))) selected.add(task);
            }
            cursors.set(level, cursor);
        }
        return selected;
    }

    public String statistics() {
        long[] ages = new long[2048];
        int n = 0;
        long now = System.nanoTime();
        for (var level : levels) {
            int local = 0;
            for (var task : level.values()) {
                if (n == ages.length || local++ >= 409) break;
                ages[n++] = Math.max(0, now - task.since);
            }
        }
        java.util.Arrays.sort(ages, 0, n);
        String age =
                n == 0
                        ? "none"
                        : String.format(
                                java.util.Locale.ROOT,
                                "%.1f/%.1f/%.1fms sample=%d",
                                ages[(n - 1) / 2] / 1e6,
                                ages[(int) ((n - 1) * .95)] / 1e6,
                                ages[(int) ((n - 1) * .99)] / 1e6,
                                n);
        return "uniqueWaiting="
                + size()
                + " pendingByLod="
                + levels.stream().map(Map::size).toList()
                + " waitingAgeSampleP50/95/99="
                + age;
    }
}

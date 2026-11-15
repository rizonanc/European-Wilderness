package com.github.xandergos.terraindiffusionmc.compat.voxy;

import it.unimi.dsi.fastutil.longs.*;
import java.io.IOException;
import java.nio.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/** Read-only protection of real 16x16 chunks. Disk headers are read only by the LOD worker. */
public final class LodChunkGuard {
    private record Region(int x, int z) {}

    private record Header(long readAt, int[] locations, boolean protectAll) {}

    private record SavedColumns(long[] bits, boolean protectAll, long readAt) {
        boolean contains(int index) {
            return protectAll || bits != null && (bits[index >>> 6] & (1L << (index & 63))) != 0;
        }

        boolean ready(long now) {
            return !protectAll || now - readAt < 2_000_000_000L;
        }
    }

    private static final int MAX_RENDER_REGIONS = 32768, MAX_RENDER_REQUESTS = 8192;
    private final Path regionDirectory;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Region, AtomicLongArray> seen = new ConcurrentHashMap<>();
    private volatile Long2ObjectMap<AtomicLongArray> renderSeen = Long2ObjectMaps.emptyMap();
    private volatile Long2ObjectMap<SavedColumns> renderSaved = Long2ObjectMaps.emptyMap();
    private final Set<Long> renderPending = ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<Long> renderRequests = new ArrayBlockingQueue<>(MAX_RENDER_REQUESTS);
    private final LinkedHashMap<Region, Header> headers = new LinkedHashMap<>(128, .75F, true);
    private volatile boolean overflow;
    private boolean uncertain;
    private final AtomicLong failures = new AtomicLong();

    public LodChunkGuard(Path regionDirectory) {
        this(regionDirectory, System::nanoTime);
    }

    LodChunkGuard(Path regionDirectory, LongSupplier clock) {
        this.regionDirectory = regionDirectory;
        this.clock = clock;
    }

    public synchronized void markLoaded(int chunkX, int chunkZ) {
        var region = new Region(Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
        var bits = seen.get(region);
        if (bits == null) {
            if (seen.size() >= 4096) {
                overflow = true;
                return;
            }
            bits = seen.computeIfAbsent(region, r -> new AtomicLongArray(16));
            var copy = new Long2ObjectOpenHashMap<>(renderSeen);
            copy.put(((long) region.x() << 32) | (region.z() & 0xffffffffL), bits);
            renderSeen = copy;
        }
        int index = Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32), word = index >>> 6;
        long mask = 1L << (index & 63);
        long previous;
        do {
            previous = bits.get(word);
            if ((previous & mask) != 0) return;
        } while (!bits.compareAndSet(word, previous, previous | mask));
    }

    private boolean wasSeen(int chunkX, int chunkZ) {
        var bits = seen.get(new Region(Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32)));
        if (bits == null) return false;
        int index = Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32);
        return (bits.get(index >>> 6) & (1L << (index & 63))) != 0;
    }

    /** No IO; may be used to recheck the mask on the client thread. */
    public int unseenMask(int cellX, int cellZ) {
        if (overflow) return 0;
        int mask = 0;
        for (int z = 0; z < 2; z++)
            for (int x = 0; x < 2; x++) if (!wasSeen(cellX * 2 + x, cellZ * 2 + z)) mask |= 1 << (z * 2 + x);
        return mask;
    }

    /** Worker-only: a nonzero Anvil location entry protects its entire real chunk. */
    public int writableMask(int cellX, int cellZ) {
        uncertain = false;
        int mask = unseenMask(cellX, cellZ);
        if (mask == 0) return 0;
        int chunkX = cellX * 2, chunkZ = cellZ * 2;
        var region = new Region(Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
        var header = header(region);
        if (header.protectAll()) {
            uncertain = true;
            return 0;
        }
        for (int z = 0; z < 2; z++)
            for (int x = 0; x < 2; x++) {
                int index = Math.floorMod(chunkZ + z, 32) * 32 + Math.floorMod(chunkX + x, 32);
                if (header.locations() != null && header.locations()[index] != 0) mask &= ~(1 << (z * 2 + x));
            }
        return mask & unseenMask(cellX, cellZ);
    }

    /** Both background workers share the small disk cache; render reads never enter this lock. */
    private Header header(Region region) {
        // Never use the markLoaded monitor: a slow disk cannot hold up the client thread.
        synchronized (headers) {
            long now = clock.getAsLong();
            var header = headers.get(region);
            if (header == null || now - header.readAt() >= 2_000_000_000L) {
                header = read(region, now);
                headers.put(region, header);
                while (headers.size() > 128)
                    headers.remove(headers.keySet().iterator().next());
            }
            return header;
        }
    }

    private static long regionKey(int blockX, int blockZ) {
        return ((long) Math.floorDiv(blockX, 512) << 32) | (Math.floorDiv(blockZ, 512) & 0xffffffffL);
    }

    /** Nonblocking request only. A Voxy section at levels 0..4 fits one aligned Anvil region. */
    public boolean renderRegionReady(int blockX, int blockZ) {
        long key = regionKey(blockX, blockZ);
        var snapshot = renderSaved;
        var saved = snapshot.get(key);
        if (saved != null && saved.ready(clock.getAsLong()) || saved == null && snapshot.size() >= MAX_RENDER_REGIONS)
            return true;
        if (renderPending.size() < MAX_RENDER_REQUESTS && renderPending.add(key) && !renderRequests.offer(key))
            renderPending.remove(key);
        return false;
    }

    /** Coarse worker only. Publish one immutable primitive-key map per bounded batch. */
    public int prepareRenderRegions(int limit) {
        Long2ObjectOpenHashMap<SavedColumns> next = null;
        int processed = 0;
        for (int checked = 0; checked < limit; checked++) {
            var key = renderRequests.poll();
            if (key == null) break;
            try {
                var saved = (next == null ? renderSaved : next).get(key.longValue());
                if (saved != null && saved.ready(clock.getAsLong())) continue;
                if (next == null) next = new Long2ObjectOpenHashMap<>(renderSaved);
                if (saved == null && next.size() >= MAX_RENDER_REGIONS) continue;
                var header = header(new Region((int) (key >> 32), (int) (long) key));
                long[] bits = null;
                if (header.locations() != null) {
                    bits = new long[16];
                    for (int i = 0; i < 1024; i++) if (header.locations()[i] != 0) bits[i >>> 6] |= 1L << (i & 63);
                }
                next.put(key.longValue(), new SavedColumns(bits, header.protectAll(), header.readAt()));
                processed++;
            } finally {
                renderPending.remove(key);
            }
        }
        if (next != null) renderSaved = next;
        return processed;
    }

    /** Unknown/unreadable ownership fails closed, including real air above a saved chunk. */
    public boolean savedRealColumn(int blockX, int blockZ) {
        var saved = renderSaved.get(regionKey(blockX, blockZ));
        if (saved == null) return true;
        int index = Math.floorMod(Math.floorDiv(blockZ, 16), 32) * 32 + Math.floorMod(Math.floorDiv(blockX, 16), 32);
        return saved.contains(index);
    }

    /** A disk chunk is write-protected, but may have no Voxy representation yet. */
    public boolean savedRenderRegion(int blockX, int blockZ) {
        var saved = renderSaved.get(regionKey(blockX, blockZ));
        return saved == null || saved.protectAll || saved.bits != null;
    }

    public boolean uncertainRenderRegion(int blockX, int blockZ) {
        var saved = renderSaved.get(regionKey(blockX, blockZ));
        return saved == null || saved.protectAll;
    }

    public String renderStatistics() {
        return "ownership={regions="
                + renderSaved.size()
                + ", pending="
                + renderPending.size()
                + ", limit="
                + (renderSaved.size() >= MAX_RENDER_REGIONS)
                + "}";
    }

    private Header read(Region region, long now) {
        Path file = regionDirectory.resolve("r." + region.x() + "." + region.z() + ".mca");
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile() || attributes.size() < 8192) throw new IOException("Invalid region header");
            var data = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                while (data.hasRemaining())
                    if (channel.read(data) < 0) throw new IOException("Truncated region header");
            }
            data.flip();
            int[] locations = new int[1024];
            data.asIntBuffer().get(locations);
            return new Header(now, locations, false);
        } catch (NoSuchFileException absent) {
            return new Header(now, null, false);
        } catch (IOException | SecurityException failure) {
            failures.incrementAndGet();
            return new Header(now, null, true);
        }
    }

    public long failures() {
        return failures.get();
    }

    public boolean overflow() {
        return overflow;
    }

    /** Render-thread safe, no filesystem access. Real air must beat a coarse preview too. */
    public boolean knownRealColumn(int blockX, int blockZ) {
        if (overflow) return true;
        int x = Math.floorDiv(blockX, 16), z = Math.floorDiv(blockZ, 16);
        var bits = renderSeen.get(((long) Math.floorDiv(x, 32) << 32) | (Math.floorDiv(z, 32) & 0xffffffffL));
        if (bits == null) return false;
        int index = Math.floorMod(z, 32) * 32 + Math.floorMod(x, 32);
        return (bits.get(index >>> 6) & (1L << (index & 63))) != 0;
    }

    public boolean uncertain() {
        return uncertain;
    }

    public static boolean includes(int mask, int bx, int bz) {
        if (bx < 0 || bx > 1 || bz < 0 || bz > 1) throw new IllegalArgumentException("Chunk quadrant");
        return (mask & (1 << (bz * 2 + bx))) != 0;
    }
}

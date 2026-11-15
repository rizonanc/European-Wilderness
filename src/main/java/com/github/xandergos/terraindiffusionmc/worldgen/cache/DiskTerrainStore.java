package com.github.xandergos.terraindiffusionmc.worldgen.cache;

import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.WorkTimeline.Stage.*;

import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.GenerationMetrics;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DiskTerrainStore {
    private static final Logger LOG = LoggerFactory.getLogger(DiskTerrainStore.class);
    private static final byte[] MAGIC = new byte[] {69, 87, 84, 49};
    static final int FORMAT_VERSION = 2;
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int MAX_BODY_BYTES = 67108864;
    private static final int MAX_LAYER_VALUES = 4194304;
    private static final byte LAYER_HEIGHTS = 1;
    private static final byte LAYER_BIOMES = 2;
    private static final byte LAYER_CLIMATE = 3;
    private static final byte TYPE_SHORT = 83;
    private static final byte TYPE_FLOAT = 70;
    private final Path root;
    private final DiskQuota quota;
    private final TileReader reader;
    private final TileMover mover;
    private final ConcurrentHashMap<TerrainTileKey, ColdRead> reads = new ConcurrentHashMap<>();
    private static final AtomicInteger READER_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor READERS = createReaders();

    private static ThreadPoolExecutor createReaders() {
        // Only filesystem reads/validation run here. Never inference, chunk work or nested submissions.
        var pool = new ThreadPoolExecutor(
                2,
                2,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                work -> {
                    var thread = new Thread(work, "terrain-cache-read-" + READER_IDS.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                (work, executor) -> {
                    // Backpressure cannot abandon a published shared flight when its submitter is interrupted.
                    boolean interrupted = false;
                    try {
                        while (true)
                            try {
                                executor.getQueue().put(work);
                                return;
                            } catch (InterruptedException retry) {
                                interrupted = true;
                            }
                    } finally {
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    public DiskTerrainStore(Path root) {
        this(root, Files::readAllBytes);
    }

    @FunctionalInterface
    interface TileReader {
        byte[] read(Path file) throws IOException;
    }

    @FunctionalInterface
    interface TileMover {
        Path move(Path source, Path target, java.nio.file.CopyOption... options) throws IOException;
    }

    DiskTerrainStore(Path root, TileReader reader) {
        this(root, reader, Files::move);
    }

    DiskTerrainStore(Path root, TileReader reader, TileMover mover) {
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }

        this.quota = DiskQuota.open(root);
        this.root = this.quota.root;
        this.reader = java.util.Objects.requireNonNull(reader);
        this.mover = java.util.Objects.requireNonNull(mover);
    }

    public Path root() {
        return this.root;
    }

    public Optional<TerrainTile> load(TerrainTileKey key) {
        var candidate = new ColdRead(key);
        var flight = reads.putIfAbsent(key, candidate);
        if (flight == null) {
            flight = candidate;
            try {
                READERS.execute(flight);
            } catch (RuntimeException | Error failure) {
                flight.fail(failure);
            }
        }
        // A caller does not own the physical operation. Cancelling/interrupting it cannot cancel other readers.
        boolean interrupted = false;
        try {
            while (true)
                try {
                    return flight.get();
                } catch (InterruptedException retry) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof Error error) throw error;
                    if (failure.getCause() instanceof RuntimeException error) throw error;
                    throw new IllegalStateException("Disk tile read failed", failure.getCause());
                }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private final class ColdRead extends FutureTask<Optional<TerrainTile>> {
        final TerrainTileKey key;

        ColdRead(TerrainTileKey key) {
            this(key, DiagnosticErrors.context("CACHE_READ", "tile=(" + key.tileX() + "," + key.tileZ() + ")"));
        }

        private ColdRead(TerrainTileKey key, Context context) {
            super(() -> {
                try (var ignored = DiagnosticErrors.scope(context)) {
                    return readValidated(key);
                }
            });
            this.key = key;
        }

        void fail(Throwable failure) {
            setException(failure);
        }

        @Override
        protected void done() {
            reads.remove(key, this);
        }
    }

    private Optional<TerrainTile> readValidated(TerrainTileKey key) {
        long trace = PerformanceDiagnostics.begin(RAW_IO_READ);
        try (var diagnostic = DiagnosticErrors.scope(
                DiagnosticErrors.context("CACHE_READ", "tile=(" + key.tileX() + "," + key.tileZ() + ")"))) {
            Path file = this.root.resolve(key.fileName());

            byte[] raw;
            try {
                raw = reader.read(file);
            } catch (java.nio.file.NoSuchFileException missing) {
                return Optional.empty();
            } catch (IOException failure) {
                emit(
                        Code.IO_READ,
                        current("CACHE_READ"),
                        failure,
                        Outcome.DEGRADED,
                        "Entry unavailable; recomputation required, other entries retained",
                        false);
                return Optional.empty();
            }

            try {
                TerrainTile tile = decode(raw);
                if (!tile.key().equals(key)) {
                    emit(
                            Code.CAC_ENTRY,
                            current("CACHE_VALIDATE"),
                            new IOException("Stored tile identity differs from requested tile"),
                            Outcome.DEGRADED,
                            "Only this entry ignored; recomputation required",
                            false);
                    return Optional.empty();
                } else {
                    return Optional.of(tile);
                }
            } catch (IOException | IllegalArgumentException corrupt) {
                emit(
                        validationCode(corrupt),
                        current("CACHE_VALIDATE"),
                        corrupt,
                        Outcome.DEGRADED,
                        "Only this entry ignored; recomputation required",
                        false);
                return Optional.empty();
            }
        } finally {
            PerformanceDiagnostics.end(trace);
        }
    }

    public void store(TerrainTileKey key, TerrainTile tile) throws IOException {
        store(key, tile, null);
    }

    /** Uses an existing compute reservation; commit and release are one accounting operation. */
    public void store(TerrainTileKey key, TerrainTile tile, Reservation reservation) throws IOException {
        long trace = PerformanceDiagnostics.begin(RAW_IO_WRITE);
        try {
            if (!tile.key().equals(key)) {
                throw new IllegalArgumentException("tile key " + tile.key() + " does not match " + key);
            }

            byte[] body = encodeBody(tile);
            byte[] fileBytes = wrapBody(body);
            Path target = this.root.resolve(key.fileName());
            Path tmp = this.root.resolve(key.fileName() + ".tmp-" + java.util.UUID.randomUUID());
            Reservation admitted = reservation == null ? tryReserve(fileBytes.length, Long.MAX_VALUE) : reservation;
            if (admitted == null) throw new IOException("Disk accounting capacity exhausted");
            try (admitted) {
                synchronized (quota) {
                    quota.beginWrite(admitted, tmp, fileBytes.length);
                }
                try {
                    Files.createDirectories(this.root);
                    Files.write(tmp, fileBytes, StandardOpenOption.CREATE_NEW);
                    synchronized (quota) {
                        try {
                            quota.refresh(target);
                            try {
                                mover.move(
                                        tmp,
                                        target,
                                        StandardCopyOption.REPLACE_EXISTING,
                                        StandardCopyOption.ATOMIC_MOVE);
                            } catch (AtomicMoveNotSupportedException fallback) {
                                mover.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                                // Validate this commit afresh; a shared read may still describe the old file.
                                if (this.readValidated(key).isEmpty()) {
                                    Files.deleteIfExists(target);
                                    throw new IOException("Non-atomic tile write failed verification for " + key);
                                }
                            }
                            quota.setSize(target, fileBytes.length);
                            admitted.close();
                        } catch (IOException | RuntimeException failure) {
                            quota.invalidate();
                            throw failure;
                        }
                    }
                } finally {
                    synchronized (quota) {
                        try {
                            Files.deleteIfExists(tmp);
                        } finally {
                            try {
                                quota.finishWrite(tmp);
                            } catch (IOException failure) {
                                quota.invalidate();
                                throw failure;
                            }
                        }
                    }
                }
            }

            GenerationMetrics.countTileStored();
        } catch (IOException | RuntimeException failure) {
            emit(
                    Code.IO_WRITE,
                    DiagnosticErrors.context("CACHE_WRITE", "tile=(" + key.tileX() + "," + key.tileZ() + ")"),
                    failure,
                    Outcome.FAILED,
                    "Write failed; original exception propagated",
                    true);
            throw failure;
        } finally {
            PerformanceDiagnostics.end(trace);
        }
    }

    private static final class InvalidCache extends IOException {
        final Code code;

        InvalidCache(Code code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static Code validationCode(Exception failure) {
        if (failure instanceof InvalidCache invalid) return invalid.code;
        if (failure instanceof java.io.EOFException) return Code.CAC_EOF;
        return Code.CAC_ENTRY;
    }

    public long usedBytes() throws IOException {
        return quota.usedBytes();
    }

    /** Explicit reconciliation after out-of-band maintenance; not a hot-path quota check. */
    public long reconcileUsedBytes() throws IOException {
        return quota.reconcile();
    }

    public Reservation tryReserve(long bytes, long limit) throws IOException {
        return quota.reserve(bytes, limit);
    }

    public QuotaStatistics quotaStatistics() {
        return quota.statistics();
    }

    public boolean delete(TerrainTileKey key) throws IOException {
        synchronized (quota) {
            quota.usedBytes();
            Path target = root.resolve(key.fileName());
            try {
                boolean deleted = Files.deleteIfExists(target);
                quota.setSize(target, 0);
                return deleted;
            } catch (IOException failure) {
                quota.invalidate();
                throw failure;
            }
        }
    }

    public record QuotaStatistics(
            long usedBytes, long reservedBytes, long scans, long sizeQueries, boolean initialized) {}

    public static final class Reservation implements AutoCloseable {
        final DiskQuota quota;
        final long bytes;
        boolean closed;

        Reservation(DiskQuota quota, long bytes) {
            this.quota = quota;
            this.bytes = bytes;
        }

        @Override
        public void close() {
            quota.release(this);
        }
    }

    public static long worstCaseEncodedSize(TerrainTileKey key) {
        long cells = Math.multiplyExact((long) key.tileSize(), key.tileSize());
        long fingerprintBytes = modifiedUtfLength(key.fingerprint());
        long envelope = 16L;
        long bodyMetadata = 6L + fingerprintBytes + 8L + 4L + 4L + 4L + 4L;
        long layerHeaders = 18L;
        long payload = cells * 20L;
        return Math.addExact(Math.addExact(envelope, bodyMetadata), Math.addExact(layerHeaders, payload));
    }

    private static int modifiedUtfLength(String value) {
        int bytes = 0;

        for (int i = 0; i < value.length(); i++) {
            int c = value.charAt(i);
            if (c >= 1 && c <= 127) {
                bytes++;
            } else if (c <= 2047) {
                bytes += 2;
            } else {
                bytes += 3;
            }

            if (bytes > 65535) {
                throw new IllegalArgumentException("fingerprint exceeds EWT modified-UTF limit");
            }
        }

        return bytes;
    }

    private static byte[] encodeBody(TerrainTile tile) throws IOException {
        TerrainTileKey key = tile.key();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(2);
            out.writeUTF(key.fingerprint());
            out.writeLong(key.seed());
            out.writeInt(key.tileX());
            out.writeInt(key.tileZ());
            out.writeInt(key.tileSize());
            float[] climate = tile.climate();
            out.writeInt(climate == null ? 2 : 3);
            writeShortLayer(out, (byte) 1, tile.heightsMeters());
            writeShortLayer(out, (byte) 2, tile.biomeIds());
            if (climate != null) {
                writeFloatLayer(out, (byte) 3, climate);
            }
        }

        return buffer.toByteArray();
    }

    private static void writeShortLayer(DataOutputStream out, byte layerId, short[] values) throws IOException {
        out.writeByte(layerId);
        out.writeByte(83);
        out.writeInt(values.length);

        for (short value : values) {
            out.writeShort(value);
        }
    }

    private static void writeFloatLayer(DataOutputStream out, byte layerId, float[] values) throws IOException {
        out.writeByte(layerId);
        out.writeByte(70);
        out.writeInt(values.length);

        for (float value : values) {
            out.writeFloat(value);
        }
    }

    private static byte[] wrapBody(byte[] body) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(body);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(MAGIC.length + 8 + 4 + body.length);

        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.write(MAGIC);
            out.writeLong(crc.getValue());
            out.writeInt(body.length);
            out.write(body);
        }

        return buffer.toByteArray();
    }

    private static TerrainTile decode(byte[] raw) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            byte[] magic = new byte[MAGIC.length];
            int read = in.read(magic);
            if (read != MAGIC.length) throw new java.io.EOFException("Truncated cache header");
            if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
                throw new IOException("bad magic");
            } else {
                long expectedCrc = in.readLong();
                int bodyLength = in.readInt();
                if (bodyLength < 0 || bodyLength > 67108864) {
                    throw new IOException("implausible body length " + bodyLength);
                } else if (bodyLength != in.available()) {
                    throw new InvalidCache(
                            bodyLength > in.available() ? Code.CAC_EOF : Code.CAC_ENTRY,
                            "body length mismatch: declared " + bodyLength + " bytes, found " + in.available());
                } else {
                    byte[] body = new byte[bodyLength];
                    in.readFully(body);
                    CRC32 crc = new CRC32();
                    crc.update(body);
                    if (crc.getValue() != expectedCrc) {
                        throw new InvalidCache(Code.CAC_CRC, "checksum mismatch");
                    } else {
                        return decodeBody(body);
                    }
                }
            }
        }
    }

    private static TerrainTile decodeBody(byte[] body) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            int formatVersion = in.readInt();
            if (formatVersion == 1) {
                return decodeBodyV1(in);
            }

            if (formatVersion != 2) {
                throw new InvalidCache(Code.CAC_VERSION, "unsupported tile format " + formatVersion);
            }

            String fingerprint = in.readUTF();
            long seed = in.readLong();
            int tileX = in.readInt();
            int tileZ = in.readInt();
            int tileSize = in.readInt();
            if (tileSize < 16 || tileSize > 1024) {
                throw new IOException("implausible tile size " + tileSize);
            }

            int expected = tileSize * tileSize;
            int layerCount = in.readInt();
            if (layerCount < 0 || layerCount > 16) {
                throw new IOException("implausible layer count " + layerCount);
            }

            short[] heights = null;
            short[] biomes = null;
            float[] climate = null;

            for (int layer = 0; layer < layerCount; layer++) {
                byte layerId = in.readByte();
                byte elemType = in.readByte();
                int count = in.readInt();
                if (count < 0 || count > 4194304) {
                    throw new IOException("implausible layer size " + count);
                }

                if (layerId == 1 && elemType == 83 && count == expected) {
                    heights = readShorts(in, count);
                } else if (layerId == 2 && elemType == 83 && count == expected) {
                    biomes = readShorts(in, count);
                } else if (layerId == 3 && elemType == 70 && count == 4 * expected) {
                    climate = readFloats(in, count);
                } else if (elemType == 83) {
                    skipFully(in, 2L * count);
                } else {
                    if (elemType != 70) {
                        throw new IOException("unknown layer encoding for layer " + layerId);
                    }

                    skipFully(in, 4L * count);
                }
            }

            if (heights == null || biomes == null) {
                throw new IOException("missing required layers");
            }

            TerrainTileKey key = TerrainTileKey.ofTile(seed, tileX, tileZ, tileSize, fingerprint);
            return new TerrainTile(key, heights, biomes, climate);
        }
    }

    private static short[] readShorts(DataInputStream in, int count) throws IOException {
        short[] values = new short[count];

        for (int i = 0; i < count; i++) {
            values[i] = in.readShort();
        }

        return values;
    }

    private static float[] readFloats(DataInputStream in, int count) throws IOException {
        float[] values = new float[count];

        for (int i = 0; i < count; i++) {
            values[i] = in.readFloat();
            if (!Float.isFinite(values[i])) throw new IOException("Non-finite climate value in cache entry");
        }

        return values;
    }

    private static void skipFully(DataInputStream in, long bytes) throws IOException {
        long remaining = bytes;

        while (remaining > 0L) {
            long skipped = in.skip(remaining);
            if (skipped <= 0L) {
                throw new IOException("truncated layer payload");
            }

            remaining -= skipped;
        }
    }

    private static TerrainTile decodeBodyV1(DataInputStream in) throws IOException {
        String fingerprint = in.readUTF();
        long seed = in.readLong();
        int tileX = in.readInt();
        int tileZ = in.readInt();
        int tileSize = in.readInt();
        if (tileSize >= 16 && tileSize <= 1024) {
            int expected = tileSize * tileSize;
            int layerCount = in.readInt();
            if (layerCount >= 0 && layerCount <= 16) {
                short[] heights = null;
                short[] biomes = null;

                for (int layer = 0; layer < layerCount; layer++) {
                    byte layerId = in.readByte();
                    int count = in.readInt();
                    if (count != expected) {
                        throw new IOException("layer " + layerId + " holds " + count + " values, expected " + expected);
                    }

                    short[] values = new short[count];

                    for (int i = 0; i < count; i++) {
                        values[i] = in.readShort();
                    }

                    if (layerId == 1) {
                        heights = values;
                    } else if (layerId == 2) {
                        biomes = values;
                    }
                }

                if (heights != null && biomes != null) {
                    TerrainTileKey key = TerrainTileKey.ofTile(seed, tileX, tileZ, tileSize, fingerprint);
                    return new TerrainTile(key, heights, biomes, null);
                } else {
                    throw new IOException("missing required layers");
                }
            } else {
                throw new IOException("implausible layer count " + layerCount);
            }
        } else {
            throw new IOException("implausible tile size " + tileSize);
        }
    }
}

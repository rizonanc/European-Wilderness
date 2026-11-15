package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

public final class PassCampDecisionStore {
    private static final int MAGIC = 1346585683;
    private static final int FORMAT = 1;
    private static final int MAX_FINGERPRINT_BYTES = 512;
    private static final int MAX_FILE_BYTES = 600;
    private static final int LOCK_STRIPES = 64;
    private final Path directory;
    private final Object[] locks = new Object[64];

    public PassCampDecisionStore(Path directory) {
        this.directory =
                Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();

        for (int i = 0; i < this.locks.length; i++) {
            this.locks[i] = new Object();
        }
    }

    public PassCampDecisionStore.Decision decide(
            TerrainTileKey key, int worldScale, int generatorVersion, PassCampDecisionStore.CheckedTileLookup lookup)
            throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(lookup, "lookup");
        if (worldScale > 0 && generatorVersion > 0) {
            byte[] fingerprint = key.fingerprint().getBytes(StandardCharsets.UTF_8);
            if (fingerprint.length > 512) {
                throw new IllegalArgumentException("fingerprint too long for decision marker");
            }

            Path path = this.markerPath(key);
            synchronized (this.locks[Math.floorMod(key.hashCode(), this.locks.length)]) {
                if (Files.exists(path)) {
                    PassCampDecisionStore.Decision existing = read(path, key, worldScale, generatorVersion);
                    if (existing == PassCampDecisionStore.Decision.ACTIVE) {
                        requireMatchingTile(lookup.load(key), key);
                    }

                    return existing;
                } else {
                    TerrainTile tile = lookup.load(key);
                    PassCampDecisionStore.Decision decision = tile == null
                            ? PassCampDecisionStore.Decision.DISABLED
                            : PassCampDecisionStore.Decision.ACTIVE;
                    if (tile != null) {
                        requireMatchingTile(tile, key);
                    }

                    this.write(path, key, worldScale, generatorVersion, decision, fingerprint);
                    return decision;
                }
            }
        } else {
            throw new IllegalArgumentException("worldScale and generatorVersion must be positive");
        }
    }

    private static void requireMatchingTile(TerrainTile tile, TerrainTileKey key) {
        if (tile == null) {
            throw new IllegalStateException("Active pass/camp decision requires its prepared terrain tile: " + key);
        }

        if (!key.equals(tile.key())) {
            throw new IllegalStateException("Pass/camp lookup returned foreign terrain tile for " + key);
        }
    }

    private Path markerPath(TerrainTileKey key) {
        return this.directory.resolve("pc_" + key.seed() + "_" + key.tileX() + "_" + key.tileZ() + ".pcd");
    }

    private static byte[] payload(
            TerrainTileKey key,
            int worldScale,
            int generatorVersion,
            PassCampDecisionStore.Decision decision,
            byte[] fingerprint)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + fingerprint.length);

        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1346585683);
            out.writeByte(1);
            out.writeLong(key.seed());
            out.writeInt(key.tileX());
            out.writeInt(key.tileZ());
            out.writeInt(key.tileSize());
            out.writeInt(worldScale);
            out.writeInt(generatorVersion);
            out.writeShort(fingerprint.length);
            out.write(fingerprint);
            out.writeByte(decision == PassCampDecisionStore.Decision.ACTIVE ? 1 : 0);
        }

        return bytes.toByteArray();
    }

    private void write(
            Path path,
            TerrainTileKey key,
            int worldScale,
            int generatorVersion,
            PassCampDecisionStore.Decision decision,
            byte[] fingerprint)
            throws IOException {
        byte[] data = payload(key, worldScale, generatorVersion, decision, fingerprint);
        CRC32 crc = new CRC32();
        crc.update(data);
        Files.createDirectories(this.directory);
        Path temp = Files.createTempFile(this.directory, ".pass-camp-", ".tmp");
        boolean moved = false;

        try {
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
                out.write(data);
                out.writeInt((int) crc.getValue());
            }

            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, path);
            }

            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static PassCampDecisionStore.Decision read(
            Path path, TerrainTileKey key, int worldScale, int generatorVersion) throws IOException {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(path)) {
            bytes = in.readNBytes(601);
        }

        if (bytes.length <= 600 && bytes.length >= 40) {
            int bodyLength = bytes.length - 4;
            CRC32 crc = new CRC32();
            crc.update(bytes, 0, bodyLength);

            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
                if (in.readInt() != 1346585683 || in.readUnsignedByte() != 1) {
                    throw new IllegalStateException("Unknown pass/camp decision marker: " + path);
                } else if (in.readLong() != key.seed()
                        || in.readInt() != key.tileX()
                        || in.readInt() != key.tileZ()
                        || in.readInt() != key.tileSize()
                        || in.readInt() != worldScale
                        || in.readInt() != generatorVersion) {
                    throw new IllegalStateException("Foreign pass/camp decision marker: " + path);
                } else {
                    int fpLength = in.readUnsignedShort();
                    if (fpLength > 512 || fpLength > in.available() - 5) {
                        throw new IllegalStateException("Invalid pass/camp fingerprint length: " + path);
                    } else {
                        byte[] fp = in.readNBytes(fpLength);
                        if (!Arrays.equals(fp, key.fingerprint().getBytes(StandardCharsets.UTF_8))) {
                            throw new IllegalStateException("Foreign pass/camp art fingerprint: " + path);
                        } else {
                            int encodedDecision = in.readUnsignedByte();
                            if (encodedDecision != 0 && encodedDecision != 1) {
                                throw new IllegalStateException("Invalid pass/camp decision value: " + path);
                            } else if (in.available() != 4 || in.readInt() != (int) crc.getValue()) {
                                throw new IllegalStateException("Corrupt pass/camp decision marker: " + path);
                            } else {
                                return encodedDecision == 1
                                        ? PassCampDecisionStore.Decision.ACTIVE
                                        : PassCampDecisionStore.Decision.DISABLED;
                            }
                        }
                    }
                }
            } catch (IOException malformed) {
                throw new IllegalStateException("Corrupt pass/camp decision marker: " + path, malformed);
            }
        } else {
            throw new IllegalStateException("Invalid pass/camp decision marker size: " + path);
        }
    }

    @FunctionalInterface
    public interface CheckedTileLookup {
        TerrainTile load(TerrainTileKey var1) throws Exception;
    }

    public enum Decision {
        ACTIVE,
        DISABLED;
    }
}

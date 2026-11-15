package com.github.xandergos.terraindiffusionmc.worldgen.cache;

import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import java.nio.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CacheFailureDiagnosticsTest {
    @TempDir Path temp;

    static long seen(String code) {
        var v = DiagnosticErrors.statistics().get(code);
        return v == null ? 0 : ((Number) ((Map<?, ?>) v).get("seen")).longValue();
    }

    @Test
    void crcFailureRepairsOneEntryWithoutRemovingOtherCachedData() throws Exception {
        var writer = new DiskTerrainStore(temp);
        var key = DiskQuotaTest.key(0);
        var other = DiskQuotaTest.key(1);
        var tile = DiskQuotaTest.tile(key, false);
        writer.store(key, tile);
        writer.store(other, DiskQuotaTest.tile(other, false));
        byte[] otherBefore = Files.readAllBytes(temp.resolve(other.fileName()));
        byte[] bytes = Files.readAllBytes(temp.resolve(key.fileName()));
        bytes[bytes.length - 1] ^= 1;
        Files.write(temp.resolve(key.fileName()), bytes);
        long before = seen("TD-CAC-001");
        assertTrue(writer.load(key).isEmpty());
        assertEquals(before + 1, seen("TD-CAC-001"));
        var repository = new TerrainRepository(writer, k -> tile, 16, 1 << 20, 1 << 24);
        assertEquals(tile, repository.get(key));
        assertEquals(tile, writer.load(key).orElseThrow());
        assertArrayEquals(otherBefore, Files.readAllBytes(temp.resolve(other.fileName())));
    }

    @Test
    void missingFileIsQuietButIoFailureIsNotTreatedAsOrdinaryAbsence() {
        var key = DiskQuotaTest.key(0);
        long before = seen("TD-IO-001");
        assertTrue(new DiskTerrainStore(temp).load(key).isEmpty());
        assertEquals(before, seen("TD-IO-001"));
        var denied =
                new DiskTerrainStore(
                        temp,
                        p -> {
                            throw new AccessDeniedException("fixture read");
                        });
        assertTrue(denied.load(key).isEmpty());
        assertEquals(before + 1, seen("TD-IO-001"));
    }

    @Test
    void versionEofAndSemanticCorruptionAreClassifiedSeparately() throws Exception {
        var key = DiskQuotaTest.key(0);
        var store = new DiskTerrainStore(temp);
        store.store(key, DiskQuotaTest.tile(key, false));
        var path = temp.resolve(key.fileName());
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer.wrap(bytes).putInt(16, 999);
        var crc = new java.util.zip.CRC32();
        crc.update(bytes, 16, bytes.length - 16);
        ByteBuffer.wrap(bytes).putLong(4, crc.getValue());
        Files.write(path, bytes);
        long before = seen("TD-CAC-002");
        assertTrue(store.load(key).isEmpty());
        assertEquals(before + 1, seen("TD-CAC-002"));
        store.store(key, DiskQuotaTest.tile(key, false));
        byte[] valid = Files.readAllBytes(path);
        Files.write(path, Arrays.copyOf(valid, valid.length + 1));
        before = seen("TD-CAC-004");
        assertTrue(store.load(key).isEmpty());
        assertEquals(before + 1, seen("TD-CAC-004"));
        before = seen("TD-CAC-003");
        Files.write(path, new byte[] {69, 87});
        assertTrue(store.load(key).isEmpty());
        assertEquals(before + 1, seen("TD-CAC-003"));
        var bad = DiskQuotaTest.tile(key, true);
        float[] climate = bad.climate();
        climate[0] = Float.NaN;
        store.store(key, new TerrainTile(key, bad.heightsMeters(), bad.biomeIds(), climate));
        before = seen("TD-CAC-004");
        assertTrue(store.load(key).isEmpty());
        assertEquals(before + 1, seen("TD-CAC-004"));
    }

    @Test
    void failedWritePreservesTheOriginalExceptionAndIsNotReportedAsSuccess() {
        var original = new IOException("fixture commit failure");
        var store =
                new DiskTerrainStore(
                        temp,
                        Files::readAllBytes,
                        (a, b, options) -> {
                            throw original;
                        });
        var key = DiskQuotaTest.key(0);
        long before = seen("TD-IO-002");
        assertSame(original, assertThrows(IOException.class, () -> store.store(key, DiskQuotaTest.tile(key, false))));
        assertEquals(before + 1, seen("TD-IO-002"));
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.*;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaterSurfaceMipTest {
    private static long value(int id) {
        return (long) id << 27;
    }

    private static final WaterMipMapping MARINE =
            new WaterMipMapping() {
                public boolean ewh$isWater(int id) {
                    return id == 7;
                }

                public long ewh$surfaceWaterValue(long v) {
                    return WaterMipPolicyTest.surfaceWater(v);
                }
            };

    private static long[] reef(int surface, boolean plants) {
        long[] data = new long[4681];
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int id =
                            y < surface
                                    ? ((x + z + y) % 3 == 0 ? 180 : 171)
                                    : y == surface ? (plants && (x + z) % 3 == 0 ? 181 : 7) : 0;
                    data[y * 256 + z * 16 + x] = value(id) | (23L << 47) | (15L << 56);
                }
        return data;
    }

    private void stock(long[] data) throws Exception {
        var fixture = new WaterMipPolicyTest.Fixture();
        WaterMipRepairData.stock(
                data, (a, b, c, d, e, f, g, h) -> fixture.reduce(new long[] {a, b, c, d, e, f, g, h}, false));
    }

    @Test
    void testFullCoverageWithReef()
            throws Exception {
        for (int surface = 0; surface < 16; surface++) {
            long[] data = reef(surface, true);
            stock(data);
            long[] original = data.clone();
            WaterSurfaceMip.correct(data, MARINE);
            assertArrayEquals(Arrays.copyOf(original, 4096), Arrays.copyOf(data, 4096));
            for (int level = 1; level <= 4; level++) {
                int width = 16 >> level, offset = WaterMipRepairData.offset(level), layer = surface >> level;
                for (int y = 0; y < width; y++)
                    for (int z = 0; z < width; z++)
                        for (int x = 0; x < width; x++) {
                            int i = offset + y * width * width + z * width + x;
                            if (y == layer) {
                                assertEquals(
                                        7, WaterMipPolicy.blockId(data[i]), "surface=" + surface + " level=" + level);
                                assertEquals(23, (data[i] >>> 47) & 511);
                            } else assertEquals(original[i], data[i]);
                        }
            }
        }
    }

    @Test
    void completelyWaterloggedPlantSurfaceUsesExistingPureWaterMapping() throws Exception {
        long[] data = reef(14, false);
        Arrays.fill(data, 14 * 256, 15 * 256, value(181));
        stock(data);
        WaterSurfaceMip.correct(data, MARINE);
        assertEquals(7, WaterMipPolicy.blockId(data[4680]));
        assertEquals(181, WaterMipPolicy.blockId(data[14 * 256]));
    }

    @Test
    void testDryCoastRoofAirColumn() throws Exception {
        for (int dry : new int[] {0, 2, 180, 182}) {
            long[] data = reef(14, true);
            if (dry == 0) for (int y = 0; y < 16; y++) data[y * 256] = 0;
            else data[15 * 256] = value(dry);
            stock(data);
            long unchanged = data[4680];
            WaterSurfaceMip.correct(data, MARINE);
            assertEquals(unchanged, data[4680], "dry=" + dry);
        }
    }

    @Test
    void testPartialCoverageOnlyChangesFullyWaterCoveredSubregions() throws Exception {
        long[] data = reef(14, true);
        data[15 * 256] = value(2);
        stock(data);
        long[] before = data.clone();
        WaterSurfaceMip.correct(data, MARINE);
        for (int level = 1; level <= 4; level++) {
            int width = 16 >> level, index = WaterMipRepairData.offset(level) + (14 >> level) * width * width;
            assertEquals(before[index], data[index]);
            if (width > 1) assertEquals(7, WaterMipPolicy.blockId(data[index + width - 1]));
        }
    }

    @Test
    void neighbouringWaterLevelsAcrossAMipBoundaryPreserveTheirSharedSurface() throws Exception {
        long[] data = reef(3, false);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) if ((x & 3) < 2) data[4 * 256 + z * 16 + x] = value(7);
        stock(data);
        long[] base = Arrays.copyOf(data, 4096);
        int index = WaterMipRepairData.offset(2);
        assertNotEquals(7, WaterMipPolicy.blockId(data[index]), "fixture reproduces the lost L2 water");
        WaterSurfaceMip.correct(data, MARINE);
        assertEquals(7, WaterMipPolicy.blockId(data[index]));
        assertArrayEquals(base, Arrays.copyOf(data, 4096));
    }

    @Test
    void testSteppedSurfaceDoesNotFillAColumn() throws Exception {
        long[] data = reef(3, false);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) if ((x & 3) < 2) data[4 * 256 + z * 16 + x] = value(7);
        data[3 * 256] = value(2);
        stock(data);
        int index = WaterMipRepairData.offset(2);
        long original = data[index];
        WaterSurfaceMip.correct(data, MARINE);
        assertEquals(original, data[index]);
    }

    @Test
    void testDeeperStepKeepsOnlyTheLayerCoveredByWaterInEveryColumn() throws Exception {
        long[] data = reef(3, false);
        for (int y = 4; y <= 8; y++)
            for (int z = 0; z < 16; z++) for (int x = 0; x < 2; x++) data[y * 256 + z * 16 + x] = value(7);
        stock(data);
        int index = WaterMipRepairData.offset(2);
        WaterSurfaceMip.correct(data, MARINE);
        assertEquals(7, WaterMipPolicy.blockId(data[index]));
    }

    @Test
    void testSteppedSurfaceCanRepairAnExistingParent() throws Exception {
        long[] stock = reef(3, false);
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) if ((x & 3) < 2) stock[4 * 256 + z * 16 + x] = value(7);
        stock(stock);
        long[] fixed = stock.clone();
        WaterSurfaceMip.correct(fixed, MARINE);
        long[] parent = new long[32768];
        int cx = -7, cy = 13, cz = -420, level = 2, size = 4, mask = 7;
        int index = (((cy & mask) * size) << 10) | (((cz & mask) * size) << 5) | ((cx & mask) * size);
        parent[index] = stock[WaterMipRepairData.offset(level)];
        assertEquals(1, WaterMipRepairData.patch(level, cx, cy, cz, parent, stock, fixed, MARINE));
        assertEquals(7, WaterMipPolicy.blockId(parent[index]));
        assertEquals(0, WaterMipRepairData.patch(level, cx, cy, cz, parent, stock, fixed, MARINE));
        assertArrayEquals(Arrays.copyOf(stock, 4096), Arrays.copyOf(fixed, 4096));
    }

    @Test
    void emptyDryAndMissingWaterMappingNeverInventWaterOrRetainPreviousThreadLocalData() throws Exception {
        long[] data = reef(14, true);
        stock(data);
        WaterSurfaceMip.correct(data, MARINE);
        Arrays.fill(data, 0);
        assertEquals(0, WaterSurfaceMip.correct(data, MARINE));
        assertTrue(Arrays.stream(data).allMatch(v -> v == 0));
        Arrays.fill(data, value(2));
        long[] before = data.clone();
        assertEquals(0, WaterSurfaceMip.correct(data, MARINE));
        assertArrayEquals(before, data);
        data = reef(14, true);
        stock(data);
        before = data.clone();
        assertEquals(0, WaterSurfaceMip.correct(data, id -> false));
        assertArrayEquals(before, data);
        assertThrows(IllegalArgumentException.class, () -> WaterSurfaceMip.correct(new long[4096], MARINE));
    }

    @Test
    void repeatedHotScanUsesNoTemporaryArraysAfterWarmup() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) return;
        bean.setThreadAllocatedMemoryEnabled(true);
        long[] data = new long[4681];
        Arrays.fill(data, value(2));
        for (int i = 0; i < 10000; i++) WaterSurfaceMip.correct(data, MARINE);
        long thread = Thread.currentThread().threadId(), start = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 10000; i++) WaterSurfaceMip.correct(data, MARINE);
        assertTrue(bean.getThreadAllocatedBytes(thread) - start < 4096);
    }
}

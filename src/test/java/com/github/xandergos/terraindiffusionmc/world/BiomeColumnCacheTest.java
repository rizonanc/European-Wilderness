package com.github.xandergos.terraindiffusionmc.world;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeColumnCacheTest {
    @Test
    void repeatedVerticalQueriesOnlyReadSixteenColumnsAndPreserveIds() {
        var cache = new BiomeColumnCache();
        var session = UUID.randomUUID();
        int reads = 0;
        for (int y = -64; y < 320; y += 4) {
            for (int z = -4; z < 0; z++)
                for (int x = -8; x < -4; x++) {
                    short expected = (short) (208 + Math.floorMod(x + z, 3));
                    int id = cache.get(session, x, z);
                    if (id == BiomeColumnCache.MISSING) {
                        reads++;
                        id = expected;
                        cache.put(session, x, z, expected);
                    }
                    assertEquals(expected, id);
                }
        }
        assertEquals(16, reads, "One horizontal biome lookup per column, independent of height");
    }

    @Test
    void negativeBoundariesEvictionAndWorldChangesCannotReturnForeignIds() {
        var cache = new BiomeColumnCache();
        var first = UUID.randomUUID();
        cache.put(first, -1, -1, (short) -1);
        cache.put(first, 0, 0, Short.MIN_VALUE);
        cache.put(first, -4, -4, (short) 44);
        assertEquals(-1, cache.get(first, -1, -1));
        assertEquals(Short.MIN_VALUE, cache.get(first, 0, 0));
        assertEquals(44, cache.get(first, -4, -4));
        assertEquals(BiomeColumnCache.MISSING, cache.get(first, -5, -4));
        // Five distinct chunks must evict at least the oldest of the four slots.
        for (int i = 1; i <= 4; i++) cache.put(first, i * 4, 0, (short) i);
        assertEquals(BiomeColumnCache.MISSING, cache.get(first, -1, -1));
        assertEquals(4, cache.get(first, 16, 0));
        var second = UUID.randomUUID();
        assertEquals(BiomeColumnCache.MISSING, cache.get(second, 16, 0));
        cache.put(second, 16, 0, (short) 215);
        assertEquals(215, cache.get(second, 16, 0));
        assertEquals(BiomeColumnCache.MISSING, cache.get(first, 16, 0));
    }
}

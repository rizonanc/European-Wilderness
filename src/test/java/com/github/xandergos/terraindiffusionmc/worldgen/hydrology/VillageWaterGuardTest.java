package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.Arrays;
import net.minecraft.util.math.BlockBox;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VillageWaterGuardTest {
    @Test
    void testChecksEveryColumnOfAPieceAtNegativeCoordinates() {
        int[] air = new int[4096], water = new int[4096];
        Arrays.fill(air, 98);
        Arrays.fill(water, PreparedWaterArea.NONE);
        water[20 * 64 + 20] = 100;
        var overlay = new PreparedWaterArea(-128, -256, 64, air, water);

        assertTrue(VillageWaterGuard.intersects(overlay, new BlockBox(-110, 98, -238, -106, 106, -234)));
        assertFalse(VillageWaterGuard.intersects(overlay, new BlockBox(-118, 98, -238, -114, 106, -234)));
        assertFalse(VillageWaterGuard.intersects(overlay, new BlockBox(-110, 115, -238, -106, 120, -234)));
        assertFalse(VillageWaterGuard.intersects(overlay, new BlockBox(-110, 60, -238, -106, 90, -234)));
    }
}

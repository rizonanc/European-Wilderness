package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class KarstResurgenceTest {
    @Test
    void choosesTheLowestOutletWithinReach() {
        var outlet = MountainLandforms.findResurgence((x, z) -> x == 7 ? 125 : z == -7 ? 122 : 140, 0, 140, 0);
        assertEquals(Direction.NORTH, outlet.direction());
        assertEquals(122, outlet.edge());
        assertNull(MountainLandforms.findResurgence((x, z) -> 137, 0, 140, 0));
    }
}

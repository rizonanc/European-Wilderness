package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static org.junit.jupiter.api.Assertions.*;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import org.junit.jupiter.api.Test;

class CoarseSeamParityTest {
    private CoarseLodPatch patch(int x, int z) {
        int g = CoarseLodPatch.GRID, n = g * g;
        var tensor = new FloatTensor(new int[] {7, g, g});
        for (int j = 0; j < g; j++)
            for (int i = 0; i < g; i++) {
                int p = j * g + i;
                float root = (float) (10 + (x * 64 + i - 2) * .003 + (z * 64 + j - 2) * .007);
                tensor.data[p] = root;
                tensor.data[2 * n + p] = 12;
                tensor.data[4 * n + p] = 500;
                tensor.data[6 * n + p] = 1;
            }
        return new CoarseLodPatch(new CoarseLodPatch.Key(x, z), 2, 8, 12345, 1871, tensor);
    }

    @Test
    void testGlobalSamplesMatchAtPatchBorders() {
        for (int k : new int[] {-2, -1, 0, 1}) {
            var a = patch(k, 0);
            var b = patch(k + 1, 0);
            int edge = (k + 1) * 32768;
            for (int dx : new int[] {-512, -64, -32, -1, 0, 1, 32, 64, 255})
                for (int z : new int[] {0, 32, 64, 512, 32767}) {
                    assertEquals(
                            a.channel(0, edge + dx, z),
                            b.channel(0, edge + dx, z),
                            0,
                            "Root values at " + (edge + dx) + "," + z);
                    assertEquals(a.surface(edge + dx, z), b.surface(edge + dx, z));
                }
        }
    }

    @Test
    void occupiedChildOctantsMatchActualPinnedVoxyAbiAtLodOneThroughFour() throws Exception {
        var type = new CoarseRawAbiTest.RawLoader().loadClass("me.cortex.voxy.common.world.WorldSection");
        var index = type.getMethod("getChildIndex", int.class, int.class, int.class);
        long[] palette = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        for (int level = 1; level <= 4; level++)
            for (int z : new int[] {0, 16})
                for (int x : new int[] {0, 16})
                    for (int y : new int[] {0, 16}) {
                        int unit = 1 << level, wx = x * unit + unit / 2, wz = z * unit + unit / 2, wy = y * unit;
                        long[] raw = new long[32768];
                        var result = CoarseSectionComposer.fill(
                                level,
                                0,
                                0,
                                0,
                                wy,
                                wy + unit - 1,
                                CoarseSectionComposerTest.flat(wy + 1),
                                null,
                                raw,
                                (px, pz) -> px != wx || pz != wz,
                                b -> palette,
                                v -> v == 0);
                        int bit = (int) index.invoke(null, x >> 4, y >> 4, z >> 4);
                        assertEquals(1 << bit, Byte.toUnsignedInt(result.children()));
                        assertEquals(1, result.nonAir());
                    }
    }
}

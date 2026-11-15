package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FloatTensorFastPathTest {
    static void same(float[] a, float[] b) {
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++)
            assertEquals(Float.floatToRawIntBits(a[i]), Float.floatToRawIntBits(b[i]), "raw bits at " + i);
    }

    static void compare(FloatTensor dst, FloatTensor src, int[][] dr, int[][] sr) {
        var expected = new FloatTensor(dst.shape, dst.data.clone());
        System.arraycopy(dst.strides, 0, expected.strides, 0, dst.ndim());
        var source = new FloatTensor(src.shape, src.data == dst.data ? expected.data : src.data.clone());
        System.arraycopy(src.strides, 0, source.strides, 0, src.ndim());
        expected.addFromGeneric(source, dr, sr);
        dst.addFrom(src, dr, sr);
        same(expected.data, dst.data);
    }

    @Test
    void randomizedPartialRegionsMatchOriginalForRanksOneThroughSix() {
        var rng = new Random(8712);
        for (int n = 1; n <= 6; n++)
            for (int run = 0; run < 250; run++) {
                int[] shape = new int[n];
                int[][] dr = new int[n][2], sr = new int[n][2];
                for (int d = 0; d < n; d++) {
                    shape[d] = 1 + rng.nextInt(5);
                    int count = rng.nextInt(shape[d] + 1);
                    dr[d][0] = rng.nextInt(shape[d] - count + 1);
                    dr[d][1] = dr[d][0] + count;
                    sr[d][0] = rng.nextInt(shape[d] - count + 1);
                    sr[d][1] = sr[d][0] + count;
                }
                var dst = new FloatTensor(shape);
                var src = new FloatTensor(shape);
                for (int i = 0; i < dst.data.length; i++) {
                    dst.data[i] = rng.nextFloat() * 100 - 50;
                    src.data[i] = rng.nextFloat() * 100 - 50;
                }
                compare(dst, src, dr, sr);
            }
    }

    @Test
    void forwardAliasSemanticsSignedZeroNaNAndInfinityRemainIdentical() {
        float[] specials = {
            0.0f,
            -0.0f,
            Float.NaN,
            Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.MIN_VALUE,
            -Float.MIN_VALUE,
            1,
            -1
        };
        for (int shift : new int[] {-2, -1, 0, 1, 2}) {
            var dst = new FloatTensor(new int[] {3, 4, 9});
            for (int i = 0; i < dst.data.length; i++) dst.data[i] = specials[i % specials.length];
            var src = new FloatTensor(dst.shape, dst.data);
            int a = Math.max(0, shift), b = Math.max(0, -shift);
            compare(dst, src, new int[][] {{0, 3}, {0, 4}, {a, a + 7}}, new int[][] {{0, 3}, {0, 4}, {b, b + 7}});
        }
        var d = new FloatTensor(new int[] {9}, specials.clone());
        var s = new FloatTensor(new int[] {9}, specials.clone());
        compare(d, s, new int[][] {{0, 9}}, new int[][] {{0, 9}});
    }

    @Test
    void testArbitraryOuterAndLastStridesUseCorrectFallback() {
        var a = new FloatTensor(new int[] {4, 6, 6}, new float[288]);
        var b = new FloatTensor(a.shape, new float[288]);
        for (int i = 0; i < a.data.length; i++) {
            a.data[i] = i / 3.0f;
            b.data[i] = -i / 7.0f;
        }
        a.strides[1] = -6;
        b.strides[1] = -6;
        compare(a, b, new int[][] {{1, 3}, {1, 5}, {0, 6}}, new int[][] {{1, 3}, {1, 5}, {0, 6}});
        a.strides[1] = 6;
        b.strides[1] = 6;
        a.strides[2] = 2;
        b.strides[2] = 2;
        compare(a, b, new int[][] {{0, 4}, {0, 6}, {0, 6}}, new int[][] {{0, 4}, {0, 6}, {0, 6}});
        var before = a.data.clone();
        assertThrows(
                IllegalArgumentException.class,
                () -> a.addFrom(b, new int[][] {{0, 4}, {0, 6}, {-1, 5}}, new int[][] {{0, 4}, {0, 6}, {0, 6}}));
        same(before, a.data);
        assertThrows(
                IllegalArgumentException.class,
                () -> a.addFrom(b, new int[][] {{0, 4}, {0, 6}, {0, 6}}, new int[][] {{0, 4}, {0, 6}, {0, 5}}));
        same(before, a.data);
    }

    @Test
    void testIndependentWorkersNeverShareMutableScratchOrOutputs() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<FloatTensor>>();
            for (int i = 0; i < 32; i++)
                tasks.add(
                        workers.submit(() -> {
                            var dst = new FloatTensor(new int[] {4, 8, 16});
                            var src = new FloatTensor(dst.shape);
                            Arrays.fill(src.data, 0.25f);
                            int[][] region = {{0, 4}, {0, 8}, {0, 16}};
                            for (int j = 0; j < 100; j++) dst.addFrom(src, region, region);
                            return dst;
                        }));
            for (var task : tasks) for (float value : task.get(10, TimeUnit.SECONDS).data) assertEquals(25.0f, value);
        }
    }
}

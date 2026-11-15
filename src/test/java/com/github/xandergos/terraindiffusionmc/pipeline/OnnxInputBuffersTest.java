package com.github.xandergos.terraindiffusionmc.pipeline;

import ai.onnxruntime.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnnxInputBuffersTest {
    // Self-authored ONNX Identity graph, dynamic float vector, IR8/opset13. No downloaded model.
    static final byte[] MODEL =
            Base64.getDecoder()
                    .decode(
                            "CAgSFnRlcnJhaW4tZGlmZnVzaW9uLXRlc3Q6SAoQCgF4EgF5IghJZGVudGl0eRIQaW5wdXQtcmV1c2UtdGVzdFoQCgF4EgsKCQgBEgUKAxIBbmIQCgF5EgsKCQgBEgUKAxIBbkICEA0=");

    private Object[][] input(float[] values) {
        return new Object[][] {{"x", values, new long[] {values.length}}};
    }

    static OrtSession session() throws OrtException {
        try (var opts = new OrtSession.SessionOptions()) {
            opts.setIntraOpNumThreads(1);
            opts.setInterOpNumThreads(1);
            return OrtEnvironment.getEnvironment().createSession(MODEL, opts);
        }
    }

    @Test
    void testNativeRunSeesFreshValuesInTheSameTensor() throws Exception {
        var env = OrtEnvironment.getEnvironment();
        try (var session = session();
                var pool = new OnnxInputBuffers(1024)) {
            OnnxTensor first = null;
            float[] earlier = null;
            for (int step = 0; step < 20; step++) {
                float[] values = {step, -step, Float.intBitsToFloat(0x80000000), Float.intBitsToFloat(0x7fc00123)};
                try (var lease = pool.prepare(env, input(values));
                        var result = session.run(lease.feed())) {
                    var tensor = lease.feed().get("x");
                    if (first == null) first = tensor;
                    else assertSame(first, tensor);
                    float[] actual = new float[4];
                    ((OnnxTensor) result.get(0)).getFloatBuffer().get(actual);
                    for (int i = 0; i < 4; i++)
                        assertEquals(Float.floatToRawIntBits(values[i]), Float.floatToRawIntBits(actual[i]));
                    if (earlier == null) earlier = actual;
                    else assertEquals(0, Float.floatToRawIntBits(earlier[0]));
                }
            }
            assertEquals(1, pool.created());
            assertEquals(19, pool.reused());
            assertEquals(16, pool.retainedBytes());
        }
    }

    @Test
    void testShapeChangesAreRebuilt() throws Exception {
        var env = OrtEnvironment.getEnvironment();
        try (var session = session();
                var pool = new OnnxInputBuffers(16)) {
            for (int size : new int[] {4, 4, 12, 12, 4}) {
                float[] values = new float[size];
                Arrays.fill(values, size);
                try (var lease = pool.prepare(env, input(values));
                        var result = session.run(lease.feed())) {
                    var actual = ((OnnxTensor) result.get(0)).getFloatBuffer();
                    assertEquals(size, actual.remaining());
                    while (actual.hasRemaining()) assertEquals((float) size, actual.get());
                    assertTrue(pool.retainedBytes() <= 16);
                }
            }
            assertEquals(4, pool.created());
            assertEquals(1, pool.reused());
        }
    }

    @Test
    void testInvalidInputAndFailedRunDoNotPoisonTheNextLease() throws Exception {
        var env = OrtEnvironment.getEnvironment();
        try (var session = session();
                var pool = new OnnxInputBuffers(128)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> pool.prepare(env, new Object[][] {{"x", new float[3], new long[] {4}}}));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            pool.prepare(
                                    env,
                                    new Object[][] {
                                        {"x", new float[1], new long[] {1}}, {"x", new float[1], new long[] {1}}
                                    }));
            try (var lease = pool.prepare(env, input(new float[] {7}))) {
                assertThrows(IllegalStateException.class, () -> pool.prepare(env, input(new float[] {9})));
                assertThrows(IllegalStateException.class, pool::close);
                assertThrows(OrtException.class, () -> session.run(Map.of()));
                try (var result = session.run(lease.feed())) {
                    assertEquals(7f, ((OnnxTensor) result.get(0)).getFloatBuffer().get());
                }
            }
            try (var lease = pool.prepare(env, input(new float[] {11}));
                    var result = session.run(lease.feed())) {
                assertEquals(11f, ((OnnxTensor) result.get(0)).getFloatBuffer().get());
            }
        }
    }
}

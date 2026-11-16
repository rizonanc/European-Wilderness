package com.github.xandergos.terraindiffusionmc.pipeline;

import ai.onnxruntime.*;
import java.nio.*;
import java.util.*;

/** CPU direct input storage, reused only after the preceding synchronous Run has returned.
 * This avoids repeat native input allocation, not the execution provider's GPU transfers. */
final class OnnxInputBuffers implements AutoCloseable {
    private record Slot(long[] shape, FloatBuffer buffer, OnnxTensor tensor, long bytes) {}

    private final Map<String, Slot> slots = new HashMap<>();
    private final long budget;
    private volatile long retained, created, reused, copiedBytes;
    private boolean active, closed;

    OnnxInputBuffers(long budget) {
        if (budget < 0) throw new IllegalArgumentException("Negative input budget");
        this.budget = budget;
    }

    Lease prepare(OrtEnvironment env, Object[][] inputs) throws OrtException {
        if (active || closed) throw new IllegalStateException("Input storage is active or closed");
        PipelineChecks.inputs(inputs);
        active = true;
        var feed = new LinkedHashMap<String, OnnxTensor>();
        var temporary = new ArrayList<Slot>();
        try {
            for (var input : inputs) {
                String name = (String) input[0];
                float[] data = (float[]) input[1];
                long[] shape = (long[]) input[2];
                var slot = slots.get(name);
                if (slot != null && !Arrays.equals(slot.shape, shape)) {
                    slots.remove(name);
                    retained -= slot.bytes;
                    slot.tensor.close();
                    slot = null;
                }
                if (slot == null) {
                    long bytes = (long) data.length * Float.BYTES;
                    var buffer = ByteBuffer.allocateDirect((int) bytes)
                            .order(ByteOrder.nativeOrder())
                            .asFloatBuffer();
                    long[] ownedShape = shape.clone();
                    slot = new Slot(ownedShape, buffer, OnnxTensor.createTensor(env, buffer, ownedShape), bytes);
                    created++;
                    if (slots.size() < 16 && retained + bytes <= budget) {
                        slots.put(name, slot);
                        retained += bytes;
                    } else temporary.add(slot);
                } else reused++;
                slot.buffer.clear();
                slot.buffer.put(data);
                slot.buffer.rewind();
                copiedBytes += (long) data.length * Float.BYTES;
                feed.put(name, slot.tensor);
            }
            return new Lease(feed, temporary);
        } catch (OrtException | RuntimeException | Error failure) {
            temporary.forEach(s -> s.tensor.close());
            active = false;
            throw failure;
        }
    }

    long retainedBytes() {
        return retained;
    }

    long created() {
        return created;
    }

    long reused() {
        return reused;
    }

    String statistics() {
        return "created="
                + created
                + " reused="
                + reused
                + " retainedBytes="
                + retained
                + " copiedInputBytes="
                + copiedBytes;
    }

    final class Lease implements AutoCloseable {
        private final Map<String, OnnxTensor> feed;
        private final List<Slot> temporary;
        private boolean released;

        Lease(Map<String, OnnxTensor> feed, List<Slot> temporary) {
            this.feed = Collections.unmodifiableMap(feed);
            this.temporary = temporary;
        }

        Map<String, OnnxTensor> feed() {
            if (released) throw new IllegalStateException("Inputs released");
            return feed;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                temporary.forEach(s -> s.tensor.close());
                active = false;
            }
        }
    }

    @Override
    public void close() {
        if (active) throw new IllegalStateException("Run still owns inputs");
        if (!closed) {
            closed = true;
            slots.values().forEach(s -> s.tensor.close());
            slots.clear();
            retained = 0;
        }
    }
}

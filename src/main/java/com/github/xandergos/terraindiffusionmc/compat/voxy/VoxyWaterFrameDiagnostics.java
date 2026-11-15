package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.MinecraftClient;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL12C.GL_PACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL45C.*;

/** Explicit, bounded, one-frame inspection. Never writes GL textures, config or world data. */
public final class VoxyWaterFrameDiagnostics {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(VoxyWaterFrameDiagnostics.class);
    private static volatile long deadline;
    private static volatile String last = "water-frame: no capture requested";

    private VoxyWaterFrameDiagnostics() {}

    public static String request() {
        try {
            if (!Voxy016Api.runtimeReady()) return "water-frame: Voxy runtime not ready";
        } catch (ReflectiveOperationException | LinkageError failure) {
            return "water-frame: Voxy runtime unavailable";
        }
        last = "water-frame: waiting for a translucent MDIC frame";
        deadline = System.nanoTime() + 15_000_000_000L;
        return "Диагностика воды: один кадр Voxy будет записан в latest.log. Оставь воду в кадре; /td-voxy status покажет результат.";
    }

    public static String status() {
        if (deadline != 0 && System.nanoTime() > deadline) {
            deadline = 0;
            last = "water-frame: no translucent MDIC frame within 15s; Voxy may be disabled or world not rendering";
        }
        return last;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass())
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
            }
        throw new NoSuchFieldException(name);
    }

    private static Object call(Object owner, String name) throws ReflectiveOperationException {
        return owner.getClass().getMethod(name).invoke(owner);
    }

    private static float depth(int texture, int x, int y) {
        ByteBuffer data = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        glGetTextureSubImage(texture, 0, x, y, 0, 1, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, data);
        return data.getFloat(0);
    }

    private static String colour(int texture, int x, int y) {
        if (texture == 0) return "none";
        int format = glGetTextureLevelParameteri(texture, 0, GL_TEXTURE_INTERNAL_FORMAT);
        // Photon uses normalized/float targets. Do not read integer formats as floats.
        boolean integer =
                switch (format) {
                    case GL_RGBA32UI, GL_RGBA16UI, GL_RGBA8UI, GL_RGBA32I, GL_RGBA16I, GL_RGBA8I -> true;
                    default -> false;
                };
        ByteBuffer data = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        glGetTextureSubImage(
                texture,
                0,
                x,
                y,
                0,
                1,
                1,
                1,
                integer ? GL_RGBA_INTEGER : GL_RGBA,
                integer ? GL_UNSIGNED_INT : GL_FLOAT,
                data);
        return integer
                ? Arrays.toString(new int[] {data.getInt(0), data.getInt(4), data.getInt(8), data.getInt(12)})
                : Arrays.toString(
                        new float[] {data.getFloat(0), data.getFloat(4), data.getFloat(8), data.getFloat(12)});
    }

    /** Called only at the end of Voxy's translucent draw, with its framebuffer still bound. */
    public static void capture(Object renderer, Object viewport) {
        long requested = deadline;
        if (requested == 0) return;
        deadline = 0;
        if (System.nanoTime() > requested) {
            last = "water-frame: request expired";
            return;
        }
        try {
            if (!MinecraftClient.getInstance().isOnThread()) throw new IllegalStateException("Not render thread");
            if (glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING) != 0
                    || glGetInteger(GL_PACK_SKIP_ROWS) != 0
                    || glGetInteger(GL_PACK_SKIP_PIXELS) != 0
                    || glGetInteger(GL_PACK_SKIP_IMAGES) != 0)
                throw new IllegalStateException("Pixel pack buffer busy; no read performed");
            int framebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            if (framebuffer == 0) throw new IllegalStateException("No Voxy framebuffer");
            int w = (int) field(viewport, "width"), h = (int) field(viewport, "height");
            if (w < 4 || h < 4) throw new IllegalStateException("Empty viewport");
            int bound = (int) field(call(field(viewport, "depthBoundingBuffer"), "getDepthTex"), "id");
            int depth =
                    glGetNamedFramebufferAttachmentParameteri(
                            framebuffer, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int[] targets = new int[2];
            for (int i = 0; i < targets.length; i++)
                targets[i] =
                        glGetNamedFramebufferAttachmentParameteri(
                                framebuffer, GL_COLOR_ATTACHMENT0 + i, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int countBuffer = (int) field(field(viewport, "drawCountCallBuffer"), "id");
            int[] counts = new int[8];
            glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
            glGetNamedBufferSubData(countBuffer, 0, counts);
            StringBuilder line =
                    new StringBuilder("water-frame: viewport=")
                            .append(w)
                            .append('x')
                            .append(h)
                            .append(" counters=")
                            .append(Arrays.toString(counts))
                            .append(" depthFunc=")
                            .append(glGetInteger(GL_DEPTH_FUNC))
                            .append(" formats=");
            for (int target : targets)
                line.append(
                                target == 0
                                        ? "none"
                                        : Integer.toHexString(
                                                glGetTextureLevelParameteri(target, 0, GL_TEXTURE_INTERNAL_FORMAT)))
                        .append(',');
            var inverse =
                    new org.joml.Matrix4f((org.joml.Matrix4f) field(viewport, "projection"))
                            .mul((org.joml.Matrix4f) field(viewport, "modelView"))
                            .invert();
            double cx = (double) field(viewport, "cameraX"),
                    cy = (double) field(viewport, "cameraY"),
                    cz = (double) field(viewport, "cameraZ");
            for (int x : new int[] {w / 8, w / 2, w * 7 / 8})
                for (int y : new int[] {h / 3, h / 2, h * 2 / 3}) {
                    float d = depth(depth, x, y);
                    line.append(" [")
                            .append(x)
                            .append(',')
                            .append(y)
                            .append(" bound=")
                            .append(depth(bound, x, y))
                            .append(" depth=")
                            .append(d)
                            .append(" worldXYZ=")
                            .append(worldPoint(inverse, x, y, w, h, d, cx, cy, cz))
                            .append(" colour=")
                            .append(colour(targets[0], x, y))
                            .append(" waterData=")
                            .append(colour(targets[1], x, y))
                            .append(']');
                }
            last = line.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            last = "water-frame: unavailable (" + failure + ")";
        }
        LOG.info("{}", last);
    }

    static String worldPoint(
            org.joml.Matrix4f inverse,
            int x,
            int y,
            int width,
            int height,
            float depth,
            double cx,
            double cy,
            double cz) {
        if (!Float.isFinite(depth) || depth >= 1 || depth < 0) return "SKY/UNKNOWN";
        var p =
                inverse.transform(
                        new org.joml.Vector4f((x + .5f) / width * 2 - 1, (y + .5f) / height * 2 - 1, depth * 2 - 1, 1));
        if (!Float.isFinite(p.w) || Math.abs(p.w) < 1e-9) return "UNKNOWN";
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", cx + p.x / p.w, cy + p.y / p.w, cz + p.z / p.w);
    }
}

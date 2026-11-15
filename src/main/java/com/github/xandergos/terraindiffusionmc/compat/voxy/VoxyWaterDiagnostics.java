package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/** Explicit read-only snapshot for the pinned Voxy 0.2.16 renderer, on the client thread. */
public final class VoxyWaterDiagnostics {
    private VoxyWaterDiagnostics() {}

    static boolean facePresent(long metadata, int face) {
        if (face < 0 || face > 5) throw new IllegalArgumentException("Face must be 0..5");
        return ((metadata >>> (face * 8)) & 255L) != 255L;
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    public static String snapshot() {
        try {
            if (!Voxy016Api.runtimeReady()) return "water-model: Voxy runtime not ready";
            Object renderer =
                    Class.forName("me.cortex.voxy.client.core.IGetVoxyRenderSystem")
                            .getMethod("getNullable")
                            .invoke(null);
            if (renderer == null) return "water-model: no active Voxy renderer";
            Object engine = renderer.getClass().getMethod("getEngine").invoke(renderer);
            Object mapper = engine.getClass().getMethod("getMapper").invoke(engine);
            Object service = field(renderer, "modelService");
            Object factory = service.getClass().getField("factory").get(service);
            Class<?> factoryType = factory.getClass();
            var has = factoryType.getMethod("hasModelForBlockId", int.class);
            var modelId = factoryType.getMethod("getModelId", int.class);
            var metadata = factoryType.getMethod("getModelMetadataFromClientId", int.class);
            // Enumerate existing entries only. Never register states or request a bake.
            Object[] entries = (Object[]) mapper.getClass().getMethod("getStateEntries").invoke(mapper);
            int states = 0, baked = 0, missingTop = 0;
            var details = new ArrayList<String>();
            for (Object entry : entries) {
                Object rawState = entry.getClass().getField("state").get(entry);
                if (!(rawState instanceof BlockState state) || !state.isOf(Blocks.WATER)) continue;
                states++;
                int id = entry.getClass().getField("id").getInt(entry);
                if (!(boolean) has.invoke(factory, id)) continue;
                baked++;
                int clientId = (int) modelId.invoke(factory, id);
                long meta = (long) metadata.invoke(factory, clientId);
                if (!facePresent(meta, 1)) missingTop++;
                if (details.size() < 4) details.add(id + ":" + clientId + ":0x" + Long.toHexString(meta));
            }
            String pipeline = field(renderer, "pipeline").getClass().getSimpleName();
            return "water-model: pipeline="
                    + pipeline
                    + " states="
                    + states
                    + " baked="
                    + baked
                    + " missingTop="
                    + missingTop
                    + " waterMipHook="
                    + (mapper instanceof WaterMipMapping)
                    + " metadata="
                    + details
                    + " (read-only; a present face does not prove visible pixels)";
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return "water-model: snapshot unavailable (" + failure.getClass().getSimpleName() + ")";
        }
    }
}

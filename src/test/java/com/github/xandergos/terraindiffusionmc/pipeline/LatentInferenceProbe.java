package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;

public final class LatentInferenceProbe {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), out = Path.of(args[1]).toAbsolutePath();
        int size = args.length > 2 ? Integer.parseInt(args[2]) : 160;
        boolean repeat = args.length > 3 && args[3].equalsIgnoreCase("repeat");
        Files.createDirectories(out);
        Path models = out.resolve("terrain-diffusion-models"), config = out.resolve("config");
        Files.createDirectories(models);
        Files.createDirectories(config);
        for (String file : List.of("world_pipeline_config.json", "pipeline_data.json"))
            Files.copy(source.resolve(file), models.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(config.resolve("european-wilderness.properties"), "inference.device=cpu\n");
        var provider =
                (GameProvider)
                        Proxy.newProxyInstance(
                                LatentInferenceProbe.class.getClassLoader(),
                                new Class[] {GameProvider.class},
                                (p, m, a) -> {
                                    if (m.getName().equals("getLaunchDirectory")) return out;
                                    if (m.getName().equals("getGameId")) return "latent-probe";
                                    if (m.getReturnType() == boolean.class) return false;
                                    return null;
                                });
        FabricLoaderImpl.INSTANCE.setGameProvider(provider);
        Field ready = ModelAssetManager.class.getDeclaredField("READY");
        ready.setAccessible(true);
        ((AtomicBoolean) ready.get(null)).set(true);
        long started = System.nanoTime();
        try (var coarse = new OnnxModel(source.resolve("coarse_model.onnx"), "coarse");
                var base = new OnnxModel(source.resolve("base_model.onnx"), "base");
                var decoder = new OnnxModel(source.resolve("decoder_model.onnx"), "decoder")) {
            var pipeline = new WorldPipeline(12345, coarse, base, decoder);
            var first = get(pipeline, size, 0, 0);
            if (repeat) {
                var again = get(pipeline, size, 0, 0);
                if (!Arrays.equals(first.elev, again.elev) || !Arrays.equals(first.climate, again.climate))
                    throw new AssertionError("Repeat of the same region is not bit-identical");
                var shifted = get(pipeline, Math.max(64, size / 2), size, size); // different window layout
                if (shifted.elev.length != Math.max(64, size / 2) * Math.max(64, size / 2))
                    throw new AssertionError("Shifted region has wrong element count");
                if (!Arrays.equals(shifted.elev, get(pipeline, Math.max(64, size / 2), size, size).elev))
                    throw new AssertionError("Shifted region repeat is not bit-identical");
            }
            // Deterministic batch-remainder sequence against the real base graph:
            // five pending latent windows in one pass are grouped 4 then 1, so a batch
            // of 1 runs right after batches of 4 — the production shape/data contract.
            pipeline.latents.getSlice(new int[] {0, 0, 0}, new int[] {6, 33, 161});
            // A third distinct batch size (2) after 4 and 1.
            pipeline.latents.getSlice(new int[] {0, 300, 0}, new int[] {6, 333, 33});
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("seed", 12345);
            report.put("provider", "CPU");
            report.put("region", size);
            report.put("elevChecksum", checksum(first.elev));
            report.put("climateChecksum", checksum(first.climate));
            report.put("elevMin", min(first.elev));
            report.put("elevMax", max(first.elev));
            report.put("windows", pipeline.getTotalComputedWindowCount());
            report.put("native", OnnxModel.statistics());
            report.put("totalMs", (System.nanoTime() - started) / 1e6);
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report);
            Files.writeString(out.resolve("latent-probe-report.json"), json + "\n");
            ByteBuffer bytes = ByteBuffer.allocate(first.elev.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : first.elev) bytes.putFloat(value);
            Files.write(out.resolve("latent-probe-elev-f32.bin"), bytes.array());
            System.out.println(json);
            System.out.println("PROBE OK");
        }
    }

    private record Result(float[] elev, float[] climate) {}

    private static Result get(WorldPipeline pipeline, int size, int ox, int oy) throws Exception {
        long started = System.nanoTime();
        var values = pipeline.get(ox, oy, ox + size, oy + size, true);
        float[] elev = values[0], climate = values[1];
        if (elev == null || elev.length != size * size)
            throw new AssertionError("Elevation element count " + (elev == null ? "null" : elev.length));
        if (climate == null || climate.length != 5 * size * size) throw new AssertionError("Climate element count");
        for (float value : elev) if (!Float.isFinite(value)) throw new AssertionError("Non-finite elevation");
        for (float value : climate) if (!Float.isFinite(value)) throw new AssertionError("Non-finite climate");
        System.out.println(
                "region "
                        + size
                        + " at "
                        + ox
                        + ","
                        + oy
                        + " done in "
                        + ((System.nanoTime() - started) / 1e6)
                        + " ms; elevMin="
                        + min(elev)
                        + " elevMax="
                        + max(elev));
        return new Result(elev, climate);
    }

    private static long checksum(float[] values) {
        long c = 17;
        for (float v : values) c = c * 31 + Float.floatToRawIntBits(v);
        return c;
    }

    private static float min(float[] values) {
        float m = Float.MAX_VALUE;
        for (float v : values) m = Math.min(m, v);
        return m;
    }

    private static float max(float[] values) {
        float m = -Float.MAX_VALUE;
        for (float v : values) m = Math.max(m, v);
        return m;
    }

    private LatentInferenceProbe() {}
}

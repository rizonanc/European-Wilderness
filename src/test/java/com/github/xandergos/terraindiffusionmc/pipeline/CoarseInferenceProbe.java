package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.compat.voxy.CoarseLodPatch;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;

/** Isolated CPU probe using the real coarse graph and production pipeline maths.
 * Only the supplied QA directory is written; base/decoder graphs are not loaded. */
public final class CoarseInferenceProbe {
    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]), out = Path.of(args[1]).toAbsolutePath();
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
                                CoarseInferenceProbe.class.getClassLoader(),
                                new Class[] {GameProvider.class},
                                (p, m, a) -> {
                                    if (m.getName().equals("getLaunchDirectory")) return out;
                                    if (m.getName().equals("getGameId")) return "coarse-probe";
                                    if (m.getReturnType() == boolean.class) return false;
                                    return null;
                                });
        FabricLoaderImpl.INSTANCE.setGameProvider(provider);
        // This process deliberately tests just the coarse graph. Prevent the normal
        // all-assets downloader from fetching/validating the unused 2GB base model.
        Field ready = ModelAssetManager.class.getDeclaredField("READY");
        ready.setAccessible(true);
        ((AtomicBoolean) ready.get(null)).set(true);
        long started = System.nanoTime();
        int steps = 0;
        long maxStep = 0;
        try (var model = new OnnxModel(source.resolve("coarse_model.onnx"), "coarse")) {
            var pipeline = new WorldPipeline(12345, model, model, model);
            int g = CoarseLodPatch.GRID;
            FloatTensor result;
            while (true) {
                long before = pipeline.getTotalComputedWindowCount(), time = System.nanoTime();
                result = pipeline.prepareCoarseLodStep(-2, -2, g - 2, g - 2);
                maxStep = Math.max(maxStep, System.nanoTime() - time);
                long added = pipeline.getTotalComputedWindowCount() - before;
                if (added > 1) throw new AssertionError("More than one coarse window in one step: " + added);
                steps++;
                if (result != null) break;
                if (steps > 30) throw new AssertionError("Coarse preparation did not finish");
            }
            var direct = pipeline.getCoarseSlice(-2, -2, g - 2, g - 2);
            if (!Arrays.equals(result.data, direct.data))
                throw new AssertionError("Incremental and direct coarse maps differ");
            ByteBuffer bytes = ByteBuffer.allocate(result.data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : result.data) bytes.putFloat(value);
            Files.write(out.resolve("coarse-weighted-f32.bin"), bytes.array());
            var patch = new CoarseLodPatch(new CoarseLodPatch.Key(0, 0), 2, 8, 12345, 1871, result);
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            long checksum = 0;
            int samples = 0;
            for (int z = 0; z < patch.span; z += 512)
                for (int x = 0; x < patch.span; x += 512) {
                    var s = patch.surface(x, z);
                    min = Math.min(min, s.firstAir());
                    max = Math.max(max, s.firstAir());
                    checksum = checksum * 31 + s.firstAir();
                    samples++;
                }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("seed", 12345);
            report.put("provider", "CPU");
            report.put("shape", result.shape);
            report.put("steps", steps);
            report.put("coarseWindows", pipeline.getTotalComputedWindowCount());
            report.put("maxStepMs", maxStep / 1e6);
            report.put("totalMs", (System.nanoTime() - started) / 1e6);
            report.put("samples", samples);
            report.put("minFirstAir", min);
            report.put("maxFirstAir", max);
            report.put("checksum", checksum);
            report.put("incrementalEqualsDirect", true);
            report.put("baseDecoderLoaded", false);
            report.put("native", OnnxModel.statistics());
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report);
            Files.writeString(out.resolve("report.json"), json + "\n");
            System.out.println(json);
        }
    }
}

package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PipelineModels implements AutoCloseable {
   private static final Logger LOG = LoggerFactory.getLogger(PipelineModels.class);
   private static final String COARSE_FILE_NAME = "coarse_model.onnx";
   private static final String BASE_FILE_NAME = "base_model.onnx";
   private static final String DECODER_FILE_NAME = "decoder_model.onnx";
   private static volatile PipelineModels INSTANCE;
   private static volatile CountDownLatch loadDone;
   private static volatile boolean loadStarted;
   private static volatile Throwable loadFailure;
   private final OnnxModel coarseModel;
   private final OnnxModel baseModel;
   private final OnnxModel decoderModel;

   public static synchronized void load() {
      if (INSTANCE == null) {
         if (!loadStarted) {
            loadStarted = true;
            loadFailure = null;
            loadDone = new CountDownLatch(1);
            Thread t = new Thread(() -> {
               try {
                  LOG.info("Loading European Wilderness ML models (background)...");
                  long start = System.currentTimeMillis();
                  INSTANCE = new PipelineModels();
                  long elapsed = System.currentTimeMillis() - start;
                  LOG.info("European Wilderness ML models loaded in {} ms", elapsed);
               } catch (Throwable e) {
                  loadFailure = e;
                  loadStarted = false;
                  failed(Code.MOD_INVALID,"MODEL_BACKGROUND_LOAD",e);
               } finally {
                  loadDone.countDown();
               }
            }, "terrain-diffusion-models");
            t.setDaemon(true);
            t.start();
         }
      }
   }

   public static void awaitLoad() {
      synchronized (PipelineModels.class) {
         if (INSTANCE != null) {
            return;
         }

         if (!loadStarted) {
            load();
         }
      }

      CountDownLatch latch = loadDone;
      if (latch != null) {
         try {
            if (!latch.await(10L, TimeUnit.MINUTES)) {
               throw new RuntimeException("Timed out waiting for terrain-diffusion models");
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for terrain-diffusion models", e);
         }
      }

      if (INSTANCE == null) {
         Throwable failureCause = loadFailure;
         if (failureCause != null) {
            throw new IllegalStateException("Failed to load terrain-diffusion models", failureCause);
         } else {
            throw new IllegalStateException("Terrain-diffusion models did not load");
         }
      }
   }

   public static PipelineModels getInstance() {
      return INSTANCE;
   }

   private PipelineModels() {
      ModelAssetManager.ensureAssetsReady();
      this.coarseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("coarse_model.onnx"), "coarse");
      this.baseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("base_model.onnx"), "base");
      this.decoderModel = new OnnxModel(ModelAssetManager.resolveAssetPath("decoder_model.onnx"), "decoder");
   }

   public OnnxModel getCoarseModel() {
      return this.coarseModel;
   }

   public OnnxModel getBaseModel() {
      return this.baseModel;
   }

   public OnnxModel getDecoderModel() {
      return this.decoderModel;
   }

   @Override
   public synchronized void close() {
      if (INSTANCE == this) {
         LOG.info("Closing European Wilderness ML models");

         try {
            this.coarseModel.close();
            this.baseModel.close();
            this.decoderModel.close();
         } finally {
            INSTANCE = null;
            loadStarted = false;
            loadFailure = null;
         }
      }
   }
}

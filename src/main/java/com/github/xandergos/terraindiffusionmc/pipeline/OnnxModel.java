package com.github.xandergos.terraindiffusionmc.pipeline;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtSession.Result;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import ai.onnxruntime.providers.CoreMLFlags;
import ai.onnxruntime.providers.OrtCUDAProviderOptions;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OnnxModel implements AutoCloseable {
   private static final Logger LOG = LoggerFactory.getLogger(OnnxModel.class);
   private static final String OPTIMIZED_MODELS_DIR_NAME = "onnx-cache";
   private static volatile String resolvedInferenceProvider = null;
   private static final AtomicBoolean providerLoggedOnce = new AtomicBoolean(false);
   private static final AtomicBoolean coremlWarnLoggedOnce = new AtomicBoolean(false);
   private static final AtomicBoolean cudaWarnLoggedOnce = new AtomicBoolean(false);
   private static final AtomicBoolean dmlWarnLoggedOnce = new AtomicBoolean(false);
   private static final AtomicBoolean noGpuWarnLoggedOnce = new AtomicBoolean(false);
   private static final GpuSessionCache<OnnxModel,OrtSession> GPU_SESSIONS = new GpuSessionCache<>((model,session)->{
      long start=System.nanoTime();try{session.close();}finally{model.timings.record("sessionClose",System.nanoTime()-start);}
   });
   private final OrtEnvironment env;
   private final byte[] optimizedModelBytes;
   private final String name;
   private OrtSession cpuSession;
   private OrtSession gpuSession;
   private static final java.util.concurrent.ConcurrentHashMap<String,com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings> PROFILE=new java.util.concurrent.ConcurrentHashMap<>();
   private static final java.util.concurrent.ConcurrentHashMap<String,OnnxInputBuffers> BUFFERS=new java.util.concurrent.ConcurrentHashMap<>();
   private final com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings timings;
   private final OnnxInputBuffers inputBuffers=new OnnxInputBuffers(8L*1024*1024);
   private boolean closed;
   public static String statistics(){var result=new java.util.TreeMap<String,String>();PROFILE.forEach((name,profile)->{
      var buffers=BUFFERS.get(name);result.put(name,profile+" inputs={"+(buffers==null?"closed":buffers.statistics())+"}");});return "gpuCache={"+GPU_SESSIONS.statistics()+"} "+result;}
   public static Map<String,Object> diagnosticCounters(){
      var result=new java.util.TreeMap<String,Object>();
      PROFILE.forEach((name,profile)->{var buffers=BUFFERS.get(name);result.put(name,Map.of("timings",profile.snapshot(),"inputs",buffers==null?"closed":buffers.statistics()));});
      return result;
   }
   private OrtSession createSession(byte[] model,SessionOptions options) throws OrtException {
      long start=System.nanoTime();try{return env.createSession(model,options);}finally{timings.record("sessionCreate",System.nanoTime()-start);}
   }

   static <T> T withSessionOptions(OnnxModel.SessionOptionsWork<T> work) throws OrtException {
      try (SessionOptions options = new SessionOptions()) {
         return work.run(options);
      }
   }

   static boolean isDirectMlMemoryExhaustion(Throwable failure) {
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
         String message = cause.getMessage();
         if (message != null && message.toLowerCase(Locale.ROOT).contains("8007000e")) {
            return true;
         }
      }

      return false;
   }

   static boolean isGpuMemoryExhaustion(Throwable failure) {
      if(isDirectMlMemoryExhaustion(failure))return true;
      for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
         String message=cause.getMessage();
         if(message!=null) {
            String lower=message.toLowerCase(Locale.ROOT);
            if(lower.contains("cuda_error_out_of_memory")||lower.contains("cuda failure 2:")
                  ||lower.contains("cudnn_status_alloc_failed"))return true;
         }
      }
      return false;
   }

   public OnnxModel(Path modelFilePath, String name) {
      this.name = name;
      this.timings=PROFILE.computeIfAbsent(name,k->new com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings());
      BUFFERS.put(name,inputBuffers);

      try {
         long start = System.currentTimeMillis();
         this.env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
         byte[] sourceModelBytes = Files.readAllBytes(modelFilePath);
         OnnxModel.OptimizedModelLoadResult initialOptimizedModelLoadResult = this.optimizeModelAtRuntime(sourceModelBytes, false);

         byte[] loadedModelBytes;
         try {
            this.initializeModelSession(initialOptimizedModelLoadResult.modelBytes, start);
            loadedModelBytes = initialOptimizedModelLoadResult.modelBytes;
         } catch (Exception initialLoadException) {
            if (!initialOptimizedModelLoadResult.loadedFromCache) {
               throw initialLoadException;
            }

            this.closeLoadedSessions();
            emit(Code.MOD_CACHE,current("OPTIMIZED_MODEL_LOAD"),initialLoadException,Outcome.DEGRADED,"Rebuilding only optimized model cache from source model",false);
            this.deleteOptimizedCacheFile(initialOptimizedModelLoadResult.optimizedModelPath);
            OnnxModel.OptimizedModelLoadResult rebuiltOptimizedModelLoadResult = this.optimizeModelAtRuntime(sourceModelBytes, true);
            this.initializeModelSession(rebuiltOptimizedModelLoadResult.modelBytes, start);
            loadedModelBytes = rebuiltOptimizedModelLoadResult.modelBytes;
         }

         this.optimizedModelBytes = loadedModelBytes;
      } catch (Exception e) {
         failed(e instanceof java.nio.file.NoSuchFileException?Code.MOD_MISSING:Code.MOD_INVALID,"MODEL_SESSION_INIT",e);
         throw new RuntimeException("Failed to load ONNX model: " + modelFilePath.getFileName(), e);
      }
   }

   private OnnxModel.OptimizedModelLoadResult optimizeModelAtRuntime(byte[] sourceModelBytes, boolean forceRebuildFromSource) {
      Path optimizedModelPath = this.resolveOptimizedModelPath(sourceModelBytes);

      try {
         if (!forceRebuildFromSource && Files.exists(optimizedModelPath)) {
            byte[] cachedOptimizedModelBytes = Files.readAllBytes(optimizedModelPath);
            return new OnnxModel.OptimizedModelLoadResult(cachedOptimizedModelBytes, optimizedModelPath, true);
         } else {
            Files.createDirectories(optimizedModelPath.getParent());
            Path temporaryOptimizedModelPath = optimizedModelPath.resolveSibling(optimizedModelPath.getFileName() + ".tmp");
            Files.deleteIfExists(temporaryOptimizedModelPath);
            withSessionOptions(optimizationOptions -> {
               optimizationOptions.setOptimizationLevel(OptLevel.EXTENDED_OPT);
               optimizationOptions.setOptimizedModelFilePath(temporaryOptimizedModelPath.toAbsolutePath().toString());
               OrtSession ignored = this.env.createSession(sourceModelBytes, optimizationOptions);
               if (ignored != null) {
                  ignored.close();
               }

               return null;
            });
            byte[] optimizedModelBytesFromDisk = Files.readAllBytes(temporaryOptimizedModelPath);
            Files.move(temporaryOptimizedModelPath, optimizedModelPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            LOG.info(
               "Optimized ONNX model '{}' at runtime ({} KB -> {} KB)",
               new Object[]{this.name, sourceModelBytes.length / 1024, optimizedModelBytesFromDisk.length / 1024}
            );
            return new OnnxModel.OptimizedModelLoadResult(optimizedModelBytesFromDisk, optimizedModelPath, false);
         }
      } catch (Exception optimizationException) {
         emit(Code.MOD_CACHE,current("MODEL_OPTIMIZATION"),optimizationException,Outcome.DEGRADED,"Using original model bytes; no model substitution",false);
         return new OnnxModel.OptimizedModelLoadResult(sourceModelBytes, optimizedModelPath, false);
      }
   }

   public static String getResolvedInferenceProvider() {
      String provider = resolvedInferenceProvider;
      return provider != null ? provider : "unknown";
   }

   private static void setResolvedProviderOnce(String provider) {
      if (resolvedInferenceProvider == null) {
         resolvedInferenceProvider = provider;
         DiagnosticErrors.provider(provider);
      }

      if (providerLoggedOnce.compareAndSet(false, true)) {
         LOG.info("Terrain diffusion inference: {}", provider);
      }
   }

   private void initializeModelSession(byte[] modelBytes, long startMillis) throws OrtException {
      if ("cpu".equals(TerrainDiffusionConfig.inferenceDevice())) {
         this.cpuSession = withSessionOptions(sessionOptions -> {
            sessionOptions.setOptimizationLevel(OptLevel.ALL_OPT);
            return createSession(modelBytes, sessionOptions);
         });
         this.gpuSession = null;
         setResolvedProviderOnce("CPU");
         LOG.info("ONNX model '{}' loaded on CPU ({} KB) in {} ms", new Object[]{this.name, modelBytes.length / 1024, System.currentTimeMillis() - startMillis});
      } else if (!TerrainDiffusionConfig.offloadModels()) {
         this.gpuSession = withSessionOptions(
            sessionOptions -> {
               sessionOptions.setOptimizationLevel(OptLevel.ALL_OPT);
               addGpuProvider(sessionOptions);
               if ("CoreML".equals(resolvedInferenceProvider)) {
                  throw new OrtException(
                     "inference.offload_models=false is not supported with CoreML. Set inference.offload_models=true in terrain-diffusion-mc.properties."
                  );
               } else {
                  return createSession(modelBytes, sessionOptions);
               }
            }
         );
         this.cpuSession = null;
         LOG.info("ONNX model '{}' loaded on GPU ({} KB) in {} ms", new Object[]{this.name, modelBytes.length / 1024, System.currentTimeMillis() - startMillis});
      } else {
         this.cpuSession = null;
         this.gpuSession = null;
         LOG.info(
            "ONNX model '{}' bytes cached in CPU RAM ({} KB) in {} ms",
            new Object[]{this.name, modelBytes.length / 1024, System.currentTimeMillis() - startMillis}
         );
      }
   }

   private void closeLoadedSessions() {
      if (this.cpuSession != null) {
         try {
            this.cpuSession.close();
         } catch (OrtException var3) {emit(Code.LFC_SESSION,current("ONNX_CLOSE"),var3,Outcome.DEGRADED,"Closing remaining resources",false);
         }

         this.cpuSession = null;
      }

      if (this.gpuSession != null) {
         try {
            this.gpuSession.close();
         } catch (OrtException var2) {emit(Code.LFC_SESSION,current("ONNX_CLOSE"),var2,Outcome.DEGRADED,"Closing remaining resources",false);
         }

         this.gpuSession = null;
      }
   }

   private void deleteOptimizedCacheFile(Path optimizedModelPath) {
      try {
         Files.deleteIfExists(optimizedModelPath);
      } catch (Exception deleteException) {
         LOG.warn("Failed to delete optimized cache '{}' for '{}': {}", new Object[]{optimizedModelPath, this.name, deleteException.getMessage()});
      }
   }

   private Path resolveOptimizedModelPath(byte[] sourceModelBytes) {
      String sourceModelHashPrefix = sha256Hex(sourceModelBytes).substring(0, 16);
      String runtimeVersionTag = resolveOnnxRuntimeVersionTag();
      String optimizedFileName = this.name + "-" + runtimeVersionTag + "-" + sourceModelHashPrefix + ".onnx";
      return ModelAssetManager.resolveAssetPath("onnx-cache").resolve(optimizedFileName);
   }

   private static String resolveOnnxRuntimeVersionTag() {
      Package onnxRuntimePackage = OrtEnvironment.class.getPackage();
      String implementationVersion = onnxRuntimePackage == null ? null : onnxRuntimePackage.getImplementationVersion();
      return implementationVersion == null ? "unknown" : implementationVersion;
   }

   private static String sha256Hex(byte[] inputBytes) {
      try {
         MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
         byte[] digestBytes = messageDigest.digest(inputBytes);
         StringBuilder hexBuilder = new StringBuilder(digestBytes.length * 2);

         for (byte digestByte : digestBytes) {
            hexBuilder.append(String.format("%02x", digestByte));
         }

         return hexBuilder.toString();
      } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
         throw new IllegalStateException("Missing SHA-256 algorithm", noSuchAlgorithmException);
      }
   }

   public synchronized float[] run(Object[][] inputs) {
      if(closed){var failure=new IllegalStateException("ONNX model is closed");failed(Code.LFC_SESSION,"ONNX_RUN_AFTER_CLOSE",failure);throw failure;}
      if (this.cpuSession != null) {
         return runWithSession(this.cpuSession, inputs);
      }

      if (this.gpuSession != null) {
         return runWithSession(this.gpuSession, inputs);
      }

      long waiting=System.nanoTime();
      try {
         // CoreML keeps its existing single-slot lifetime constraint.
         int capacity="CoreML".equals(resolvedInferenceProvider)?1:TerrainDiffusionConfig.gpuSessionCacheSize();
         synchronized(GPU_SESSIONS) {
            timings.record("slotWait",System.nanoTime()-waiting);
            return GPU_SESSIONS.execute(this,capacity,this::createGpuSession,session->runWithSession(session,inputs),failure->{
               boolean exhausted=isGpuMemoryExhaustion(failure);
               if(exhausted)emit(Code.MEM_PRESSURE,current("GPU_SESSION"),failure,Outcome.DEGRADED,"Releasing cached sessions; retry once in single-session mode",false);
               return exhausted;
            });
         }
      } catch(RuntimeException failure) {throw failure;}
        catch(Exception failure) {throw new RuntimeException("Failed to use GPU session for: "+name,failure);}
   }

   public float[] runModel(float[] x, long[] xShape, float[] noiseLabels, float[][] condInputs, long[][] condShapes) {
      int nCond = condInputs == null ? 0 : condInputs.length;
      Object[][] inputs = new Object[2 + nCond][3];
      inputs[0] = new Object[]{"x", x, xShape};
      inputs[1] = new Object[]{"noise_labels", noiseLabels, new long[]{noiseLabels.length}};

      for (int i = 0; i < nCond; i++) {
         inputs[2 + i] = new Object[]{"cond_" + i, condInputs[i], condShapes[i]};
      }

      return this.run(inputs);
   }

   private OrtSession createGpuSession() throws OrtException {
      return withSessionOptions(opts->{
         opts.setOptimizationLevel(OptLevel.ALL_OPT);
         addGpuProvider(opts);
         return createSession(optimizedModelBytes,opts);
      });
   }

   private static void addGpuProvider(SessionOptions opts) throws OrtException {
      boolean gpuRequired = "gpu".equals(TerrainDiffusionConfig.inferenceDevice());
      boolean added = false;

      try {
         OrtCUDAProviderOptions cudaOpts = new OrtCUDAProviderOptions(0);
         cudaOpts.add("arena_extend_strategy", "kSameAsRequested");
         cudaOpts.add("cudnn_conv_algo_search", "HEURISTIC");
         cudaOpts.add("do_copy_in_default_stream", "1");
         opts.addCUDA(cudaOpts);
         cudaOpts.close();
         added = true;
         setResolvedProviderOnce("CUDA");
      } catch (Throwable t) {
         if (cudaWarnLoggedOnce.compareAndSet(false, true)) {
            LOG.warn("CUDA not available: {} - {}. This is expected if you are not using a CUDA build.", t.getClass().getSimpleName(), t.getMessage());
         }
      }

      if (!added) {
         try {
            opts.addDirectML(0);
            added = true;
            setResolvedProviderOnce("DirectML");
         } catch (Throwable t) {
            if (isDirectMlMemoryExhaustion(t)) {
               if (t instanceof OrtException ort) {
                  throw ort;
               }

               throw new OrtException("DirectML ran out of GPU memory: " + t.getMessage());
            }

            if (dmlWarnLoggedOnce.compareAndSet(false, true)) {
               LOG.warn(
                  "DirectML not available: {} - {}. This is expected if you are not using a DirectML build.", t.getClass().getSimpleName(), t.getMessage()
               );
            }
         }
      }

      if (!added) {
         try {
            opts.addCoreML(EnumSet.of(CoreMLFlags.ENABLE_ON_SUBGRAPH));
            added = true;
            setResolvedProviderOnce("CoreML");
         } catch (Throwable t) {
            if (coremlWarnLoggedOnce.compareAndSet(false, true)) {
               LOG.warn("CoreML not available: {} - {}. This is expected on non-macOS platforms.", t.getClass().getSimpleName(), t.getMessage());
            }
         }
      }

      if (gpuRequired && !added) {
         var unavailable=new OrtException(
            "inference.device=gpu but no GPU provider (CUDA, DirectML, CoreML) is available. Use the appropriate build for your platform or set inference.device=cpu."
         );
         failed(Code.INF_PROVIDER,"GPU_PROVIDER_INIT",unavailable);throw unavailable;
      }

      if (!added) {
         setResolvedProviderOnce("CPU");
         if (noGpuWarnLoggedOnce.compareAndSet(false, true)) {
            LOG.warn("No GPU provider loaded. Check drivers and that the mod jar is the GPU build.");
         }
      }
   }

   private float[] runWithSession(OrtSession session, Object[][] inputs) {
      OrtEnvironment env = OrtEnvironment.getEnvironment();
      long preparing=System.nanoTime();
      try (var lease=inputBuffers.prepare(env,inputs)) {
         timings.record("inputPrepare",System.nanoTime()-preparing);
         long running=System.nanoTime();Result execution;
         try{execution=session.run(lease.feed());}finally{timings.record("runWall",System.nanoTime()-running);}
         try (Result result = execution) {
            long copying=System.nanoTime();try {
            if(result.size()==0||!(result.get(0) instanceof OnnxTensor))throw PipelineChecks.invalid(Code.INF_OUTPUT,"Missing/non-tensor ONNX output");
            OnnxTensor output = (OnnxTensor)result.get(0);
            FloatBuffer buf = output.getFloatBuffer();
            float[] out = new float[buf.remaining()];
            buf.get(out);
            return out;
            }finally{timings.record("outputCopy",System.nanoTime()-copying);}
         }
      } catch (OrtException e) {
         failed("CPU".equals(resolvedInferenceProvider)?Code.INF_CPU:Code.INF_RUN,"ONNX_RUN",e);
         throw new RuntimeException("ONNX inference failed", e);
      }
   }

   @Override
   public synchronized void close() {
      if(closed)return;closed=true;
      try {GPU_SESSIONS.remove(this);}catch(Exception failure){LOG.warn("Failed to close cached GPU session '{}'",name,failure);}

      if (this.cpuSession != null) {
         try {
            this.cpuSession.close();
         } catch (OrtException var5) {emit(Code.LFC_SESSION,current("ONNX_CLOSE"),var5,Outcome.DEGRADED,"Closing remaining resources",false);
         }

         this.cpuSession = null;
      }

      if (this.gpuSession != null) {
         try {
            this.gpuSession.close();
         } catch (OrtException var4) {emit(Code.LFC_SESSION,current("ONNX_CLOSE"),var4,Outcome.DEGRADED,"Closing remaining resources",false);
         }

         this.gpuSession = null;
      }
      inputBuffers.close();
      BUFFERS.remove(name,inputBuffers);
   }

   private static final class OptimizedModelLoadResult {
      private final byte[] modelBytes;
      private final Path optimizedModelPath;
      private final boolean loadedFromCache;

      private OptimizedModelLoadResult(byte[] modelBytes, Path optimizedModelPath, boolean loadedFromCache) {
         this.modelBytes = modelBytes;
         this.optimizedModelPath = optimizedModelPath;
         this.loadedFromCache = loadedFromCache;
      }
   }

   @FunctionalInterface
   interface SessionOptionsWork<T> {
      T run(SessionOptions var1) throws OrtException;
   }
}

package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModelAssetManager {
    private static final Logger LOG = LoggerFactory.getLogger(ModelAssetManager.class);
    private static final String MANIFEST_RESOURCE_PATH = "/model-assets-manifest.json";
    private static final long PROGRESS_LOG_THRESHOLD_BYTES = 104857600L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final AtomicBoolean READY = new AtomicBoolean(false);
    private static final Gson GSON = new Gson();
    private static final Type MANIFEST_TYPE = (new TypeToken<ModelAssetManager.ModelAssetManifest>() {}).getType();

    private ModelAssetManager() {}

    public static void ensureAssetsReady() {
        if (!READY.get()) {
            synchronized (ModelAssetManager.class) {
                if (!READY.get()) {
                    try {
                        Path modelDirectory = resolveAssetPath("");
                        Files.createDirectories(modelDirectory);
                        ModelAssetManager.ModelAssetManifest manifest = loadManifest();
                        String offlineHelpUrl = buildOfflineHelpUrl(manifest);
                        boolean shouldValidatePreExistingModels = TerrainDiffusionConfig.validateModel();
                        LOG.info("Preparing European Wilderness model assets in {}", modelDirectory);

                        for (Entry<String, ModelAssetManager.ManifestAsset> assetEntry : manifest.assets.entrySet()) {
                            String fileName = assetEntry.getKey();
                            ModelAssetManager.ManifestAsset assetMetadata = assetEntry.getValue();
                            Path localAssetPath = modelDirectory.resolve(fileName);
                            ensureSingleAsset(
                                    localAssetPath,
                                    assetMetadata,
                                    manifest.revision,
                                    offlineHelpUrl,
                                    shouldValidatePreExistingModels);
                        }

                        LOG.info("European Wilderness model assets ready");
                        READY.set(true);
                    } catch (RuntimeException runtimeException) {
                        DiagnosticErrors.failed(DiagnosticErrors.Code.MOD_INVALID, "MODEL_ASSETS", runtimeException);
                        throw runtimeException;
                    } catch (Exception exception) {
                        DiagnosticErrors.failed(DiagnosticErrors.Code.MOD_MISSING, "MODEL_ASSETS", exception);
                        throw new IllegalStateException("Failed to prepare terrain diffusion model assets", exception);
                    }
                }
            }
        }
    }

    public static Path resolveAssetPath(String fileName) {
        Path game = FabricLoader.getInstance().getGameDir();
        Path current = game.resolve("european-wilderness-models");
        Path legacy = game.resolve("terrain-diffusion-models");
        return (Files.isDirectory(current) || !Files.isDirectory(legacy) ? current : legacy).resolve(fileName);
    }

    public static synchronized String getModelRevision() {
        return loadManifest().revision;
    }

    private static void ensureSingleAsset(
            Path localAssetPath,
            ModelAssetManager.ManifestAsset assetMetadata,
            String revision,
            String offlineHelpUrl,
            boolean shouldValidatePreExistingModels)
            throws IOException {
        if (Files.exists(localAssetPath)) {
            if (!shouldValidatePreExistingModels) {
                LOG.info(
                        "Using pre-existing model asset '{}' without SHA validation (validate_model=false)",
                        localAssetPath.getFileName());
                return;
            }

            String existingHash = sha256Hex(localAssetPath);
            if (existingHash.equalsIgnoreCase(assetMetadata.sha256)) {
                LOG.info("Model asset '{}' already present and verified", localAssetPath.getFileName());
                return;
            }

            LOG.warn("Model asset '{}' hash mismatch. Re-downloading expected revision.", localAssetPath.getFileName());
        }

        downloadAndVerifyAsset(localAssetPath, assetMetadata, revision, offlineHelpUrl);
    }

    private static void downloadAndVerifyAsset(
            Path localAssetPath, ModelAssetManager.ManifestAsset assetMetadata, String revision, String offlineHelpUrl)
            throws IOException {
        String fileName = localAssetPath.getFileName().toString();
        String gzipUrl = fileName.equals("base_model.onnx") ? System.getProperty("td.baseModelGzipUrl", "") : "";
        boolean gzip = !gzipUrl.isBlank();
        Path temporaryAssetPath = Files.createTempFile(localAssetPath.getParent(), fileName + "-", ".tmp");
        try {
            LOG.info(
                    "Downloading model asset '{}' ({} on disk{})",
                    fileName,
                    humanReadableBytes(assetMetadata.sizeBytes),
                    gzip ? ", gzip transfer" : "");
            downloadBody(
                    URI.create(gzip ? gzipUrl : assetMetadata.url),
                    temporaryAssetPath,
                    fileName,
                    assetMetadata.sizeBytes,
                    CONNECT_TIMEOUT_MS,
                    READ_TIMEOUT_MS,
                    gzip);
            String downloadedHash = sha256Hex(temporaryAssetPath);
            if (!downloadedHash.equalsIgnoreCase(assetMetadata.sha256)) {
                throw new IOException(
                        "SHA-256 mismatch for "
                                + fileName
                                + ". Expected "
                                + assetMetadata.sha256
                                + " but got "
                                + downloadedHash);
            }
            Files.move(
                    temporaryAssetPath,
                    localAssetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            LOG.info("Downloaded and verified model asset '{}'", fileName);
        } catch (Exception exception) {
            try {
                Files.deleteIfExists(temporaryAssetPath);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            if (isOfflineError(exception)) {
                throw new IllegalStateException(
                        "European Wilderness models are missing and must be downloaded while online. Connect to the internet and restart Minecraft. Direct download: "
                                + offlineHelpUrl
                                + " (revision "
                                + revision
                                + ")",
                        exception);
            } else if (exception instanceof IOException ioException) {
                throw ioException;
            } else {
                throw new IllegalStateException(
                        "Failed downloading model asset: " + localAssetPath.getFileName(), exception);
            }
        }
    }

    static void downloadBody(
            URI uri,
            Path destination,
            String fileName,
            long expectedSize,
            int connectTimeout,
            int readTimeout,
            boolean gzip)
            throws IOException {
        if (expectedSize <= 0) throw new IOException("Invalid manifest size for " + fileName);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(true);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status + " downloading " + fileName);
            long declaredSize = connection.getContentLengthLong();
            if (!gzip && declaredSize >= 0 && declaredSize != expectedSize) {
                throw new IOException(
                        "Unexpected Content-Length for "
                                + fileName
                                + ": "
                                + declaredSize
                                + ", expected "
                                + expectedSize);
            }
            try (InputStream response = connection.getInputStream();
                    InputStream input = gzip ? new GZIPInputStream(response, 262144) : response;
                    OutputStream output = Files.newOutputStream(destination)) {
                copyWithProgress(input, output, fileName, expectedSize, gzip ? "Unpacking" : "Downloading");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static boolean isOfflineError(Exception exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
        }

        return false;
    }

    private static ModelAssetManager.ModelAssetManifest loadManifest() {
        try (InputStream manifestStream = ModelAssetManager.class.getResourceAsStream("/model-assets-manifest.json");
                InputStreamReader manifestReader =
                        new InputStreamReader(Objects.requireNonNull(manifestStream), StandardCharsets.UTF_8); ) {
            ModelAssetManager.ModelAssetManifest modelAssetManifest =
                    (ModelAssetManager.ModelAssetManifest) GSON.fromJson(manifestReader, MANIFEST_TYPE);
            if (modelAssetManifest == null
                    || modelAssetManifest.assets == null
                    || modelAssetManifest.assets.isEmpty()) {
                throw new IllegalStateException("Model asset manifest is empty: /model-assets-manifest.json");
            } else {
                return modelAssetManifest;
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to load model asset manifest from /model-assets-manifest.json", exception);
        }
    }

    private static String buildOfflineHelpUrl(ModelAssetManager.ModelAssetManifest manifest) {
        return "https://huggingface.co/" + manifest.repositorySlug + "/tree/" + manifest.revision;
    }

    private static void copyWithProgress(
            InputStream responseStream,
            OutputStream fileOutputStream,
            String fileName,
            long expectedSizeBytes,
            String action)
            throws IOException {
        boolean shouldLogProgress = expectedSizeBytes >= 104857600L;
        byte[] copyBuffer = new byte[262144];
        long downloadedBytes = 0L;
        int nextProgressPercent = 10;

        int readCount;
        while ((readCount = responseStream.read(copyBuffer)) != -1) {
            if (readCount > expectedSizeBytes - downloadedBytes) {
                throw new IOException("Download exceeds manifest size for " + fileName + ": " + expectedSizeBytes);
            }
            fileOutputStream.write(copyBuffer, 0, readCount);
            downloadedBytes += readCount;
            if (shouldLogProgress && expectedSizeBytes > 0L) {
                for (int completedPercent = (int) (downloadedBytes * 100L / expectedSizeBytes);
                        completedPercent >= nextProgressPercent && nextProgressPercent <= 100;
                        nextProgressPercent += 10) {
                    LOG.info(
                            "{} '{}'... {}% ({}/{})",
                            action,
                            fileName,
                            nextProgressPercent,
                            humanReadableBytes(downloadedBytes),
                            humanReadableBytes(expectedSizeBytes));
                }
            }
        }

        if (downloadedBytes != expectedSizeBytes) {
            throw new IOException(
                    "Incomplete download for " + fileName + ": " + downloadedBytes + " of " + expectedSizeBytes);
        }
        if (!shouldLogProgress) {
            LOG.info("{} '{}'... {}", action, fileName, humanReadableBytes(downloadedBytes));
        }
    }

    private static String sha256Hex(Path localFilePath) throws IOException {
        MessageDigest messageDigest = createSha256Digest();

        try (InputStream fileStream = Files.newInputStream(localFilePath);
                DigestInputStream digestInputStream = new DigestInputStream(fileStream, messageDigest); ) {
            byte[] readBuffer = new byte[65536];

            while (digestInputStream.read(readBuffer) != -1) {}
        }

        return toHex(messageDigest.digest());
    }

    private static MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException noSuchAlgorithmException) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", noSuchAlgorithmException);
        }
    }

    private static String toHex(byte[] digestBytes) {
        StringBuilder hexBuilder = new StringBuilder(digestBytes.length * 2);

        for (byte digestByte : digestBytes) {
            hexBuilder.append(String.format("%02x", digestByte));
        }

        return hexBuilder.toString();
    }

    private static String humanReadableBytes(long byteCount) {
        if (byteCount < 1024L) {
            return byteCount + " B";
        }

        double kibibytes = byteCount / 1024.0;
        if (kibibytes < 1024.0) {
            return String.format("%.1f KiB", kibibytes);
        }

        double mebibytes = kibibytes / 1024.0;
        if (mebibytes < 1024.0) {
            return String.format("%.1f MiB", mebibytes);
        }

        double gibibytes = mebibytes / 1024.0;
        return String.format("%.2f GiB", gibibytes);
    }

    private static final class ManifestAsset {
        String sha256;
        long sizeBytes;
        String url;
    }

    private static final class ModelAssetManifest {
        String repositorySlug;
        String revision;
        Map<String, ModelAssetManager.ManifestAsset> assets;
    }
}

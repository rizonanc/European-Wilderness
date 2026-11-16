package com.github.xandergos.terraindiffusionmc.explorer;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;
import com.github.xandergos.terraindiffusionmc.world.SpawnChoice;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ExplorerServer {
    private static final Logger LOG = LoggerFactory.getLogger(ExplorerServer.class);
    private static final Gson GSON = new Gson();
    private static final String[] CHANNEL_NAMES = new String[] {"Elev", "p5", "Temp", "T std", "Precip", "Precip CV"};
    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();
    private static volatile HttpServer SERVER;
    private static volatile int SERVER_PORT = -1;

    public static final class SpawnPicker {
        public final String token = java.util.UUID.randomUUID().toString();
        final long seed;
        final int scale;
        private volatile SpawnChoice choice;

        SpawnPicker(long seed, int scale) {
            this.seed = seed;
            this.scale = scale;
        }

        public SpawnChoice choice() {
            return choice;
        }
    }

    private static volatile SpawnPicker picker;

    public static synchronized SpawnPicker beginSpawnPicker(long seed, int scale) {
        var selection = new SpawnPicker(seed, scale);
        LocalTerrainProvider.setPreviewSeed(seed);
        picker = selection;
        return selection;
    }

    public static synchronized void endSpawnPicker(SpawnPicker selection) {
        if (picker == selection) {
            picker = null;
            if (java.util.Objects.equals(selection.seed, LocalTerrainProvider.getPreviewSeedOrNull()))
                LocalTerrainProvider.clearPreviewSeed();
        }
    }

    private ExplorerServer() {}

    public static synchronized int startIfNotRunning() throws IOException {
        if (SERVER != null) {
            return SERVER_PORT;
        }

        int port = TerrainDiffusionConfig.explorerPort();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", port);
        HttpServer server = HttpServer.create(addr, 0);
        server.createContext("/", ExplorerServer::handleRoot);
        server.createContext("/api/spawn_choice", ExplorerServer::handleSpawnChoice);
        server.createContext("/api/status", ExplorerServer::handleStatus);
        server.createContext("/api/seed", ExplorerServer::handleSeed);
        server.createContext("/api/new_seed", ExplorerServer::handleNewSeed);
        server.createContext("/api/preview_clear", ExplorerServer::handlePreviewClear);
        server.createContext("/api/coarse.png", ExplorerServer::handleCoarsePng);
        server.createContext("/api/coarse_data.json", ExplorerServer::handleCoarseData);
        server.createContext("/api/coarse_stats", ExplorerServer::handleCoarseStats);
        server.createContext("/api/detail.png", ExplorerServer::handleDetailPng);
        server.createContext("/api/detail_raw", ExplorerServer::handleDetailRaw);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "terrain-explorer-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        SERVER = server;
        SERVER_PORT = port;
        LOG.info("Terrain explorer started at http://127.0.0.1:{}", port);
        return port;
    }

    public static synchronized void stop() {
        if (SERVER != null) {
            SERVER.stop(0);
            SERVER = null;
            SERVER_PORT = -1;
            LOG.info("Terrain explorer stopped.");
        }
    }

    public static boolean isRunning() {
        return SERVER != null;
    }

    public static int getPort() {
        return SERVER_PORT;
    }

    private static void handleRoot(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send405(ex);
        } else {
            try (InputStream in =
                    ExplorerServer.class.getResourceAsStream("/assets/terrain-diffusion-mc/explorer/index.html")) {
                if (in == null) {
                    sendError(ex, 404, "index.html not found");
                    return;
                }

                byte[] body = in.readAllBytes();
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } finally {
                ex.close();
            }
        }
    }

    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send405(ex);
        } else {
            try {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
                Long preview = LocalTerrainProvider.getPreviewSeedOrNull();
                resp.put("preview_seed", preview == null ? null : Long.toUnsignedString(preview));
                resp.put("channels", Arrays.asList(CHANNEL_NAMES));
                resp.put("native_resolution", NATIVE_RESOLUTION);
                var selection = picker;
                String token = parseQuery(ex.getRequestURI()).get("picker");
                boolean picking = selection != null && selection.token.equals(token);
                resp.put("scale", picking ? selection.scale : WorldScaleManager.getCurrentScale());
                resp.put("spawn_picker", picking);
                resp.put("spawn_seed", picking ? Long.toUnsignedString(selection.seed) : null);
                sendJson(ex, 200, resp);
            } catch (Exception e) {
                sendError(ex, 500, e.getMessage());
            }
        }
    }

    private static void handleSpawnChoice(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            send405(ex);
            return;
        }
        var selection = picker;
        if (selection == null || !selection.token.equals(ex.getRequestHeaders().getFirst("X-TD-Spawn-Picker"))) {
            sendError(ex, 403, "Open the spawn picker from world creation first.");
            return;
        }
        try {
            var body =
                    com.google.gson.JsonParser.parseString(readBody(ex, 1024)).getAsJsonObject();
            if (!Long.toUnsignedString(selection.seed).equals(body.get("seed").getAsString())
                    || !java.util.Objects.equals(selection.seed, LocalTerrainProvider.getPreviewSeedOrNull())) {
                sendError(ex, 409, "The map seed changed. Reopen the spawn picker.");
                return;
            }
            int x = Integer.parseInt(body.get("x").getAsString()),
                    z = Integer.parseInt(body.get("z").getAsString());
            var choice = new SpawnChoice(x, z, body.get("habitat").getAsString(), selection.seed);
            int nx = Math.floorDiv(x, selection.scale), nz = Math.floorDiv(z, selection.scale);
            var raw = LocalTerrainProvider.getPipelineData(nz - 2, nx - 2, nz + 3, nx + 3, false)[0];
            if (raw[12] < 0) {
                sendError(ex, 422, "This point is underwater on the preview. Choose land.");
                return;
            }
            float min = raw[12], max = raw[12];
            for (float h : raw) {
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
            if (max - min > 180) {
                sendError(ex, 422, "This point is on a steep slope. Choose flatter land.");
                return;
            }
            synchronized (ExplorerServer.class) {
                if (picker != selection) {
                    sendError(ex, 409, "The spawn picker has closed.");
                    return;
                }
                selection.choice = choice;
            }
            sendJson(ex, 200, Map.of("x", x, "z", z));
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException invalid) {
            sendError(ex, 400, "Invalid spawn point.");
        } catch (Exception failure) {
            LOG.warn("Could not validate spawn point", failure);
            sendError(ex, 500, "Could not validate spawn point.");
        }
    }

    private static void handleSeed(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send405(ex);
        } else {
            try {
                String body = readBody(ex, 1024);
                Map<String, Object> data = (Map<String, Object>) GSON.fromJson(body, Map.class);
                if (!data.containsKey("seed")) {
                    sendError(ex, 400, "seed required");
                    return;
                }

                Object input = data.get("seed");
                long previewSeed = input instanceof Number number ? number.longValue() : parseMapSeed(input.toString());
                LocalTerrainProvider.setPreviewSeed(previewSeed);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("seed", Long.toUnsignedString(previewSeed));
                resp.put("preview_seed", Long.toUnsignedString(previewSeed));
                sendJson(ex, 200, resp);
            } catch (Exception e) {
                sendError(ex, 400, e.getMessage());
            }
        }
    }

    private static long parseMapSeed(String seed) {
        return seed.startsWith("-") ? Long.parseLong(seed) : Long.parseUnsignedLong(seed);
    }

    private static void handleNewSeed(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send405(ex);
        } else {
            try {
                long previewSeed = LocalTerrainProvider.generatePreviewSeed();
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("seed", Long.toUnsignedString(previewSeed));
                sendJson(ex, 200, resp);
            } catch (Exception e) {
                sendError(ex, 400, e.getMessage());
            }
        }
    }

    private static void handlePreviewClear(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            send405(ex);
        } else {
            try {
                LocalTerrainProvider.clearPreviewSeed();
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
                resp.put("preview_seed", null);
                sendJson(ex, 200, resp);
            } catch (Exception e) {
                sendError(ex, 400, e.getMessage());
            }
        }
    }

    private static void handleCoarsePng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send405(ex);
        } else {
            try {
                Map<String, String> q = parseQuery(ex.getRequestURI());
                int channel = getInt(q, "channel", 0);
                int ci0 = getInt(q, "ci0", -50);
                int ci1 = getInt(q, "ci1", 50);
                int cj0 = getInt(q, "cj0", -50);
                int cj1 = getInt(q, "cj1", 50);
                float[] data = coarseChannel(ci0, ci1, cj0, cj1, channel);
                int H = ci1 - ci0;
                int W = cj1 - cj0;
                float[] display = (float[]) data.clone();
                if (channel == 4) {
                    for (int i = 0; i < display.length; i++) {
                        display[i] = (float) Math.log1p(Math.max(0.0F, display[i]));
                    }
                }

                float vmin = nanMin(display);
                float vmax = nanMax(display);
                if (vmax == vmin) {
                    vmax = vmin + 1.0F;
                }

                float[][] rgba = new float[4][H * W];

                for (int i = 0; i < H * W; i++) {
                    float t = (display[i] - vmin) / (vmax - vmin);
                    float[] rgb = Colormaps.viridis(clamp01(t));
                    rgba[0][i] = rgb[0];
                    rgba[1][i] = rgb[1];
                    rgba[2][i] = rgb[2];
                    rgba[3][i] = 1.0F;
                }

                int[] filterChs = new int[] {0, 2, 3, 4, 5};
                boolean filterActive = false;

                for (int ch : filterChs) {
                    if (q.containsKey("ch" + ch + "_min") || q.containsKey("ch" + ch + "_max")) {
                        filterActive = true;
                        break;
                    }
                }

                if (filterActive) {
                    boolean[] mask = new boolean[H * W];
                    Arrays.fill(mask, true);

                    for (int ch : filterChs) {
                        Float lo = getFloat(q, "ch" + ch + "_min");
                        Float hi = getFloat(q, "ch" + ch + "_max");
                        if (lo != null || hi != null) {
                            float[] chData = coarseChannel(ci0, ci1, cj0, cj1, ch);

                            for (int i = 0; i < H * W; i++) {
                                if (lo != null && chData[i] < lo) {
                                    mask[i] = false;
                                }

                                if (hi != null && chData[i] > hi) {
                                    mask[i] = false;
                                }
                            }
                        }
                    }

                    for (int i = 0; i < H * W; i++) {
                        if (!mask[i]) {
                            rgba[0][i] = rgba[0][i] * 0.3F;
                            rgba[1][i] = rgba[1][i] * 0.3F;
                            rgba[2][i] = rgba[2][i] * 0.3F;
                        }
                    }
                }

                byte[] png = toPng(rgba, H, W);
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.getResponseHeaders().set("X-Vmin", String.format("%.3f", vmin));
                ex.getResponseHeaders().set("X-Vmax", String.format("%.3f", vmax));
                ex.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Vmin, X-Vmax");
                ex.sendResponseHeaders(200, png.length);
                ex.getResponseBody().write(png);
            } catch (Exception e) {
                LOG.error("coarse.png error", e);
                sendError(ex, 400, e.getMessage());
            } finally {
                ex.close();
            }
        }
    }

    private static void handleCoarseData(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send405(ex);
        } else {
            try {
                Map<String, String> q = parseQuery(ex.getRequestURI());
                int ci0 = getInt(q, "ci0", -50);
                int ci1 = getInt(q, "ci1", 50);
                int cj0 = getInt(q, "cj0", -50);
                int cj1 = getInt(q, "cj1", 50);
                int H = ci1 - ci0;
                int W = cj1 - cj0;
                Map<String, Object> channels = new LinkedHashMap<>();

                for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                    float[] flat = coarseChannel(ci0, ci1, cj0, cj1, ch);
                    channels.put(CHANNEL_NAMES[ch], roundedGrid(flat, H, W, 2));
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ci0", ci0);
                resp.put("ci1", ci1);
                resp.put("cj0", cj0);
                resp.put("cj1", cj1);
                resp.put("channels", channels);
                sendJson(ex, 200, resp);
            } catch (Exception e) {
                sendError(ex, 400, e.getMessage());
            }
        }
    }

    private static void handleCoarseStats(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send405(ex);
        } else {
            try {
                Map<String, String> q = parseQuery(ex.getRequestURI());
                int ci0 = getInt(q, "ci0", -50);
                int ci1 = getInt(q, "ci1", 50);
                int cj0 = getInt(q, "cj0", -50);
                int cj1 = getInt(q, "cj1", 50);
                Map<String, Object> stats = new LinkedHashMap<>();

                for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                    float[] data = coarseChannel(ci0, ci1, cj0, cj1, ch);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", CHANNEL_NAMES[ch]);
                    entry.put("min", round3(nanMin(data)));
                    entry.put("max", round3(nanMax(data)));
                    stats.put(String.valueOf(ch), entry);
                }

                sendJson(ex, 200, stats);
            } catch (Exception e) {
                sendError(ex, 400, e.getMessage());
            }
        }
    }

    private static void handleDetailPng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send405(ex);
        } else {
            try {
                Map<String, String> q = parseQuery(ex.getRequestURI());
                int ci = getInt(q, "ci", 0);
                int cj = getInt(q, "cj", 0);
                int detailSize = getInt(q, "detail_size", 1024);
                int panI = getInt(q, "pan_i", 0);
                int panJ = getInt(q, "pan_j", 0);
                String mode = q.getOrDefault("mode", "relief");
                int centerI = ci * 256 + panI;
                int centerJ = cj * 256 + panJ;
                int half = detailSize / 2;
                float[][] out = LocalTerrainProvider.getPipelineData(
                        centerI - half, centerJ - half, centerI + half, centerJ + half, mode.equals("temperature"));
                float[] elevFlat = out[0];
                float[] climate = out[1];
                int H = detailSize;
                int W = detailSize;
                float[][] rgba;
                if (mode.equals("elevation")) {
                    float vmin = nanMin(elevFlat);
                    float vmax = nanMax(elevFlat);
                    if (vmax == vmin) {
                        vmax = vmin + 1.0F;
                    }

                    rgba = applyColormap1D(elevFlat, H, W, vmin, vmax, "terrain");
                } else if (mode.equals("temperature") && climate != null) {
                    float[] temp = Arrays.copyOfRange(climate, 0, H * W);
                    float vmin = nanMin(temp);
                    float vmax = nanMax(temp);
                    if (vmax == vmin) {
                        vmax = vmin + 1.0F;
                    }

                    rgba = applyColormap1D(temp, H, W, vmin, vmax, "rdbu_r");
                } else {
                    float[][] reliefRgb = ReliefMap.getReliefMap(elevFlat, H, W, 90.0);
                    rgba = new float[4][H * W];

                    for (int i = 0; i < H * W; i++) {
                        rgba[0][i] = reliefRgb[0][i];
                        rgba[1][i] = reliefRgb[1][i];
                        rgba[2][i] = reliefRgb[2][i];
                        rgba[3][i] = 1.0F;
                    }
                }

                byte[] png = toPng(rgba, H, W);
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.sendResponseHeaders(200, png.length);
                ex.getResponseBody().write(png);
            } catch (Exception e) {
                LOG.error("detail.png error", e);
                sendError(ex, 400, e.getMessage());
            } finally {
                ex.close();
            }
        }
    }

    private static void handleDetailRaw(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            send405(ex);
        } else {
            try {
                Map<String, String> q = parseQuery(ex.getRequestURI());
                int ci = getInt(q, "ci", 0);
                int cj = getInt(q, "cj", 0);
                int detailSize = getInt(q, "detail_size", 1024);
                int panI = getInt(q, "pan_i", 0);
                int panJ = getInt(q, "pan_j", 0);
                int centerI = ci * 256 + panI;
                int centerJ = cj * 256 + panJ;
                int half = detailSize / 2;
                int H = detailSize;
                int W = detailSize;
                float[][] out = LocalTerrainProvider.getPipelineData(
                        centerI - half, centerJ - half, centerI + half, centerJ + half, true);
                float[] elevFlat = out[0];
                float[] climate = out[1];
                ByteBuffer elevBuf = ByteBuffer.allocate(H * W * 2).order(ByteOrder.LITTLE_ENDIAN);

                for (float e : elevFlat) {
                    short s = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                    elevBuf.putShort(s);
                }

                boolean hasTemp = climate != null;
                byte[] payload;
                if (!hasTemp) {
                    payload = elevBuf.array();
                } else {
                    ByteBuffer tempBuf = ByteBuffer.allocate(H * W * 4).order(ByteOrder.LITTLE_ENDIAN);

                    for (int i = 0; i < H * W; i++) {
                        tempBuf.putFloat(climate[i]);
                    }

                    payload = new byte[elevBuf.capacity() + tempBuf.capacity()];
                    System.arraycopy(elevBuf.array(), 0, payload, 0, elevBuf.capacity());
                    System.arraycopy(tempBuf.array(), 0, payload, elevBuf.capacity(), tempBuf.capacity());
                }

                ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
                ex.getResponseHeaders().set("X-Height", String.valueOf(H));
                ex.getResponseHeaders().set("X-Width", String.valueOf(W));
                ex.getResponseHeaders().set("X-Has-Temp", hasTemp ? "1" : "0");
                ex.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Height, X-Width, X-Has-Temp");
                ex.sendResponseHeaders(200, payload.length);
                ex.getResponseBody().write(payload);
            } catch (Exception e) {
                LOG.error("detail_raw error", e);
                sendError(ex, 400, e.getMessage());
            } finally {
                ex.close();
            }
        }
    }

    private static float[] coarseChannel(int ci0, int ci1, int cj0, int cj1, int channel) throws Exception {
        FloatTensor slice = LocalTerrainProvider.getPipelineCoarse(ci0, cj0, ci1, cj1);
        int H = ci1 - ci0;
        int W = cj1 - cj0;
        float[] result = new float[H * W];

        for (int i = 0; i < H * W; i++) {
            float w = slice.data[6 * H * W + i];
            float raw = w > 1.0E-8F ? slice.data[channel * H * W + i] / w : 0.0F;
            result[i] = channel <= 1 ? Math.signum(raw) * raw * raw : raw;
        }

        return result;
    }

    private static byte[] toPng(float[][] rgba, int H, int W) throws IOException {
        BufferedImage img = new BufferedImage(W, H, 2);

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                int ri = (int) (clamp01(rgba[0][idx]) * 255.0F + 0.5F);
                int gi = (int) (clamp01(rgba[1][idx]) * 255.0F + 0.5F);
                int bi = (int) (clamp01(rgba[2][idx]) * 255.0F + 0.5F);
                int ai = (int) (clamp01(rgba[3][idx]) * 255.0F + 0.5F);
                img.setRGB(c, r, ai << 24 | ri << 16 | gi << 8 | bi);
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static float[][] applyColormap1D(float[] data, int H, int W, float vmin, float vmax, String cmap) {
        float[][] rgba = new float[4][H * W];

        for (int i = 0; i < H * W; i++) {
            float t = (data[i] - vmin) / (vmax - vmin);

            float[] rgb =
                    switch (cmap) {
                        case "terrain" -> Colormaps.terrain(clamp01(t));
                        case "rdbu_r" -> Colormaps.rdBuR(clamp01(t));
                        default -> Colormaps.viridis(clamp01(t));
                    };
            rgba[0][i] = rgb[0];
            rgba[1][i] = rgb[1];
            rgba[2][i] = rgb[2];
            rgba[3][i] = 1.0F;
        }

        return rgba;
    }

    private static void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] body = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);

        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }

        ex.close();
    }

    private static void sendError(HttpExchange ex, int status, String msg) throws IOException {
        Map<String, String> err = new HashMap<>();
        err.put("error", msg != null ? msg : "unknown error");
        sendJson(ex, status, err);
    }

    private static void send405(HttpExchange ex) throws IOException {
        sendError(ex, 405, "Method Not Allowed");
    }

    private static String readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readNBytes(maxBytes);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf(61);
                if (eq >= 0) {
                    map.put(pair.substring(0, eq), pair.substring(eq + 1));
                } else {
                    map.put(pair, "");
                }
            }

            return map;
        } else {
            return map;
        }
    }

    private static float nanMin(float[] arr) {
        float min = Float.MAX_VALUE;

        for (float v : arr) {
            if (!Float.isNaN(v) && v < min) {
                min = v;
            }
        }

        return min == Float.MAX_VALUE ? 0.0F : min;
    }

    private static float nanMax(float[] arr) {
        float max = -Float.MAX_VALUE;

        for (float v : arr) {
            if (!Float.isNaN(v) && v > max) {
                max = v;
            }
        }

        return max == -Float.MAX_VALUE ? 0.0F : max;
    }

    private static float clamp01(float v) {
        return Math.min(1.0F, Math.max(0.0F, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static List<List<Double>> roundedGrid(float[] flat, int H, int W, int decimals) {
        double factor = Math.pow(10.0, decimals);
        List<List<Double>> grid = new ArrayList<>(H);

        for (int r = 0; r < H; r++) {
            List<Double> row = new ArrayList<>(W);

            for (int c = 0; c < W; c++) {
                row.add(Math.round(flat[r * W + c] * factor) / factor);
            }

            grid.add(row);
        }

        return grid;
    }

    private static int getInt(Map<String, String> q, String key, int def) {
        String v = q.get(key);
        if (v == null) {
            return def;
        }

        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Float getFloat(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null) {
            return null;
        }

        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

package com.github.xandergos.terraindiffusionmc.pipeline;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ModelAssetDownloadTest {
    @TempDir Path directory;

    @Test void decompressesGzipAndBoundsTheInstalledSize() throws Exception {
        byte[] model = {1, 2, 3};
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(model);
        }
        byte[] archive = compressed.toByteArray();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, archive.length);
                exchange.getResponseBody().write(archive);
            }
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            Path file = directory.resolve("asset.tmp");
            ModelAssetManager.downloadBody(uri, file, "test", 3, 2000, 2000, true);
            assertArrayEquals(model, Files.readAllBytes(file));
            assertThrows(IOException.class, () -> ModelAssetManager.downloadBody(uri, file, "test", 2, 2000, 2000, true));
            assertTrue(Files.size(file) <= 2);
        } finally {
            server.stop(0);
        }
    }

    @Test void enforcesManifestSizeForChunkedResponses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[] {1, 2, 3});
            }
        });
        server.start();
        try {
            Path file = directory.resolve("asset.tmp");
            assertThrows(IOException.class, () -> download(server, file, 2, 2000));
            assertTrue(Files.size(file) <= 2);
            assertThrows(IOException.class, () -> download(server, file, 4, 2000));
            download(server, file, 3, 2000);
            assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(file));
        } finally {
            server.stop(0);
        }
    }

    @Test void timesOutWhenBodyStopsAfterHeaders() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            assertThrows(SocketTimeoutException.class, () -> download(server, directory.resolve("asset.tmp"), 2, 300));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static void download(HttpServer server, Path file, long expected, int readTimeout) throws IOException {
        var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        ModelAssetManager.downloadBody(uri, file, "test", expected, 2000, readTimeout, false);
    }
}

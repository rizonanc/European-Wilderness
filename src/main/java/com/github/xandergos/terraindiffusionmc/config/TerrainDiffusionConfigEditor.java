package com.github.xandergos.terraindiffusionmc.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TerrainDiffusionConfigEditor {
    public static final List<String> KEYS = TerrainSettingsCatalog.KEYS;
    private static final Pattern ASSIGNMENT = Pattern.compile("^(\\s*)([a-zA-Z0-9._-]+)\\s*[=:].*$");
    private static final String RESOURCE = TerrainDiffusionConfig.RESOURCE_PATH;

    private TerrainDiffusionConfigEditor() {}

    public static Map<String, String> load(Path path) throws IOException {
        Properties properties = new Properties();

        try (InputStream resource =
                TerrainDiffusionConfigEditor.class.getResourceAsStream(TerrainDiffusionConfig.RESOURCE_PATH)) {
            if (resource == null) {
                throw new IOException("Missing bundled config " + TerrainDiffusionConfig.RESOURCE_PATH);
            }

            properties.load(resource);
        }

        if (path != null && Files.exists(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
        }

        Map<String, String> draft = new LinkedHashMap<>();

        for (String key : KEYS) {
            String value = properties.getProperty(key);
            if (value == null) {
                throw new IOException("Missing bundled setting " + key);
            }

            draft.put(key, value.trim().toLowerCase(Locale.ROOT));
        }

        return draft;
    }

    public static Map<String, String> loadDefaults() throws IOException {
        return load(null);
    }

    public static String validate(Map<String, String> draft) {
        for (String key : KEYS)
            if (TerrainSettingsCatalog.BOOLEANS.contains(key) && !oneOf(draft, key, "true", "false")) return key;
        for (String key : KEYS) {
            var percent = TerrainSettingsCatalog.PERCENTAGES.get(key);
            if (percent != null && !integerInRange(draft, key, percent.minimum(), percent.maximum())) return key;
        }
        if (!integerInRange(draft, "inference.gpu_session_cache", 1, 3)) return "inference.gpu_session_cache";
        if (!oneOf(draft, "terrain.climate", "temperate", "cool", "cold")) return "terrain.climate";
        if (!oneOf(draft, "performance.water_prefetch", "off", "adaptive")) return "performance.water_prefetch";
        if (!oneOf(draft, "compat.voxy.radius_mode", "auto", "manual")) return "compat.voxy.radius_mode";
        if (!oneOf(draft, "compat.voxy.source", "chunks", "prepared")) return "compat.voxy.source";
        if (!integerInRange(draft, "compat.voxy.radius_blocks", 64, 32768)) return "compat.voxy.radius_blocks";
        if (!integerInRange(draft, "compat.voxy.detail_radius_blocks", 256, 8192))
            return "compat.voxy.detail_radius_blocks";
        if (!oneOf(draft, "inference.device", "gpu", "cpu", "auto")) {
            return "inference.device";
        } else if (!oneOf(draft, "inference.offload_models", "true", "false")) {
            return "inference.offload_models";
        } else if (!oneOf(draft, "validate_model", "true", "false")) {
            return "validate_model";
        } else if (!integerInRange(draft, "explorer.port", 1, 65535)) {
            return "explorer.port";
        } else {
            int tileSize = parseInteger(draft.get("tile_size"));
            if (tileSize < 16 || tileSize > 1024 || (tileSize & tileSize - 1) != 0) {
                return "tile_size";
            } else if (!integerInRange(draft, "spawn_search.initial_size", 1, 1024)) {
                return "spawn_search.initial_size";
            } else if (!integerInRange(draft, "spawn_search.max_size", 1, 1024)
                    || parseInteger(draft.get("spawn_search.max_size"))
                            < parseInteger(draft.get("spawn_search.initial_size"))) {
                return "spawn_search.max_size";
            } else if (!oneOf(draft, "preparation.mode", "on_demand", "prepared_only")) {
                return "preparation.mode";
            } else if (!integerInRange(draft, "performance.disk_quota_mb", 1024, 1048576)) {
                return "performance.disk_quota_mb";
            } else if (!oneOf(draft, "performance.prefetch", "off", "adaptive")) {
                return "performance.prefetch";
            } else {
                return oneOf(draft, "performance.prefetch", "adaptive")
                                && !oneOf(draft, "preparation.mode", "on_demand")
                        ? "performance.prefetch"
                        : null;
            }
        }
    }

    public static void save(Path path, Map<String, String> draft) throws IOException {
        String invalid = validate(draft);
        if (invalid != null) {
            throw new IllegalArgumentException("Invalid setting: " + invalid);
        }

        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Config path has no parent: " + path);
        }

        Files.createDirectories(parent);
        String existing =
                Files.exists(absolute) ? Files.readString(absolute, StandardCharsets.ISO_8859_1) : bundledTemplate();
        String newline = existing.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = existing.split("\\r?\\n", -1);
        StringBuilder updated = new StringBuilder();
        Set<String> seen = new HashSet<>();
        int lineCount = lines.length - (existing.endsWith("\n") ? 1 : 0);

        for (int i = 0; i < lineCount; i++) {
            String line = lines[i];
            Matcher match = ASSIGNMENT.matcher(line);
            if (match.matches() && draft.containsKey(match.group(2))) {
                String key = match.group(2);
                updated.append(match.group(1))
                        .append(key)
                        .append('=')
                        .append(draft.get(key).trim());
                seen.add(key);
            } else {
                updated.append(line);
            }

            updated.append(newline);
        }

        for (String key : KEYS) {
            if (!seen.contains(key)) {
                updated.append(key).append('=').append(draft.get(key).trim()).append(newline);
            }
        }

        Path temporary = Files.createTempFile(parent, "terrain-diffusion-mc-", ".tmp");

        try {
            Files.writeString(temporary, updated.toString(), StandardCharsets.ISO_8859_1);

            try {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean oneOf(Map<String, String> draft, String key, String... accepted) {
        String raw = draft.get(key);
        if (raw == null) {
            return false;
        }

        for (String value : accepted) {
            if (value.equalsIgnoreCase(raw.trim())) {
                return true;
            }
        }

        return false;
    }

    private static String bundledTemplate() throws IOException {
        try (InputStream resource =
                TerrainDiffusionConfigEditor.class.getResourceAsStream(TerrainDiffusionConfig.RESOURCE_PATH)) {
            if (resource == null) {
                throw new IOException("Missing bundled config " + TerrainDiffusionConfig.RESOURCE_PATH);
            } else {
                return new String(resource.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
        }
    }

    private static boolean integerInRange(Map<String, String> draft, String key, int min, int max) {
        int value = parseInteger(draft.get(key));
        return value >= min && value <= max;
    }

    private static int parseInteger(String raw) {
        if (raw == null) {
            return -1;
        }

        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }
}

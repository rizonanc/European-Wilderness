package com.github.xandergos.terraindiffusionmc.config;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.PerformanceProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

public final class TerrainDiffusionConfig {
    private static final String FILE_NAME = "european-wilderness.properties";
    static final String RESOURCE_PATH = "/" + FILE_NAME;
    private static final Properties PROPERTIES = new Properties();
    private static final String BUILD_VARIANT = readBuildVariant();
    private static final boolean DEFAULT_OFFLOAD_MODELS = true;
    private static final boolean DEFAULT_VALIDATE_MODEL = true;
    private static final int DEFAULT_EXPLORER_PORT = 19801;
    private static final int DEFAULT_TILE_SIZE = 256;

    private TerrainDiffusionConfig() {}

    public static String inferenceDevice() {
        String device = readString("inference.device", "gpu");
        return "cpu".equals(BUILD_VARIANT) ? "auto" : device;
    }

    public static boolean offloadModels() {
        return readBoolean("inference.offload_models", true);
    }

    public static int gpuSessionCacheSize() {
        return Math.max(1, Math.min(3, readInt("inference.gpu_session_cache", 3)));
    }

    public static int explorerPort() {
        return readInt("explorer.port", 19801);
    }

    public static boolean validateModel() {
        return readBoolean("validate_model", true);
    }

    public static int spawnSearchInitialSize() {
        return readInt("spawn_search.initial_size", 16);
    }

    public static int spawnSearchMaxSize() {
        return readInt("spawn_search.max_size", 128);
    }

    public static PerformanceProfile.PreparationMode preparationMode() {
        return parsePreparationMode(PROPERTIES.getProperty("preparation.mode"));
    }

    public static TerrainDiffusionConfig.PrefetchMode prefetchMode() {
        return parsePrefetchMode(PROPERTIES.getProperty("performance.prefetch"));
    }

    public static boolean waterPrefetchEnabled() {
        String value = PROPERTIES.getProperty("performance.water_prefetch", "adaptive");
        return parsePrefetchMode(value) == PrefetchMode.ADAPTIVE;
    }

    /** Enables bounded background writes for frozen water plans. */
    public static boolean waterWriteBehind() {
        return readBoolean("performance.water_write_behind", false);
    }

    public static boolean voxyEnabled() {
        return readBoolean("compat.voxy.enabled", true);
    }

    public static boolean voxyTrees() {
        return readBoolean("compat.voxy.trees", true);
    }

    /** Old coarse previews are retired; legacy true values cannot reactivate them. */
    public static boolean voxyCoarse() {
        return false;
    }

    public static boolean voxyNativeChunks() {
        return !"prepared".equals(readString("compat.voxy.source", "chunks"));
    }

    public static int voxyDetailRadius() {
        return Math.max(256, Math.min(8192, readInt("compat.voxy.detail_radius_blocks", 2048)));
    }

    public static boolean voxyPrepareAhead() {
        return readBoolean("compat.voxy.prepare_ahead", true);
    }

    public static boolean worldGenCompat() {
        return readBoolean("compat.voxy_worldgen.enabled", true);
    }

    public static boolean roadWeaverEnabled() {
        return readBoolean("compat.roadweaver.enabled", true);
    }

    public static boolean enabled(String key) {
        return readBoolean(key, true);
    }

    public static String climate() {
        return readString("terrain.climate", "temperate");
    }

    public static boolean mountainRivers() {
        return readBoolean("rivers.mountain_rivers", false);
    }

    public static int percent(String key) {
        var spec = TerrainSettingsCatalog.PERCENTAGES.get(key);
        return Math.clamp(readInt(key, spec.defaultValue()), spec.minimum(), spec.maximum());
    }

    public static int voxyRadius() {
        return Math.max(64, Math.min(32768, readInt("compat.voxy.radius_blocks", 1024)));
    }

    public static boolean voxyManualRadius() {
        return "manual".equals(readString("compat.voxy.radius_mode", "auto"));
    }

    /** Enables a renderer-only coarse horizon without Voxy storage writes. */
    public static boolean voxyCoarseExperimental() {
        return readBoolean("compat.voxy.coarse_experimental", false);
    }

    static TerrainDiffusionConfig.PrefetchMode parsePrefetchMode(String configured) {
        if (configured == null || configured.trim().equalsIgnoreCase("off")) {
            return TerrainDiffusionConfig.PrefetchMode.OFF;
        }

        if (configured.trim().equalsIgnoreCase("adaptive")) {
            return TerrainDiffusionConfig.PrefetchMode.ADAPTIVE;
        }

        System.err.println("Invalid performance.prefetch: " + configured + ", using default off");
        return TerrainDiffusionConfig.PrefetchMode.OFF;
    }

    static PerformanceProfile.PreparationMode parsePreparationMode(String configured) {
        String mode = configured == null ? "on_demand" : configured.trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("prepared_only") && !mode.equals("prepared-only") && !mode.equals("preparedonly")) {
            if (!mode.equals("on_demand") && !mode.equals("on-demand") && !mode.equals("ondemand")) {
                System.err.println("Invalid preparation.mode: " + mode + ", using default on_demand");
            }

            return PerformanceProfile.PreparationMode.ON_DEMAND;
        } else {
            return PerformanceProfile.PreparationMode.PREPARED_ONLY;
        }
    }

    public static int diskQuotaMb() {
        return validatedDiskQuotaMb(PROPERTIES.getProperty("performance.disk_quota_mb"));
    }

    static int validatedDiskQuotaMb(String configured) {
        int fallback = PerformanceProfile.defaults().diskQuotaMb();
        if (configured == null) {
            return fallback;
        }

        long parsed;
        try {
            parsed = Long.parseLong(configured.trim());
        } catch (NumberFormatException invalid) {
            System.err.println("Invalid performance.disk_quota_mb: " + configured + ", using default " + fallback);
            return fallback;
        }

        if (parsed >= 1024L && parsed <= 1048576L) {
            return (int) parsed;
        }

        System.err.println("Invalid performance.disk_quota_mb: "
                + configured
                + " (expected 1024..1048576), using default "
                + fallback);
        return fallback;
    }

    public static int tileSize() {
        int configuredTileSize = readInt("tile_size", 256);
        if (configuredTileSize > 0 && isPowerOfTwo(configuredTileSize)) {
            return configuredTileSize;
        }

        System.err.println("Invalid tile_size: " + configuredTileSize + ", using default 256");
        return 256;
    }

    private static void loadDefaults() {
        boolean loadedFromResource = false;

        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) {
                PROPERTIES.load(in);
                loadedFromResource = true;
            }
        } catch (IOException e) {
            System.err.println("Failed to load default config from resource: " + e.getMessage());
        }

        if (!loadedFromResource) {
            PROPERTIES.setProperty("inference.device", "gpu");
            PROPERTIES.setProperty("validate_model", String.valueOf(true));
            PROPERTIES.setProperty("tile_size", String.valueOf(256));
        }
    }

    private static String readString(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? value.trim().toLowerCase() : defaultValue;
    }

    public static Path configPath() {
        return migrateConfig(FabricLoader.getInstance().getConfigDir(), FILE_NAME, "terrain-diffusion-mc.properties");
    }

    public static Path menuPath() {
        return migrateConfig(
                FabricLoader.getInstance().getConfigDir(),
                "european-wilderness-menu.json",
                "terrain-diffusion-menu.json");
    }

    static synchronized Path migrateConfig(Path directory, String name, String legacyName) {
        Path current = directory.resolve(name);
        Path legacy = directory.resolve(legacyName);
        if (Files.exists(current) || !Files.exists(legacy)) return current;
        try {
            Path staged = Files.createTempFile(directory, "european-wilderness-", ".tmp");
            try {
                Files.copy(legacy, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(staged, current);
            } finally {
                Files.deleteIfExists(staged);
            }
            return current;
        } catch (java.nio.file.FileAlreadyExistsException otherWriter) {
            return current;
        } catch (IOException failure) {
            System.err.println("European Wilderness could not migrate " + legacy + ": " + failure.getMessage());
            return legacy;
        }
    }

    private static void loadOverrides(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    Properties overrides = new Properties();
                    overrides.load(in);
                    PROPERTIES.putAll(overrides);
                }
            } else {
                writeConfig(configPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to read config file: " + e.getMessage());
        }
    }

    private static void writeConfig(Path configPath) {
        try (InputStream defaultConfigInputStream = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (defaultConfigInputStream == null) {
                System.err.println("Default config resource not found: " + RESOURCE_PATH);
            } else {
                Files.copy(defaultConfigInputStream, configPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to copy default config resource: " + e.getMessage());
        }
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? Boolean.parseBoolean(value.trim()) : defaultValue;
    }

    private static int readInt(String key, int defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid int for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return (value & value - 1) == 0;
    }

    private static String readBuildVariant() {
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream("/build-variant.properties")) {
            if (in == null) {
                return "unknown";
            }

            Properties props = new Properties();
            props.load(in);
            return props.getProperty("build.variant", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    static {
        loadDefaults();
        try {
            loadOverrides(configPath());
        } catch (RuntimeException failure) {
            System.err.println("Fabric Loader config directory unavailable: " + failure.getMessage());
        }
    }

    public enum PrefetchMode {
        OFF,
        ADAPTIVE;
    }
}

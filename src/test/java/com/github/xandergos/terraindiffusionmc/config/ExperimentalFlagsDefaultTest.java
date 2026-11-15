package com.github.xandergos.terraindiffusionmc.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExperimentalFlagsDefaultTest {
    @Test
    void phase4FlagsAreOffUnderBundledDefaults() {
        assertFalse(TerrainDiffusionConfig.voxyCoarseExperimental(), "coarse_experimental default");
        assertFalse(TerrainDiffusionConfig.waterWriteBehind(), "water_write_behind default");
    }

    @Test
    void legacyKeysKeepTheirPhase3Values() {
        assertFalse(TerrainDiffusionConfig.voxyCoarse(), "legacy coarse stays retired");
        assertTrue(TerrainDiffusionConfig.voxyNativeChunks(), "default source stays real chunks");
        assertEquals(1024, TerrainDiffusionConfig.voxyRadius());
        assertEquals(2048, TerrainDiffusionConfig.voxyDetailRadius());
        assertTrue(TerrainDiffusionConfig.voxyEnabled());
        assertFalse(TerrainDiffusionConfig.voxyManualRadius());
    }

    @Test
    void catalogueContainsExactlyTheBundledKeysWithNewFlagsPresent() throws Exception {
        var bundled = new java.util.Properties();
        try (var input = getClass().getResourceAsStream("/european-wilderness.properties")) {
            bundled.load(input);
        }
        assertTrue(bundled.containsKey("compat.voxy.coarse_experimental"));
        assertTrue(bundled.containsKey("performance.water_write_behind"));
        assertEquals("false", bundled.getProperty("compat.voxy.coarse_experimental"));
        assertEquals("false", bundled.getProperty("performance.water_write_behind"));
        assertTrue(TerrainSettingsCatalog.KEYS.containsAll(bundled.stringPropertyNames()));
    }

    @Test
    void coarseMixinPluginRefusesWhileFlagIsOff() {
        var plugin = new com.github.xandergos.terraindiffusionmc.mixin.compat.CoarseVoxyMixinPlugin();
        assertFalse(
                plugin.shouldApplyMixin(
                        "me.cortex.voxy.client.core.rendering.building.RenderGenerationService",
                        "com.github.xandergos.terraindiffusionmc.mixin.compat.CoarseRenderSectionMixin"));
    }
}

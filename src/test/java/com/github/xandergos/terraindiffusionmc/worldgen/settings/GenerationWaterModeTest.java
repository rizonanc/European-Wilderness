package com.github.xandergos.terraindiffusionmc.worldgen.settings;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

class GenerationWaterModeTest {
    @Test
    void newLandformsArePinnedToTheWorldProfile() {
        var json = GenerationProfileState.CODEC
                .encodeStart(JsonOps.INSTANCE, GenerationProfileState.createDefault())
                .getOrThrow()
                .getAsJsonObject();
        var current = GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, json)
                .getOrThrow()
                .toProfile();
        json.remove("new_landforms");
        var previous = GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, json)
                .getOrThrow()
                .toProfile();
        assertTrue(current.newLandforms());
        assertFalse(previous.newLandforms());
        assertNotEquals(current.fingerprint(), previous.fingerprint());
    }

    @Test
    void newWorldEnablesAutomaticWaterAndRoundTripsTheMode() {
        var state = GenerationProfileState.createDefault();
        assertTrue(state.automaticWater());
        var encoded = GenerationProfileState.CODEC
                .encodeStart(JsonOps.INSTANCE, state)
                .getOrThrow();
        assertTrue(GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow()
                .automaticWater());
    }

    @Test
    void testLegacyWaterProfile() {
        var old = JsonParser.parseString("""
            {"format_version":2,"generator_version":8,"realism_level":7,"world_scale":2,
             "region":"europe","model_fingerprint":"model","inference_device":"cpu","created_backend":"cpu"}
            """);
        var state = GenerationProfileState.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();
        assertFalse(state.automaticWater());
        assertEquals(
                GenerationProfile.defaultsFor(2, "model", "cpu", "cpu").fingerprint(),
                state.toProfile().fingerprint());
    }

    @Test
    void testTerrainPreferencesRoundTrip() {
        var json = GenerationProfileState.CODEC
                .encodeStart(JsonOps.INSTANCE, GenerationProfileState.createDefault())
                .getOrThrow()
                .getAsJsonObject();
        var original = GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, json)
                .getOrThrow()
                .toProfile();
        json.addProperty("mountain_relief", 175);
        json.addProperty("biome_variety", 55);
        var state = GenerationProfileState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        var saved = GenerationProfileState.CODEC
                .encodeStart(JsonOps.INSTANCE, state)
                .getOrThrow();
        var restored = GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, saved)
                .getOrThrow()
                .toProfile();
        assertEquals(175, restored.mountainRelief());
        assertEquals(55, restored.biomeVariety());
        assertEquals(original.worldScale(), restored.worldScale());
        assertNotEquals(original.fingerprint(), restored.fingerprint());
    }

    @Test
    void testLegacyWaterOptionsAreFrozenOnce() {
        var json = GenerationProfileState.CODEC
                .encodeStart(JsonOps.INSTANCE, GenerationProfileState.createDefault())
                .getOrThrow()
                .getAsJsonObject();
        json.remove("water_generation");
        var old = GenerationProfileState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        var fingerprint = old.toProfile().fingerprint();
        var options = new WaterGenerationOptions(140, 125, 70);
        old.bindLegacyWaterOptions(options);
        var saved =
                GenerationProfileState.CODEC.encodeStart(JsonOps.INSTANCE, old).getOrThrow();
        var restored =
                GenerationProfileState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        restored.bindLegacyWaterOptions(WaterGenerationOptions.DEFAULT);
        assertEquals(options, restored.toProfile().water());
        assertEquals(fingerprint, restored.toProfile().fingerprint());
    }

    @Test
    void testLegacyBiomeCacheIdentity() {
        var fresh = GenerationProfileState.CODEC
                .encodeStart(JsonOps.INSTANCE, GenerationProfileState.createDefault())
                .getOrThrow()
                .getAsJsonObject();
        assertTrue(fresh.has("larger_landscapes"));
        assertTrue(fresh.get("larger_landscapes").getAsBoolean());
        var newFingerprint = GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, fresh)
                .getOrThrow()
                .toProfile()
                .fingerprint();
        assertTrue(fresh.get("tropical_landforms").getAsBoolean());
        assertTrue(fresh.remove("large_islands").getAsBoolean());
        assertEquals(0, fresh.remove("climate_shift").getAsInt());
        assertTrue(fresh.remove("slope_climate").getAsBoolean());
        assertTrue(fresh.remove("new_landforms").getAsBoolean());
        var previous = GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, fresh)
                .getOrThrow()
                .toProfile();
        assertTrue(previous.tropicalLandforms());
        assertFalse(previous.largeIslands());
        fresh.remove("tropical_landforms");
        fresh.remove("larger_landscapes");
        var old = GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, fresh)
                .getOrThrow()
                .toProfile();
        assertNotEquals(newFingerprint, old.fingerprint());
        assertEquals(
                GenerationProfile.defaultsFor(
                                old.worldScale(), old.modelFingerprint(), old.inferenceDevice(), old.createdBackend())
                        .fingerprint(),
                old.fingerprint());
    }

    @Test
    void testBiomeBalanceIsStoredPerWorld() {
        var neutral = GenerationProfileState.createDefault().toProfile();
        var settings = WorldGenerationOptions.fromSettings(
                java.util.Map.of("terrain.ocean_amount", "175", "terrain.desert_amount", "25"));
        var encodedOptions = WorldGenerationOptions.CODEC
                .encodeStart(JsonOps.INSTANCE, settings)
                .getOrThrow();
        var decodedOptions = WorldGenerationOptions.CODEC
                .parse(JsonOps.INSTANCE, encodedOptions)
                .getOrThrow();
        GenerationProfileState.offerDefaults(
                neutral.worldScale(),
                neutral.modelFingerprint(),
                neutral.inferenceDevice(),
                neutral.createdBackend(),
                decodedOptions);
        var saved = GenerationProfileState.CODEC
                .encodeStart(JsonOps.INSTANCE, GenerationProfileState.createDefault())
                .getOrThrow();
        var restored = GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, saved)
                .getOrThrow()
                .toProfile();
        assertEquals(175, restored.biomeBalance().oceans());
        assertEquals(25, restored.biomeBalance().deserts());
        assertNotEquals(neutral.fingerprint(), restored.fingerprint());
    }

    @Test
    void testMountainRiversDefaultOffForNewWorlds() throws Exception {
        var defaults = com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfigEditor.loadDefaults();
        var options = WorldGenerationOptions.fromSettings(defaults);
        assertFalse(options.water().mountainRivers());
        var saved = WaterGenerationOptions.CODEC
                .encodeStart(JsonOps.INSTANCE, options.water())
                .getOrThrow()
                .getAsJsonObject();
        assertFalse(WaterGenerationOptions.CODEC
                .parse(JsonOps.INSTANCE, saved)
                .getOrThrow()
                .mountainRivers());
        saved.remove("mountain_rivers");
        assertTrue(WaterGenerationOptions.CODEC
                .parse(JsonOps.INSTANCE, saved)
                .getOrThrow()
                .mountainRivers());
        defaults.put("rivers.mountain_rivers", "true");
        assertTrue(WorldGenerationOptions.fromSettings(defaults).water().mountainRivers());
    }

    @Test
    void testRegionalPresetClimatePersists() {
        var preset = com.github.xandergos.terraindiffusionmc.config.SettingsPresets.ALL.stream()
                .filter(candidate -> candidate.id().equals("scandinavia"))
                .findFirst()
                .orElseThrow();
        var options = WorldGenerationOptions.fromSettings(preset.values());
        assertEquals(-7, options.climateShift());
        var creation = WorldGenerationOptions.CODEC
                .encodeStart(JsonOps.INSTANCE, options)
                .getOrThrow();
        assertEquals(
                options,
                WorldGenerationOptions.CODEC.parse(JsonOps.INSTANCE, creation).getOrThrow());
        GenerationProfileState.offerDefaults(2, "test", "cpu", "cpu", options);
        var state = GenerationProfileState.createDefault();
        var encoded = GenerationProfileState.CODEC
                .encodeStart(JsonOps.INSTANCE, state)
                .getOrThrow()
                .getAsJsonObject();
        var restored = GenerationProfileState.CODEC
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow()
                .toProfile();
        assertEquals(java.util.Optional.of(-7), restored.climateShift());
        encoded.remove("climate_shift");
        assertNotEquals(
                restored.fingerprint(),
                GenerationProfileState.CODEC
                        .parse(JsonOps.INSTANCE, encoded)
                        .getOrThrow()
                        .toProfile()
                        .fingerprint());
    }
}

package com.github.xandergos.terraindiffusionmc.config;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettingsEditorTest {
    @TempDir Path temp;
    private Map<String,String> defaults() throws Exception{return TerrainDiffusionConfigEditor.load(temp.resolve("missing.properties"));}
    @Test void catalogueExposesEveryBundledPropertyExactlyOnceIncludingLegacyConfigDefaults() throws Exception {
        var bundled=new Properties();try(var input=getClass().getResourceAsStream("/terrain-diffusion-mc.properties")){bundled.load(input);}
        assertEquals(bundled.stringPropertyNames(),new HashSet<>(TerrainSettingsCatalog.KEYS));
        assertEquals(TerrainSettingsCatalog.KEYS.size(),new HashSet<>(TerrainSettingsCatalog.KEYS).size());
        var legacy=temp.resolve("legacy.properties");Files.writeString(legacy,"inference.device=auto\nperformance.prefetch=off\n");
        var draft=TerrainDiffusionConfigEditor.load(legacy);assertNull(TerrainDiffusionConfigEditor.validate(draft));
        assertEquals("3",draft.get("inference.gpu_session_cache"));assertEquals("auto",draft.get("compat.voxy.radius_mode"));
        assertEquals("true",draft.get("compat.roadweaver.enabled"));
        for(String key:draft.keySet())assertTrue(TerrainSettingsCatalog.PAGES.get(TerrainSettingsCatalog.pageFor(key)).keys().contains(key));
    }
    @Test void allNewControlsRoundTripPreservingCommentsUnknownOptionsAndCrLf() throws Exception {
        Path path=temp.resolve("settings.properties");String original="# keep comment\r\ncustom.unknown=KeepCase\r\ninference.device=auto\r\n";Files.writeString(path,original);
        var draft=TerrainDiffusionConfigEditor.load(path);
        for(String key:TerrainSettingsCatalog.BOOLEANS)draft.put(key,"false");
        TerrainSettingsCatalog.PERCENTAGES.forEach((key,range)->draft.put(key,Integer.toString(range.maximum())));
        draft.put("inference.gpu_session_cache","2");draft.put("performance.water_prefetch","off");
        draft.put("compat.voxy.radius_mode","manual");draft.put("compat.voxy.radius_blocks","32768");draft.put("compat.voxy.detail_radius_blocks","4096");
        TerrainDiffusionConfigEditor.save(path,draft);assertEquals(draft,TerrainDiffusionConfigEditor.load(path));
        String saved=Files.readString(path);assertTrue(saved.startsWith("# keep comment\r\ncustom.unknown=KeepCase\r\n"));
        assertFalse(saved.replace("\r\n","").contains("\n"));
        TerrainDiffusionConfigEditor.save(path,draft);assertEquals(saved,Files.readString(path));
    }
    @Test void newRangesAndChoicesRejectInvalidValuesAndKeepValidBoundaries() throws Exception {
        Map<String,String[]> invalid=Map.of("inference.gpu_session_cache",new String[]{"0","4","x"},
            "compat.voxy.radius_blocks",new String[]{"63","32769"},"compat.voxy.detail_radius_blocks",new String[]{"255","8193"},
            "compat.voxy.radius_mode",new String[]{"512","invalid"},"performance.water_prefetch",new String[]{"true"});
        for(var entry:invalid.entrySet())for(String value:entry.getValue()){var draft=defaults();draft.put(entry.getKey(),value);assertEquals(entry.getKey(),TerrainDiffusionConfigEditor.validate(draft));}
        for(String key:TerrainSettingsCatalog.BOOLEANS){var draft=defaults();draft.put(key,"maybe");assertEquals(key,TerrainDiffusionConfigEditor.validate(draft));}
        for(var entry:TerrainSettingsCatalog.PERCENTAGES.entrySet()){
            var draft=defaults();
            draft.put(entry.getKey(),Integer.toString(entry.getValue().minimum()-1));
            assertEquals(entry.getKey(),TerrainDiffusionConfigEditor.validate(draft));
            draft.put(entry.getKey(),Integer.toString(entry.getValue().maximum()+1));
            assertEquals(entry.getKey(),TerrainDiffusionConfigEditor.validate(draft));
        }
        var valid=defaults();valid.put("inference.gpu_session_cache","1");valid.put("compat.voxy.radius_blocks","64");valid.put("compat.voxy.detail_radius_blocks","8192");assertNull(TerrainDiffusionConfigEditor.validate(valid));
    }
    @Test void invalidSaveLeavesExistingConfigUntouched() throws Exception {
        var path=temp.resolve("config.properties");Files.writeString(path,"# original\ncustom.unknown=keep\n");byte[] original=Files.readAllBytes(path);
        var invalid=defaults();invalid.put("compat.voxy.radius_blocks","999999");
        assertThrows(IllegalArgumentException.class,()->TerrainDiffusionConfigEditor.save(path,invalid));assertArrayEquals(original,Files.readAllBytes(path));
    }
    @Test void allControlsChoicesAndPagesHaveEnglishAndRussianHelp() throws Exception {
        for(String language:List.of("en_us","ru_ru"))try(var input=getClass().getResourceAsStream("/assets/terrain-diffusion-mc/lang/"+language+".json")) {
            var json=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();String prefix="terrain-diffusion-mc.settings.";
            for(String key:TerrainSettingsCatalog.KEYS){assertTrue(json.has(prefix+"field."+key),language+" "+key);assertTrue(json.has(prefix+"tooltip."+key),language+" tooltip "+key);
                var choices=TerrainSettingsCatalog.choices(key);if(choices!=null)for(String choice:choices)assertTrue(json.has(prefix+"value."+choice));}
            for(var page:TerrainSettingsCatalog.PAGES){assertTrue(json.has(prefix+"page."+page.name()));assertTrue(json.has(prefix+"bounds."+page.name()));}
            for(String status:List.of("roadweaver.missing","roadweaver.supported","roadweaver.unsupported",
                    "mod_missing","mod_version","water.new_world","water.automatic","water.prepared"))
                assertTrue(json.has(prefix+status),language+" "+status);
            for(String action:List.of("open_voxy","open_iris","photon_clear","photon_normal")){assertTrue(json.has(prefix+action));assertTrue(json.has(prefix+action+".tip"));}
        }
    }
    @Test void resettingOneSectionAndApplyingAProfileCanBeUndone() throws Exception {
        var defaults = defaults();
        var draft = new SettingsDraft(defaults, defaults);
        draft.apply(Map.of("rivers.depth_variation", "125", "terrain.mountain_relief", "175"));
        draft.reset(TerrainSettingsCatalog.PAGES.get(TerrainSettingsCatalog.pageFor("rivers.depth_variation")).keys());
        assertEquals(List.of("terrain.mountain_relief"), draft.changedKeys());
        draft.undo();
        assertEquals("125", draft.get("rivers.depth_variation"));
        draft.undo();
        assertEquals(defaults, draft.values());
    }

    @Test void twoToggleClicksCanBeUndoneIndividually() throws Exception {
        var defaults = defaults();
        var draft = new SettingsDraft(defaults, defaults);
        String key = "rivers.boats";
        String original = defaults.get(key);
        String toggled = Boolean.toString(!Boolean.parseBoolean(original));
        draft.edit(key, toggled, false);
        draft.edit(key, original, false);
        assertFalse(draft.changed(key));
        draft.undo();
        assertEquals(toggled, draft.get(key));
        draft.undo();
        assertEquals(original, draft.get(key));
    }

    @Test void menuPreferencesPersistIndependentOfTheGameConfig() throws Exception {
        var path = temp.resolve("menu.json");
        var prefs = SettingsPreferences.load(path);
        prefs.toggleFavorite("terrain.mountain_relief");
        prefs.recordRecent(List.of("rivers.depth_variation", "terrain.mountain_relief"));
        prefs.recordRecent(List.of("terrain.mountain_relief"));
        prefs.saveProfile("Mountains", defaults());
        var reloaded = SettingsPreferences.load(path);
        assertTrue(reloaded.favorite("terrain.mountain_relief"));
        assertEquals(List.of("terrain.mountain_relief", "rivers.depth_variation"), reloaded.recent());
        assertEquals(defaults(), reloaded.profiles().get("Mountains"));
        reloaded.deleteProfile("Mountains");
        assertTrue(SettingsPreferences.load(path).profiles().isEmpty());
        assertFalse(Files.exists(temp.resolve("missing.properties")));
    }

    @Test void photonFogOnlyChangesItsTwoOptionsAndKeepsOtherCustomizations() {
        Map<String,String> queue=new LinkedHashMap<>(Map.of("COLORED_LIGHTS","true","NETHER_FOG_INTENSITY","1.50","CUSTOM_USER_OPTION","keep"));
        PhotonFogPreset.queue(queue,true);assertEquals("0.10",queue.get("OVERWORLD_FOG_INTENSITY"));assertEquals("false",queue.get("BORDER_FOG"));
        assertEquals("1.50",queue.get("NETHER_FOG_INTENSITY"));assertEquals("true",queue.get("COLORED_LIGHTS"));assertEquals("keep",queue.get("CUSTOM_USER_OPTION"));
        PhotonFogPreset.queue(queue,false);assertEquals("1.00",queue.get("OVERWORLD_FOG_INTENSITY"));assertEquals("true",queue.get("BORDER_FOG"));assertEquals(5,queue.size());
    }
}

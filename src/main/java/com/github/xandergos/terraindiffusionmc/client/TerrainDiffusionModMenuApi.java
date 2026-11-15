package com.github.xandergos.terraindiffusionmc.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
public final class TerrainDiffusionModMenuApi implements ModMenuApi {
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TerrainDiffusionSettingsScreen::new;
    }
}

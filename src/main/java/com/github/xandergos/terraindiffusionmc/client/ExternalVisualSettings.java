package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.compat.voxy.Voxy016Api;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;

/** Access to Voxy settings and the current shader pack name. */
public final class ExternalVisualSettings {
    public record VoxyState(boolean present, boolean enabled, boolean rendering, int chunks) {}

    public static VoxyState voxyState() {
        try {
            if (!Voxy016Api.runtimeReady()) return new VoxyState(true, false, false, 0);
            Class<?> type = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            Object cfg = type.getField("CONFIG").get(null);
            return new VoxyState(
                    true,
                    type.getField("enabled").getBoolean(cfg),
                    type.getField("enableRendering").getBoolean(cfg),
                    Voxy016Api.horizonBlocks(
                                    type.getField("sectionRenderDistance").getFloat(cfg))
                            / 16);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return new VoxyState(false, false, false, 0);
        }
    }

    public static Screen voxy(Screen parent) throws ReflectiveOperationException {
        var integration = (ModMenuApi) Class.forName("me.cortex.voxy.client.config.ModMenuIntegration")
                .getConstructor()
                .newInstance();
        Screen screen = integration.getModConfigScreenFactory().create(parent);
        if (screen == null) throw new IllegalStateException("Voxy settings are unavailable");
        return screen;
    }

    public static String shaderName() {
        try {
            Object name = Class.forName("net.irisshaders.iris.Iris")
                    .getMethod("getCurrentPackName")
                    .invoke(null);
            return name == null ? "" : name.toString();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return "";
        }
    }

    private ExternalVisualSettings() {}
}

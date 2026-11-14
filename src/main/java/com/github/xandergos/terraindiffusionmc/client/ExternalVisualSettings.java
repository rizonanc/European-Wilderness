package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.config.PhotonFogPreset;
import com.github.xandergos.terraindiffusionmc.compat.voxy.Voxy016Api;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;
import java.lang.reflect.*;
import java.util.*;

/** User-triggered native settings and shader reload; never runs automatically. */
public final class ExternalVisualSettings {
    public record VoxyState(boolean present,boolean enabled,boolean rendering,int chunks){}
    public static VoxyState voxyState() {
        try {
            if(!Voxy016Api.runtimeReady())return new VoxyState(true,false,false,0);
            Class<?> type=Class.forName("me.cortex.voxy.client.config.VoxyConfig");Object cfg=type.getField("CONFIG").get(null);
            return new VoxyState(true,type.getField("enabled").getBoolean(cfg),type.getField("enableRendering").getBoolean(cfg),
                Voxy016Api.horizonBlocks(type.getField("sectionRenderDistance").getFloat(cfg))/16);
        }catch(ReflectiveOperationException|LinkageError unavailable){return new VoxyState(false,false,false,0);}
    }
    public static Screen voxy(Screen parent) throws ReflectiveOperationException {
        var integration=(ModMenuApi)Class.forName("me.cortex.voxy.client.config.ModMenuIntegration").getConstructor().newInstance();
        Screen screen=integration.getModConfigScreenFactory().create(parent);
        if(screen==null)throw new IllegalStateException("Voxy settings are unavailable");return screen;
    }
    public static Screen iris(Screen parent) throws ReflectiveOperationException {
        var type=Class.forName("net.irisshaders.iris.api.v0.IrisApi");Object api=type.getMethod("getInstance").invoke(null);
        Screen screen=(Screen)type.getMethod("openMainIrisScreenObj",Object.class).invoke(api,parent);
        if(screen==null)throw new IllegalStateException("Iris settings are unavailable");return screen;
    }
    public static String shaderName() {
        try{Object name=Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPackName").invoke(null);return name==null?"":name.toString();}
        catch(ReflectiveOperationException|LinkageError unavailable){return "";}
    }
    public static boolean photon(){return shaderName().toLowerCase(Locale.ROOT).contains("photon");}
    @SuppressWarnings("unchecked")
    public static void photonFog(boolean clear) throws ReflectiveOperationException {
        if(!photon())throw new IllegalStateException("Select Photon first");
        Class<?> type=Class.forName("net.irisshaders.iris.Iris");
        Object current=((Optional<?>)type.getMethod("getCurrentPack").invoke(null)).orElseThrow();
        Object options=current.getClass().getMethod("getShaderPackOptions").invoke(current);
        Object set=options.getClass().getMethod("getOptionSet").invoke(options);
        Map<?,?> strings=(Map<?,?>)set.getClass().getMethod("getStringOptions").invoke(set);
        Map<?,?> booleans=(Map<?,?>)set.getClass().getMethod("getBooleanOptions").invoke(set);
        if(!strings.containsKey("OVERWORLD_FOG_INTENSITY")||!booleans.containsKey("BORDER_FOG"))throw new IllegalStateException("Unsupported Photon fog options");
        // Do not use queueShaderPackOptionsFromProperties: it resets unrelated options.
        Map<String,String> queue=(Map<String,String>)type.getMethod("getShaderPackOptionQueue").invoke(null);
        PhotonFogPreset.queue(queue,clear);type.getMethod("reload").invoke(null);
    }
    private ExternalVisualSettings(){}
}

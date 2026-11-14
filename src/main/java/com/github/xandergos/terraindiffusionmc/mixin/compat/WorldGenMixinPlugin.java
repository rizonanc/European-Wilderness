package com.github.xandergos.terraindiffusionmc.mixin.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import java.util.*;

public final class WorldGenMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String name){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){return FabricLoader.getInstance().getModContainer("voxyworldgenv2")
        .map(m->m.getMetadata().getVersion().getFriendlyString().equals("2.2.4")).orElse(false);}
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}

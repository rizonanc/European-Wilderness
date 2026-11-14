package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.VoxySupport;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import java.util.*;

public final class CoarseVoxyMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String name){}
    public String getRefMapperConfig(){return null;}
    /** Opt-in experimental renderer-only coarse horizon; still refuses unsupported Voxy versions. */
    public boolean shouldApplyMixin(String target,String mixin){return TerrainDiffusionConfig.voxyCoarseExperimental()&&FabricLoader.getInstance().getModContainer("voxy")
        .map(m->VoxySupport.problem(m.getMetadata().getVersion().getFriendlyString(),false)==null).orElse(false);}
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Keep version decisions independent of optional client classes. */
public final class VoxySupport {
    private VoxySupport(){}
    public static String problem(String version,boolean seedGen) {
        if(!"0.2.16-beta".equals(version)&&!"0.2.16-beta+1.21.11".equals(version)) return "Нужен Voxy 0.2.16-beta для Minecraft 1.21.11; обнаружен "+version;
        return seedGen?"Отключите Voxy SeedGen: прямой мост использует Terrain Diffusion, одновременная запись двумя генераторами не поддерживается":null;
    }
}

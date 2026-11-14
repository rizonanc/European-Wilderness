package com.github.xandergos.terraindiffusionmc.config;

import java.util.*;

/** Only the two explicit fog choices are changed; existing shader options stay intact. */
public final class PhotonFogPreset {
    public static Map<String,String> values(boolean clear){return Map.of("OVERWORLD_FOG_INTENSITY",clear?"0.10":"1.00","BORDER_FOG",clear?"false":"true");}
    public static void queue(Map<String,String> options,boolean clear){options.putAll(values(clear));}
    private PhotonFogPreset(){}
}

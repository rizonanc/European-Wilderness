package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiverFinishTest {
    @Test void onlySoilWithinWetColumnsIsReplacedAndRepeatFinishPreservesDecorationsAndStructures() {
        int[] air=new int[4096],water=new int[4096];Arrays.fill(air,98);Arrays.fill(water,PreparedWaterArea.NONE);
        for(int z=8;z<48;z++)for(int x=8;x<48;x++)water[z*64+x]=100;
        var overlay=new PreparedWaterArea(0,0,64,air,water);Map<String,String> blocks=new HashMap<>();
        blocks.put("20,97,20","soil");blocks.put("20,99,20","soil");blocks.put("21,100,20","soil");
        for(String kind:List.of("slab","wood","leaves","seagrass","structure"))blocks.put((22+blocks.size())+",99,20",kind);
        blocks.put("20,101,20","soil");blocks.put("50,100,20","soil");List<String> writes=new ArrayList<>();
        var access=new RiverFinishPlanning.Access(){
            public boolean soil(int x,int y,int z){return "soil".equals(blocks.get(x+","+y+","+z));}
            private void set(int x,int y,int z,String kind){var p=x+","+y+","+z;writes.add(p);blocks.put(p,kind);}
            public void sand(int x,int y,int z){set(x,y,z,"sand");}
            public void water(int x,int y,int z,boolean surface){assertEquals(y==100,surface);set(x,y,z,"water");}
            public void removeUnsupportedPlant(int x,int y,int z){}};
        for(int z=0;z<64;z+=16)for(int x=0;x<64;x+=16)RiverFinishPlanning.finish(overlay,x,z,-64,319,access);
        assertEquals(Set.of("20,97,20","20,99,20","21,100,20"),new HashSet<>(writes));
        assertEquals("soil",blocks.get("20,101,20"));assertEquals("soil",blocks.get("50,100,20"));
        for(String kind:List.of("slab","wood","leaves","seagrass","structure"))assertTrue(blocks.containsValue(kind));
        int before=writes.size();RiverFinishPlanning.finish(overlay,16,16,-64,319,access);assertEquals(before,writes.size());
    }
    @Test void cleanupIncludesTheBottomAndTopOfWetColumnsAndTheDrySurface() {
        int[] air=new int[4096],water=new int[4096];
        Arrays.fill(air,98);Arrays.fill(water,PreparedWaterArea.NONE);
        water[20*64+20]=100;
        var overlay=new PreparedWaterArea(0,0,64,air,water);
        Set<String> checked=new HashSet<>();
        var access=new RiverFinishPlanning.Access(){
            public boolean soil(int x,int y,int z){return false;}
            public void sand(int x,int y,int z){fail("no soil");}
            public void water(int x,int y,int z,boolean surface){fail("no soil");}
            public void removeUnsupportedPlant(int x,int y,int z){checked.add(x+","+y+","+z);}
        };
        RiverFinishPlanning.finish(overlay,16,16,-64,319,access);
        assertTrue(checked.contains("20,98,20"));
        assertTrue(checked.contains("20,101,20"));
        assertTrue(checked.contains("21,98,20"));
        assertFalse(checked.contains("20,102,20"));
    }
}

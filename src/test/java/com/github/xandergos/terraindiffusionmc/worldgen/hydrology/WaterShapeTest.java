package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaterShapeTest {
    private static int[] lake() {
        int[] w=new int[64*64];Arrays.fill(w,PreparedWaterArea.NONE);
        for(int z=12;z<52;z++) for(int x=12;x<52;x++) w[z*64+x]=100;
        return w;
    }
    private static void island(int[] water,int from,int size) {
        for(int z=from;z<from+size;z++) for(int x=from;x<from+size;x++) water[z*64+x]=PreparedWaterArea.NONE;
    }
    @Test void smallLowIslandBecomesPartOfTheSameLake() {
        int[] water=lake(),air=new int[64*64];Arrays.fill(air,103);
        island(water,28,4);
        WaterShape.removeSmallLowIslands(64,air,water);
        for(int z=28;z<32;z++) for(int x=28;x<32;x++) assertEquals(100,water[z*64+x]);
        assertEquals(PreparedWaterArea.NONE,water[0]);
    }
    @Test void preservesLargeIsland() {
        int[] water=lake(),air=new int[64*64];Arrays.fill(air,103);
        island(water,24,16);WaterShape.removeSmallLowIslands(64,air,water);
        assertEquals(PreparedWaterArea.NONE,water[30*64+30]);
    }
    @Test void preservesHighIslandEvenWhenItIsSmall() {
        int[] water=lake(),air=new int[64*64];Arrays.fill(air,115);
        island(water,28,4);WaterShape.removeSmallLowIslands(64,air,water);
        assertEquals(PreparedWaterArea.NONE,water[30*64+30]);
    }
    @Test void doesNotConnectWaterAtDifferentElevationsThroughAnIsland() {
        int[] water=lake(),air=new int[64*64];Arrays.fill(air,103);
        island(water,28,4);water[29*64+27]=101;
        WaterShape.removeSmallLowIslands(64,air,water);
        assertEquals(PreparedWaterArea.NONE,water[30*64+30]);
    }
    @Test void keepsPeninsulaConnectedToDryShore() {
        int[] water=lake(),air=new int[64*64];Arrays.fill(air,103);
        for(int x=0;x<32;x++) water[30*64+x]=PreparedWaterArea.NONE;
        WaterShape.removeSmallLowIslands(64,air,water);
        assertEquals(PreparedWaterArea.NONE,water[30*64+30]);
    }
    @Test void smoothingKeepsBoundariesAndRemovesASingleBlockDrainagePit() {
        int[][] terrain=new int[9][9];for(int[] row:terrain) Arrays.fill(row,100);
        terrain[4][4]=99;terrain[0][4]=81;
        int[][] smooth=WaterShape.smoothDrainageHeights(terrain);
        assertEquals(100,smooth[4][4]);assertEquals(81,smooth[0][4]);assertEquals(99,terrain[4][4]);
    }
    @Test void minorRunoffDoesNotCutAChannelInEveryRow() {
        int[][] slope=new int[64][64];
        for(int z=0;z<64;z++) for(int x=0;x<64;x++) slope[z][x]=140-2*x;
        assertEquals(0,PreparedWaterArea.plan(4,0,0,slope).wetColumns());
    }
    @Test void retainExisting231LakeInsteadOfRemovingItsWholeCatchment() {
        int[][] terrain=new int[64][64];for(int[] row:terrain) Arrays.fill(row,101);
        for(int z=28;z<34;z++) for(int x=28;x<34;x++) terrain[z][x]=80;
        var area=PreparedWaterArea.plan(4,0,0,terrain);
        assertTrue(area.wetColumns()>0);
        assertTrue(area.wetColumns()<128,"retaining a small lake must not restore dense branching everywhere");
    }
}

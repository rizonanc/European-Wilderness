package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxyCensusScanTest {
    private record Key(int l,int x,int y,int z){}
    private static final class Fixture implements VoxyCensus.Reader {
        final Map<Key,Integer> masks=new HashMap<>();final Map<Key,long[]> voxels=new HashMap<>();
        public int mask(int l,int x,int y,int z){return masks.getOrDefault(new Key(l,x,y,z),-1);}
        public long[] raw(int l,int x,int y,int z){return voxels.get(new Key(l,x,y,z));}
        void at(int l,int x,int y,int z,int mask,long[] raw){var key=new Key(l,x,y,z);masks.put(key,mask);if(raw!=null)voxels.put(key,raw);}
    }
    @Test void childBitsUseXZYAndVerticalRangeScalesAtEveryLevel() throws Exception {
        var f=new Fixture();f.at(2,-1,-1,0,1<<2,null);
        // bit 2 means z=1, y=0; child must lie at (-2,-2,1).
        f.at(1,-2,-2,1,1,null);f.at(0,-4,-4,2,1,null);
        var report=VoxyCensus.scan(f,List.of(),4,-128,127,-1,0);
        assertTrue(report.contains("parent-бит без ребёнка=0"),report);
        assertTrue(report.contains("partial=false"),report);
    }
    @Test void zeroMaskCheckIsReachableAndLightAirIsNotSolid() throws Exception {
        var f=new Fixture();var raw=new long[32768];raw[0]=1L<<27;
        f.at(1,0,0,0,0,raw);
        var light=new long[32768];Arrays.fill(light,15L<<56);f.at(1,1,0,0,0,light);
        var report=VoxyCensus.scan(f,List.of(),1,-64,127,0,0);
        assertTrue(report.contains("воксели-без-маски=1"),report);
        assertEquals(Integer.MIN_VALUE,VoxyCensus.persistedTopOf(light,0,0));
    }
    @Test void comparesWholeColumnAndAllFourChunkQuadrantsWithAbsoluteHeights() throws Exception {
        var f=new Fixture();var raw=new long[32768];var points=new ArrayList<VoxyCensus.Sample>();
        for(int z:new int[]{2,18})for(int x:new int[]{2,18}){
            int y=x==2?3:20;raw[y*1024+z*32+x]=1L<<27;
            points.add(new VoxyCensus.Sample(-32+x,-32+z,32+y));
        }
        f.at(0,-1,1,-1,1,raw);
        var underground=new long[32768];Arrays.fill(underground,1L<<27);f.at(0,-1,-2,-1,1,underground);
        var report=VoxyCensus.scan(f,points,1,-64,127,-1,-1);
        assertTrue(report.contains("доступно=4, проверено=4, расхождений=0"),report);
        assertTrue(report.contains("NOT proof of persistent corruption"),report);
    }
    @Test void largeCensusIsBoundedAndExplicitlyPartial() throws Exception {
        var report=VoxyCensus.scan(new Fixture(),List.of(),32,-64,1871,0,0);
        assertTrue(report.contains("reads=20001"),report);assertTrue(report.contains("partial=true"),report);
    }
    @Test void targetedInspectionReportsSignedWorldBoundsAndDoesNotClaimPersistence() throws Exception {
        var f=new Fixture();var raw=new long[32768];raw[7*1024]=4L<<27;f.at(2,-3,-1,5,17,raw);
        f.at(3,-2,-1,2,128,null);f.at(2,-2,-1,5,1,null);
        var report=VoxyCensus.inspect(f,2,-3,-1,5);
        assertTrue(report.contains("Y=-128..-1"),report);assertTrue(report.contains("nonAir=1"),report);
        assertTrue(report.contains("childBit=7"),report);assertTrue(report.contains("resident OR persisted"),report);
    }
}

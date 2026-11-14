package com.github.xandergos.terraindiffusionmc.compat.voxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GrassSurfaceMipTest {
    private static final GrassMipMapping M=WaterMipPolicyTest::grassKind;
    private static long v(int id){return ((long)id<<27)|(15L<<56)|(17L<<47);}
    private static long[] field(int root,boolean grass){
        long[] d=new long[4681];for(int y=0;y<=root;y++)Arrays.fill(d,y*256,(y+1)*256,v(171));
        if(grass)Arrays.fill(d,(root+1)*256,(root+2)*256,v(31));return d;
    }
    private static void stock(long[] d)throws Exception{
        var f=new WaterMipPolicyTest.Fixture();WaterMipRepairData.stock(d,(a,b,c,e,g,h,i,j)->f.reduce(new long[]{a,b,c,e,g,h,i,j},false));
    }
    @Test void actualPinnedMipperLosesOddHeightGrassButCapsSurviveBothNearLevels()throws Exception{
        for(int root=0;root<=10;root++){
            long[] d=field(root,true);stock(d);long[] before=d.clone();
            GrassSurfaceMip.correct(d,-183,-181,M);
            assertArrayEquals(Arrays.copyOf(before,4096),Arrays.copyOf(d,4096));
            assertArrayEquals(Arrays.copyOfRange(before,4672,4681),Arrays.copyOfRange(d,4672,4681));
            for(int level=1;level<=2;level++){
                int w=16>>level,off=WaterMipRepairData.offset(level),ground=root>>level;
                for(int z=0;z<w;z++)for(int x=0;x<w;x++){
                    assertEquals(171,WaterMipPolicy.blockId(d[off+ground*w*w+z*w+x]));
                    long cap=d[off+(ground+1)*w*w+z*w+x];assertEquals(31,WaterMipPolicy.blockId(cap));assertEquals(17,(cap>>>47)&511);
                }
            }
        }
    }
    @Test void dryEmptyWaterRoofAndForeignSurfacesDoNotGainGrass()throws Exception{
        for(int foreign:new int[]{0,7,2,180}){
            long[] d=field(2,true);for(int z=0;z<16;z+=2)for(int x=0;x<16;x+=2)d[3*256+z*16+x]=v(foreign);
            // Air exposes the grass ground, a valid dry-but-unplanted source column.
            if(foreign==0)Arrays.fill(d,3*256,4*256,0);
            stock(d);long[] before=d.clone();GrassSurfaceMip.correct(d,0,0,M);assertArrayEquals(before,d);
        }
    }
    @Test void sparseGrassDensityIsStableAcrossVerticalGridPhases()throws Exception{
        long[] reference=null;
        for(int root=2;root<=5;root++){
            long[] d=field(root,false);for(int z=0;z<16;z++)for(int x=0;x<16;x++)if((x+z)%2==0)d[(root+1)*256+z*16+x]=v(31);
            stock(d);GrassSurfaceMip.correct(d,-201,73,M);
            long[] mask=new long[80];int p=0;
            for(int l=1;l<=2;l++){int w=16>>l,cap=(root>>l)+1,off=WaterMipRepairData.offset(l);
                for(int z=0;z<w;z++)for(int x=0;x<w;x++)mask[p++]=WaterMipPolicy.blockId(d[off+cap*w*w+z*w+x]);}
            if(reference==null)reference=mask;else assertArrayEquals(reference,mask);
        }
    }
    @Test void edgeOfFineSectionDoesNotWriteIntoAnotherSectionOrFarMip()throws Exception{
        long[] d=field(14,true);stock(d);long[] before=d.clone();
        GrassSurfaceMip.correct(d,0,0,M);assertArrayEquals(before,d);
    }
    @Test void selectiveMigrationNeverChangesSoilWaterOrConcurrentValues()throws Exception{
        long[] stock=field(2,true);stock(stock);long[] fixed=stock.clone();GrassSurfaceMip.correct(fixed,-3,-5,M);
        for(int level=1;level<=2;level++){
            int w=16>>level,off=WaterMipRepairData.offset(level),ox=(-3&((1<<(level+1))-1))*w,oz=(-5&((1<<(level+1))-1))*w;
            long[] parent=new long[32768];Arrays.fill(parent,v(2));
            for(int y=0,p=off;y<w;y++)for(int z=0;z<w;z++)for(int x=0;x<w;x++,p++)parent[(y<<10)|((oz+z)<<5)|(ox+x)]=stock[p];
            int firstCap=(((2>>level)+1)<<10)|(oz<<5)|ox;parent[firstCap]=v(7);
            long[] before=parent.clone();int changes=GrassMipRepairData.patch(level,-3,0,-5,parent,stock,fixed,M);
            assertTrue(changes>0);assertEquals(v(7),parent[firstCap]);
            for(int i=0;i<parent.length;i++)if(before[i]!=parent[i]){
                int a=M.ewh$grassKind(WaterMipPolicy.blockId(before[i])),b=M.ewh$grassKind(WaterMipPolicy.blockId(parent[i]));
                assertTrue(a==GrassMipMapping.AIR||a>=GrassMipMapping.SHORT);assertTrue(b==GrassMipMapping.AIR||b>=GrassMipMapping.SHORT);
            }
            assertEquals(0,GrassMipRepairData.patch(level,-3,0,-5,parent,stock,fixed,M));
        }
    }
    @Test void repeatedScanDoesNotRetainPreviousFieldAndAllocatesNoArrays()throws Exception{
        long[] wet=field(2,true);stock(wet);GrassSurfaceMip.correct(wet,0,0,M);
        long[] dry=field(2,false);stock(dry);long[] before=dry.clone();GrassSurfaceMip.correct(dry,0,0,M);assertArrayEquals(before,dry);
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        for(int i=0;i<2000;i++)GrassSurfaceMip.correct(wet,0,0,M);
        long id=Thread.currentThread().threadId(),start=bean.getThreadAllocatedBytes(id);
        for(int i=0;i<10000;i++)GrassSurfaceMip.correct(wet,0,0,M);
        assertTrue(bean.getThreadAllocatedBytes(id)-start<4096);
    }
    @Test void migrationMatchesPinnedWriterAndLeavesDerivedCountsAndChildMasksAlone()throws Exception{
        var f=new WaterMipPolicyTest.Fixture();Class<?> ws=f.loadClass("me.cortex.voxy.common.world.WorldSection"),vs=f.loadClass("me.cortex.voxy.common.voxelization.VoxelizedSection");
        Object input=vs.getMethod("createEmpty").invoke(null);long[] data=(long[])vs.getField("section").get(input);
        long[] baseline=field(2,true);stock(baseline);long[] fixed=baseline.clone();GrassSurfaceMip.correct(fixed,-3,-5,M);
        vs.getMethod("setPosition",int.class,int.class,int.class).invoke(input,-3,3,-5);
        var write=f.loadClass("me.cortex.voxy.common.world.WorldUpdater").getDeclaredMethod("insertSectionLvlIntoWorld",vs,ws);write.setAccessible(true);
        for(int level=1;level<=2;level++){
            Object parent=ws.getMethod("_createRawUntrackedUnsafeSection",int.class,int.class,int.class,int.class).invoke(null,level,-3>>(level+1),3>>(level+1),-5>>(level+1));
            ws.getMethod("acquire").invoke(parent);
            try{
                ws.getMethod("_unsafeSetNonEmptyChildren",byte.class).invoke(parent,(byte)255);
                long[] values=(long[])ws.getMethod("_unsafeGetRawDataArray").invoke(parent);
                System.arraycopy(baseline,0,data,0,data.length);write.invoke(null,input,parent);long[] start=values.clone();
                System.arraycopy(fixed,0,data,0,data.length);write.invoke(null,input,parent);long[] expected=values.clone();
                System.arraycopy(start,0,values,0,values.length);
                assertTrue(GrassMipRepairData.patch(level,-3,3,-5,values,baseline,fixed,M)>0);
                assertArrayEquals(expected,values);
                assertEquals((byte)255,ws.getMethod("getNonEmptyChildren").invoke(parent));
                assertEquals(0,ws.getMethod("getNonEmptyBlockCount").invoke(parent));
            }finally{ws.getMethod("release").invoke(parent);}
        }
    }
}

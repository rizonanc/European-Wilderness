package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoiUpsamplingTest {
    @Test void precipitationCannotBeNegativeAfterModelSampling() {
        float[] climate = {-5, 0, 1, 2, -1, 12, -24.7f, 20, Float.NaN, -2, -3, -4};
        LocalTerrainProvider.clampPrecipitation(climate, 3);
        same(new float[]{-5, 0, 1, 2, -1, 12, 0, 20, Float.NaN, -2, -3, -4}, climate);
    }
    static void same(float[] expected,float[] actual){
        assertEquals(expected.length,actual.length);for(int i=0;i<expected.length;i++)
            assertEquals(Float.floatToIntBits(expected[i]),Float.floatToIntBits(actual[i]),"float bits at "+i);
    }
    static float[][] data(int h,int w,long seed){var rng=new Random(seed);var out=new float[h][w];for(var row:out)for(int c=0;c<w;c++)row[c]=rng.nextFloat()*10000-4000;return out;}
    static float[] reference(float[][] src,int dh,int dw,int y,int x,int h,int w){
        var full=LaplacianUtils.bilinearResize(src,dh,dw);var out=new float[h*w];
        for(int r=0;r<h;r++)for(int c=0;c<w;c++)out[r*w+c]=full[Math.max(0,Math.min(dh-1,y+r))][Math.max(0,Math.min(dw-1,x+c))];return out;
    }
    @Test void randomizedCropsUseTheOriginalGlobalCoordinatesAndClamp(){
        var rng=new Random(98123);
        for(int run=0;run<1500;run++){
            int h=1+rng.nextInt(17),w=1+rng.nextInt(21),dh=1+rng.nextInt(75),dw=1+rng.nextInt(79);
            int y=rng.nextInt(dh+20)-10,x=rng.nextInt(dw+20)-10,ch=rng.nextInt(24),cw=rng.nextInt(26);
            var src=data(h,w,run);same(reference(src,dh,dw,y,x,ch,cw),LaplacianUtils.bilinearResizeRoi(src,dh,dw,y,x,ch,cw));
        }
    }
    @Test void everyScaleAndSignedWorldBorderKeepsElevationHaloAndClimateIdentical(){
        for(int scale=1;scale<=6;scale++)for(int start:new int[]{-513,-257,-256,-255,-1,0,1,255,256,257})for(int size:new int[]{1,16,33}){
            int n0=Math.floorDiv(start,scale),n1=-Math.floorDiv(-(start+size),scale),n=n1-n0+4,full=n*scale;
            int offset=2*scale+start-n0*scale;var src=data(n,n,start);int h=size+2;
            var padded=LaplacianUtils.bilinearResizeRoi(src,full,full,offset-1,offset-1,h,h);
            same(reference(src,full,full,offset-1,offset-1,h,h),padded);
            var center=new float[size*size];for(int r=0;r<size;r++)System.arraycopy(padded,(r+1)*h+1,center,r*size,size);
            same(reference(src,full,full,offset,offset,size,size),center);
            float[] nativeClimate=new float[4*n*n];
            for(int ch=0;ch<4;ch++)for(int r=0;r<n;r++)System.arraycopy(src[r],0,nativeClimate,ch*n*n+r*n,n);
            var expected=new float[4*size*size];var single=reference(src,full,full,offset,offset,size,size);
            for(int ch=0;ch<4;ch++)System.arraycopy(single,0,expected,ch*size*size,single.length);
            same(expected,LocalTerrainProvider.upsampleClimate(nativeClimate,n,n,offset,offset,size,size,scale,full,full));
        }
    }
    @Test void degenerateSourceEdgesFloatingPointSpecialsAndFallbackRemainAvailable(){
        for(var src:List.of(new float[][]{{-0.0f}},new float[][]{{0,-0.0f,Float.NaN,Float.POSITIVE_INFINITY}},new float[][]{{1},{Float.NEGATIVE_INFINITY}}))
            same(reference(src,19,13,-2,5,30,20),LaplacianUtils.bilinearResizeRoi(src,19,13,-2,5,30,20));
        assertThrows(IllegalArgumentException.class,()->LaplacianUtils.bilinearResizeRoi(new float[][]{{1},{2,3}},3,3,0,0,1,1));
        String old=System.getProperty("terrain.diffusion.roi.disable");
        try{
            float[] src={1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};
            System.clearProperty("terrain.diffusion.roi.disable");var fast=LocalTerrainProvider.upsampleClimate(src,2,2,1,1,3,3,2,4,4);
            System.setProperty("terrain.diffusion.roi.disable","true");same(fast,LocalTerrainProvider.upsampleClimate(src,2,2,1,1,3,3,2,4,4));
        }finally{if(old==null)System.clearProperty("terrain.diffusion.roi.disable");else System.setProperty("terrain.diffusion.roi.disable",old);}
    }
    @Test void destinationWritesOnlyItsOwnedSpanAndLeavesSourceUnchanged(){
        var src=data(7,11,123);var original=Arrays.stream(src).map(float[]::clone).toArray(float[][]::new);
        var result=new float[103];Arrays.fill(result,-999);
        LaplacianUtils.bilinearResizeRoiInto(src,35,44,2,-1,8,12,result,4);
        same(reference(src,35,44,2,-1,8,12),Arrays.copyOfRange(result,4,100));
        for(int i=0;i<4;i++)assertEquals(-999,result[i]);for(int i=100;i<result.length;i++)assertEquals(-999,result[i]);
        for(int i=0;i<src.length;i++)same(original[i],src[i]);
        assertThrows(IllegalArgumentException.class,()->LaplacianUtils.bilinearResizeRoiInto(src,35,44,2,-1,8,12,result,10));
        assertThrows(IllegalArgumentException.class,()->LaplacianUtils.bilinearResizeRoiInto(src,35,44,2,-1,1,1,src[0],0));
    }
}

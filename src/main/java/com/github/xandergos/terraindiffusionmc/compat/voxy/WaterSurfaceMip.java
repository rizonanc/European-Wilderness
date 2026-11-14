package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Derive water coverage from unchanged full-resolution columns, before LOD loses vegetation/fluids. */
public final class WaterSurfaceMip {
    private static final class Scan { final int[] height=new int[256];final long[] water=new long[256]; }
    private static final ThreadLocal<Scan> SCAN=ThreadLocal.withInitial(Scan::new);
    private WaterSurfaceMip() {}
    public static int correct(long[] data,WaterMipMapping mapping) {
        if(data.length<4681)throw new IllegalArgumentException("Incomplete Voxy mip array");
        Scan scan=SCAN.get();int wet=0;
        for(int col=0;col<256;col++){
            scan.height[col]=-1;scan.water[col]=0;
            for(int y=15;y>=0;y--){
                long value=data[y*256+col];if(WaterMipPolicy.blockId(value)==0)continue;
                long water=mapping.ewh$surfaceWaterValue(value);
                if(water!=0){scan.height[col]=y;scan.water[col]=water;wet++;}
                break;
            }
        }
        if(wet<4)return 0;
        int changed=0;
        for(int level=1;level<=4;level++){
            int size=1<<level,width=16>>level,offset=WaterMipRepairData.offset(level);
            for(int z=0;z<16;z+=size)for(int x=0;x<16;x+=size){
                int first=z*16+x;if(scan.height[first]<0)continue;
                int low=15,high=0;boolean covered=true;
                for(int dz=0;dz<size&&covered;dz++)for(int dx=0;dx<size;dx++){
                    int col=(z+dz)*16+x+dx;
                    if(scan.height[col]<0){covered=false;break;}
                    low=Math.min(low,scan.height[col]);high=Math.max(high,scan.height[col]);
                }
                if(!covered)continue;
                int cellY=low>>level;
                long water=scan.water[(z+size-1)*16+x+size-1];
                if((high>>level)!=cellY){
                    // A water-level step can cross a mip layer while the lower
                    // surface remains continuous across every full-resolution column.
                    for(int dz=0;dz<size&&covered;dz++)for(int dx=0;dx<size;dx++){
                        int col=(z+dz)*16+x+dx;
                        long common=mapping.ewh$surfaceWaterValue(data[low*256+col]);
                        if(common==0){covered=false;break;}
                        water=common;
                    }
                    if(!covered)continue;
                }
                int index=offset+cellY*width*width+(z>>level)*width+(x>>level);
                if(data[index]!=water){data[index]=water;changed++;}
            }
        }
        return changed;
    }
}

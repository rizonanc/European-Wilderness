package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Selective migration: only replace the exact old derived voxel by preserved water. */
public final class WaterMipRepairData {
    private static final VarHandle ELEMENT=MethodHandles.arrayElementVarHandle(long[].class);
    @FunctionalInterface public interface Mipper {
        long reduce(long a,long b,long c,long d,long e,long f,long g,long h) throws Exception;
    }
    private WaterMipRepairData() {}
    public static int offset(int level) {
        if(level<1||level>4)throw new IllegalArgumentException("Derived level must be 1..4");
        int result=0;for(int n=16;n>(16>>level);n>>=1)result+=n*n*n;return result;
    }
    /** Standard 2x2x2 reduction using the pinned, unmodified Voxy Mipper function. */
    public static void stock(long[] values,Mipper mipper) throws Exception {
        int width=16,source=0,target=4096;
        while(width>1){
            int next=width>>1,start=target,plane=width*width;
            for(int y=0;y<next;y++)for(int z=0;z<next;z++)for(int x=0;x<next;x++){
                int i=source+(y*2)*plane+(z*2)*width+x*2;
                values[target++]=mipper.reduce(values[i],values[i+1],values[i+width],values[i+width+1],
                    values[i+plane],values[i+plane+1],values[i+plane+width],values[i+plane+width+1]);
            }
            source=start;width=next;
        }
    }
    public static int patch(int level,int chunkX,int chunkY,int chunkZ,long[] parent,long[] stock,long[] fixed,
                            WaterMipMapping mapping) {
        int size=16>>level,mask=(1<<(level+1))-1;
        int ox=(chunkX&mask)*size,oy=(chunkY&mask)*size,oz=(chunkZ&mask)*size;
        int src=offset(level),changed=0;
        for(int y=0;y<size;y++)for(int z=0;z<size;z++)for(int x=0;x<size;x++,src++){
            long before=stock[src],after=fixed[src];
            if(before==after||WaterMipPolicy.blockId(before)==0)continue;
            if(!mapping.ewh$isWater(WaterMipPolicy.blockId(after))||mapping.ewh$isWater(WaterMipPolicy.blockId(before)))continue;
            int index=((oy+y)<<10)|((oz+z)<<5)|(ox+x);
            // Preserve concurrently updated or foreign data. Non-air -> non-air keeps
            // child masks and non-empty counts valid, without rewriting other materials.
            if(ELEMENT.compareAndSet(parent,index,before,after))changed++;
        }
        return changed;
    }
}

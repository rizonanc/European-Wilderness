package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.tree.*;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Pure planning/voxelisation probe: excludes disk, Voxy renderer and GPU. */
public final class ForestLodProbe {
    private static final TreeLodPreview.Field FIELD=new TreeLodPreview.Field(){
        final TreeLodPreview.Climate climate=new TreeLodPreview.Climate((short)215,(short)300,12,900);
        public TreeLodPreview.Climate climate(int x,int z){return climate;}
        public int firstAir(int x,int z){return 101;}
        public int waterY(int x,int z){return Integer.MIN_VALUE;}
    };
    private static long paint(int count) throws Exception {
        long checksum=0;long[][] palette=new long[LodColumn.Kind.values().length][2];for(int k=0;k<palette.length;k++)Arrays.fill(palette[k],k);
        long[] buffer=new long[4681];
        for(int i=0;i<count;i++) {
            int x=(i%8-4)*32,z=(i/8%8-4)*32;int[] air=new int[1024],water=new int[1024];Arrays.fill(air,101);Arrays.fill(water,Integer.MIN_VALUE);
            var base=LodColumn.owned(x,z,-64,319,air,water,new short[1024]);var builder=new LodColumn.ForestBuilder(base);
            TreeLodPreview.paint(12345,x,z,32,-64,319,FIELD,builder::set);var result=builder.build();checksum+=result.maxY;
            var encoder=new LodVoxelEncoder(result,biome->palette);
            // This intentionally tests one encoded canopy section, not a full underground column.
            checksum+=encoder.fill(0,7,0,buffer);
        }
        return checksum;
    }
    public static void main(String[] args) throws Exception {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        paint(32);long thread=Thread.currentThread().threadId();long start=System.nanoTime(),before=bean.getThreadAllocatedBytes(thread),checksum=paint(128);
        long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(thread)-before;
        System.out.println("{\"scope\":\"synthetic flat forest plus one encoded canopy section; no disk/game/GPU\",\"columns\":128,\"elapsed_ns\":"+elapsed+",\"allocated_bytes\":"+bytes+",\"checksum\":"+checksum+"}");
    }
}

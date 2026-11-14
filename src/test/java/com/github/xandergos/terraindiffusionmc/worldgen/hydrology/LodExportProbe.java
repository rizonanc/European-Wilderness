package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.compat.voxy.LodTerrainSource;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import java.nio.file.*;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Standalone hot-export allocation probe, executable against the old 2.5.0 named JAR too. */
public final class LodExportProbe {
    private static volatile long checksum;
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);if(Files.exists(output.resolve("water"))) throw new IllegalArgumentException("Use a fresh output directory");
        var ticket=WorldSession.activate(GenerationProfile.defaultsFor(2,"export-probe","cpu","cpu"),7);
        var repo=new TerrainRepository(new DiskTerrainStore(output.resolve("raw")),key->{
            short[] heights=new short[65536],biomes=new short[65536];Arrays.fill(heights,(short)600);Arrays.fill(biomes,(short)8);return new TerrainTile(key,heights,biomes);
        },256,64L*1024*1024,1024L*1024*1024,ticket);ticket.attachRepository(repo);
        var store=new WorldWaterStore(output.resolve("water"),7,ticket.fingerprint(),256,WorldWaterGrid.DEFAULT);
        for(int core=0;core<8;core++) {
            int[] air=new int[512*512],levels=new int[air.length];Arrays.fill(air,103);Arrays.fill(levels,Integer.MIN_VALUE);
            store.save(new WorldWaterTile(WorldWaterGrid.DEFAULT,new WorldWaterGrid.Cell(core,0),air,levels,List.of()));
            for(int z=-1;z<=2;z++) for(int x=core*2-1;x<=core*2+2;x++) repo.get(TerrainTileKey.ofTile(7,x,z,256,ticket.fingerprint()));
        }
        try(var water=new WorldWaterController(ticket,output.resolve("water"),300,WorldWaterGrid.DEFAULT,16,24)) {
            ticket.attachAutomaticWater(water);var source=new LodTerrainSource(ticket);
            for(int i=0;i<8;i++) {water.ensureColumn(i*512,0);source.sample(i*16,0,-64,319);}
            var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            if(!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            long thread=Thread.currentThread().threadId(),allocated=bean.getThreadAllocatedBytes(thread),start=System.nanoTime(),sum=0;
            for(int i=0;i<800;i++) {var column=source.sample(i%8*16,0,-64,319);sum+=column.firstAir(0,0)+column.biome(0,0);}
            long elapsed=System.nanoTime()-start,bytes=bean.getThreadAllocatedBytes(thread)-allocated;checksum=sum;
            String result="{\"samples\":800,\"nanoseconds\":"+elapsed+",\"allocatedBytes\":"+bytes+",\"checksum\":"+checksum+"}\n";
            Files.writeString(output.resolve("result.json"),result);System.out.print(result);
        } finally {WorldSession.deactivate(ticket);}
    }
}

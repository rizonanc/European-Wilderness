package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import java.nio.file.*;
import java.util.*;

/** Standalone CPU/IO probe usable with both the 2.4.0 and 2.4.1 named JARs.
 * Repeats a captured 1024-square raw terrain fixture outside its edges. No model/GPU or game tick. */
public final class WaterPerformanceProbe {
    public static void main(String[] args) throws Exception {
        Path fixture=Path.of(args[0]),output=Path.of(args[1]);
        if(Files.exists(output.resolve("water"))) throw new IllegalStateException("Use a fresh benchmark directory");
        var source=new DiskTerrainStore(fixture);Map<String,TerrainTile> captured=new HashMap<>();
        String fp="7d73c789d6b92225ca49fc0b8087c3353eecad582fcc0c92f4b6c0062eeee577";
        for(int z=14;z<18;z++) for(int x=14;x<18;x++) captured.put(x+","+z,source.load(TerrainTileKey.ofTile(12345,x,z,256,fp)).orElseThrow());
        var ticket=WorldSession.activate(GenerationProfile.defaultsFor(2,"recorded-fixture","cpu","cpu"),12345);
        ticket.attachRepository(new TerrainRepository(new DiskTerrainStore(output.resolve("raw")),key->{
            var original=captured.get((14+Math.floorMod(key.tileX()-14,4))+","+(14+Math.floorMod(key.tileZ()-14,4)));
            return new TerrainTile(key,original.heightsMeters(),original.biomeIds(),original.climate());
        },256,64L*1024*1024,1024L*1024*1024,ticket));
        long[] nanos=new long[5];int[][] route={{4096,4096},{4608,4096},{5120,4096},{4096,4608},{4096,4096}};
        try(var water=new WorldWaterController(ticket,output.resolve("water"),1871)) {
            for(int i=0;i<route.length;i++) {long start=System.nanoTime();water.ensureColumn(route[i][0],route[i][1]);nanos[i]=System.nanoTime()-start;}
            Files.createDirectories(output);
            Files.writeString(output.resolve("stages.json"),new com.google.gson.Gson().toJson(water.measurements()));
            Files.writeString(output.resolve("timings.json"),"{\"nanoseconds\":"+Arrays.toString(nanos)+",\"computedCores\":"+water.computedTiles()+",\"computedUnits\":"+water.computedUnits()+"}\n");
            if(args.length>2&&args[2].equals("--retained")){
                System.gc();
                long used=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
                Files.writeString(output.resolve("retained-heap.json"),"{\"used_after_explicit_gc_bytes\":"+used+",\"note\":\"Standalone fixture only; explicit GC occurs after timed work and is never used in production\"}");
                java.lang.ref.Reference.reachabilityFence(water);java.lang.ref.Reference.reachabilityFence(captured);
            }
        } finally {WorldSession.deactivate(ticket);}
        System.out.println("CPU/IO fixture route nanoseconds: "+Arrays.toString(nanos));
    }
}

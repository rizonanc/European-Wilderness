package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HydrologySchedulingTest {
    @TempDir Path directory;
    @AfterEach void retire(){WorldSession.deactivate(WorldSession.current());}
    @Test void blockedRawDependencyDoesNotBlockAnIndependentReadyCatchment() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var session=WorldSession.activate(GenerationProfile.defaultsFor(2,"model","cpu","cpu"),7);
        session.attachRepository(new TerrainRepository(new DiskTerrainStore(directory.resolve("raw")),key->{
            if(key.blockStartX()<512){entered.countDown();assertTrue(release.await(20,TimeUnit.SECONDS));}
            short[] h=new short[256*256];java.util.Arrays.fill(h,(short)600);
            return new TerrainTile(key,h,new short[h.length]);
        },256,64L*1024*1024,1024L*1024*1024,session));
        try(var water=new WorldWaterController(session,directory.resolve("water"),300,new WorldWaterGrid(64,32,128),2,4);
            var pool=Executors.newFixedThreadPool(2)) {
            var blocked=pool.submit(()->water.ensureColumn(0,0));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertFalse(water.measurements().containsKey("unitHold"),"No CPU permit is held while raw tiles are unavailable");
            var ready=pool.submit(()->water.ensureColumn(4096,4096));
            try{ready.get(10,TimeUnit.SECONDS);assertTrue(water.computedUnits()>0);}
            finally{release.countDown();}
            blocked.get(20,TimeUnit.SECONDS);
            assertEquals(2,water.computedTiles());
        }finally{release.countDown();}
    }
}

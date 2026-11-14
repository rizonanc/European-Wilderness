package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LodDistanceTest {
    @Test void chunkDistancesDoNotStopAtTheOld128ChunkLimit() {
        assertEquals(8192,WorldGenCompat.radiusBlocks(512));
        assertEquals(32768,WorldGenCompat.radiusBlocks(2048));
        assertEquals(32768,WorldGenCompat.radiusBlocks(Integer.MAX_VALUE));
    }
    @Test void lazyRingsCoverTheWholeDiskOnceIncludingNegativeCoordinates() {
        for(int blocks:new int[]{64,96,128,512}) {
            var scan=new LodScan();scan.move(-1,-33,blocks);Set<LodScan.Cell> actual=new HashSet<>();
            int n=(blocks+31)/32,idle=0;
            for(int i=0;i<10000&&idle<3;i++) {
                var cell=scan.next();if(cell==null){idle++;continue;}idle=0;
                assertTrue(actual.add(cell),"duplicate "+cell);scan.completed(cell);
            }
            Set<LodScan.Cell> expected=new HashSet<>();
            for(int z=-n;z<=n;z++)for(int x=-n;x<=n;x++)if(x*x+z*z<=n*n)expected.add(new LodScan.Cell(-1+x,-2+z));
            assertEquals(expected,actual);
        }
    }
    @Test void largeRadiusSetupDoesNotAllocateMillionsOfOffsetsAndScanReachesBeyond2048Blocks() {
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);var scan=new LodScan();scan.move(0,0,64);
        long id=Thread.currentThread().threadId(),before=bean.getThreadAllocatedBytes(id);
        scan.move(0,0,32768);assertTrue(bean.getThreadAllocatedBytes(id)-before<65536,"radius setup must stay constant-space");
        int count=0;boolean beyond=false;
        while(count<22000) {var cell=scan.next();if(cell==null)continue;scan.completed(cell);count++;
            if(Math.abs(cell.x())>64||Math.abs(cell.z())>64)beyond=true;}
        assertTrue(beyond);assertEquals(16384,scan.remembered());
        assertThrows(IllegalArgumentException.class,()->scan.move(0,0,32769));
    }
    @Test void readyNotificationRevisitsMissingRingWithoutRestartingAtTheCenter() {
        var scan=new LodScan();scan.move(0,0,8192);LodScan.Cell missing=null;
        for(int i=0;i<10000;i++){var c=scan.next();if(c==null)continue;
            if(i==9000){missing=c;scan.defer(c);}else scan.completed(c);}
        assertNotNull(missing);scan.reconsider();boolean found=false;
        for(int i=0;i<10;i++){var cell=scan.next();if(missing.equals(cell)){found=true;break;}if(cell!=null)scan.completed(cell);}
        assertTrue(found,"ready notification must not spend ticks scanning thousands of older inner cells");
    }
}

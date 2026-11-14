package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import static org.junit.jupiter.api.Assertions.*;

class PreparedWaterTest {
    @TempDir Path temp;
    private static int[][] bowl(int size) {
        int[][] height=new int[size][size];
        for(int z=0;z<size;z++) for(int x=0;x<size;x++)
            height[z][x]=(x>=12&&z>=12&&x<size-12&&z<size-12)?86:101;
        return height;
    }
    @Test void lakeFillsWholeDepressionWithShallowShoreAndNaturalDeepInterior() {
        var a=PreparedWaterArea.plan(7,0,0,bowl(64));
        assertTrue(a.wetColumns()>1000);
        assertEquals(100,a.waterY(16,16));
        assertTrue(a.bedY(16,16,999)>85,"shore must rise gradually above the deep lake bed");
        assertEquals(85,a.bedY(32,32,999));
        assertEquals(99,a.bedY(12,32,999));
        assertEquals(a.bedY(16,16,999)+1,a.carvedHeight(16,16,999));
        // lake coverage is independent of river accumulation
        for(int z=12;z<52;z++) for(int x=12;x<52;x++) assertEquals(100,a.waterY(x,z));
    }
    @Test void fineShorePinnaclesInsideCoarseLakeDoNotProduceWaterBelowBed() {
        int[][] height=bowl(64);
        Random random=new Random(4917);
        // A 2x2 coarse cell still belongs to the lake, but one actual block is
        // above its surface and can be touched by a widened incoming stream.
        for(int z=0;z<64;z++) for(int x=0;x<64;x++) {
            height[z][x]+=random.nextInt(25);
        }
        for(int z=13;z<51;z+=2) for(int x=13;x<51;x+=2) height[z][x]=130;
        var area=PreparedWaterArea.plan(12345,0,0,height);
        assertTrue(area.wetColumns()>0);
        for(int z=0;z<64;z++) for(int x=0;x<64;x++) if(area.waterY(x,z)!=PreparedWaterArea.NONE)
            assertTrue(area.bedY(x,z,0)<area.waterY(x,z));
    }
    @Test void replayActualFailed1024BlockAreaWhenSnapshotIsAvailable() throws Exception {
        Path snapshot=Path.of("../recovery-evidence/g3-invalid-column/terrain");
        Assumptions.assumeTrue(Files.isDirectory(snapshot),"Local incident snapshot is not part of the source distribution");
        var store=new DiskTerrainStore(snapshot);
        String fp="7d73c789d6b92225ca49fc0b8087c3353eecad582fcc0c92f4b6c0062eeee577";
        int[][] raw=new int[1024][1024];
        for(int tz=14;tz<18;tz++) for(int tx=14;tx<18;tx++) {
            var tile=store.load(TerrainTileKey.ofTile(12345,tx,tz,256,fp)).orElseThrow();
            for(int z=0;z<256;z++) for(int x=0;x<256;x++) {
                int wx=tx*256+x,wz=tz*256+z;
                raw[wz-3584][wx-3584]=com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField
                    .sample(12345,wx,wz,tile.heightAt(x,z),2,1871).surfaceY();
            }
        }
        var area=PreparedWaterArea.plan(12345,3584,3584,raw);
        assertTrue(area.wetColumns()>0);
        System.out.println("Actual area replay: "+area.wetColumns()+" wet columns");
        Path map=Path.of("../recovery-evidence/g3-cohesion/current.bin");
        Files.createDirectories(map.getParent());
        try(var out=new java.io.DataOutputStream(Files.newOutputStream(map))) {
            out.writeInt(1024);
            for(int[] row:raw) for(int h:row) out.writeInt(h);
            for(int h:area.waterCopy()) out.writeInt(h);
        }
        Path shore=Path.of("../recovery-evidence/g3-tree-integrity-preview/profile.bin");
        Files.createDirectories(shore.getParent());
        try(var out=new java.io.DataOutputStream(Files.newOutputStream(shore))) {
            out.writeInt(1024);
            for(int[] row:raw) for(int h:row) out.writeInt(h);
            for(int h:area.waterCopy()) out.writeInt(h);
            for(int h:area.firstAirCopy()) out.writeInt(h);
        }
        StringBuilder details=new StringBuilder("x,y,z,kind,waterlogged\n");
        for(int cz=0;cz<64;cz++) for(int cx=0;cx<64;cx++)
            RiverDetailPlanning.forChunk(area,12345,3584+cx*16,3584+cz*16,-64,1887,d->
                details.append(d.x()).append(',').append(d.y()).append(',').append(d.z()).append(',')
                    .append(d.kind()).append(',').append(d.waterlogged()).append('\n'));
        Files.writeString(shore.resolveSibling("details.csv"),details);
        StringBuilder falls=new StringBuilder("x,z,dx,dz,upper,lower,width,run\n");
        for(var f:area.waterfalls()) falls.append(f.x()).append(',').append(f.z()).append(',').append(f.dx()).append(',').append(f.dz()).append(',')
            .append(f.upperY()).append(',').append(f.lowerY()).append(',').append(f.width()).append(',').append(f.run()).append('\n');
        Files.writeString(shore.resolveSibling("waterfalls.csv"),falls);
        System.out.println("Actual waterfalls: "+area.waterfalls());
    }
    @Test void boundaryRemainsDryAndOutsideIsUnchanged() {
        var a=PreparedWaterArea.plan(7,-64,-64,bowl(64));
        for(int z=0;z<64;z++) for(int x=0;x<64;x++)
            if(x<8||z<8||x>=56||z>=56) assertEquals(PreparedWaterArea.NONE,a.waterY(x-64,z-64));
        assertFalse(a.isReady(0,0));assertEquals(123,a.carvedHeight(0,0,123));
    }
    @Test void refusesRiversWhichWouldEndAtADryFrontier() {
        int[][] slope=new int[64][64];
        for(int z=0;z<64;z++) for(int x=0;x<64;x++) slope[z][x]=250-x;
        assertEquals(0,PreparedWaterArea.plan(3,0,0,slope).wetColumns());
    }
    @Test void riversCanReachAnOceanInsideTheArea() {
        int[][] slope=new int[64][64];
        // A real converging valley, not one stream for every row of a small planar slope.
        for(int z=0;z<64;z++) for(int x=0;x<64;x++) slope[z][x]=140-2*x+Math.abs(z-32)*3;
        var area=PreparedWaterArea.plan(4,0,0,slope);
        assertTrue(area.wetColumns()>0);
        for(int z=0;z<64;z++) for(int x=0;x<64;x++) {
            int level=area.waterY(x,z);
            if(level!=PreparedWaterArea.NONE) assertTrue(level>=63 && area.bedY(x,z,0)<level);
        }
    }
    @Test void downstreamCurrentSurvivesReloadAndLeavesLakesStill() throws Exception {
        int[][] valley=new int[64][64];
        for(int z=0;z<64;z++) for(int x=0;x<64;x++) valley[z][x]=140-2*x+Math.abs(z-32)*3;
        var river=PreparedWaterArea.plan(4,0,0,valley);
        var store=new PreparedWaterStore(temp,4,"flow");
        store.save(river);
        var saved=store.loadAll().getFirst();
        int moving=0;
        for(int x=8;x<56;x++) {
            int y=river.waterY(x,32);
            short flow=river.currentAt(x,y,32);
            assertEquals(flow,saved.currentAt(x,y,32));
            if(flow!=0) {
                assertTrue((byte)(flow>>8)>0,"the valley drains east");
                assertEquals(0,river.currentAt(x,y+1,32));
                moving++;
            }
        }
        assertTrue(moving>4,"the river has a continuous current");
        var lake=PreparedWaterArea.plan(7,0,0,bowl(64));
        assertEquals(0,lake.currentAt(32,100,32));
    }
    @Test void doesNotClipALakeAtTheBoundary() {
        int[][] height=bowl(64);
        for(int z=12;z<52;z++) for(int x=2;x<12;x++) height[z][x]=86;
        assertEquals(0,PreparedWaterArea.plan(1,0,0,height).wetColumns());
    }
    @Test void deterministicAtNegativeCoordinates() {
        var a=PreparedWaterArea.plan(81,-1024,-512,bowl(64));
        var b=PreparedWaterArea.plan(81,-1024,-512,bowl(64));
        assertArrayEquals(a.waterCopy(),b.waterCopy());assertArrayEquals(a.firstAirCopy(),b.firstAirCopy());
    }
    @Test void immutableAndRejectsMalformedGrids() {
        var raw=bowl(64);var a=PreparedWaterArea.plan(1,0,0,raw);
        raw[16][16]=999;int[] copy=a.waterCopy();copy[16*64+16]=999;
        assertEquals(100,a.waterY(16,16));
        assertThrows(IllegalArgumentException.class,()->PreparedWaterArea.plan(1,0,0,new int[33][33]));
        raw[2]=new int[4];assertThrows(IllegalArgumentException.class,()->PreparedWaterArea.plan(1,0,0,raw));
    }
    @Test void storageRoundTripAndWorldIdentity() throws Exception {
        var a=PreparedWaterArea.plan(1,0,0,bowl(64));
        var store=new PreparedWaterStore(temp,1,"profile-a");store.save(a);
        var loaded=store.loadAll().getFirst();
        assertArrayEquals(a.waterCopy(),loaded.waterCopy());assertArrayEquals(a.firstAirCopy(),loaded.firstAirCopy());
        assertThrows(java.io.IOException.class,()->new PreparedWaterStore(temp,2,"profile-a").loadAll());
        assertThrows(java.io.IOException.class,()->new PreparedWaterStore(temp,1,"profile-b").loadAll());
        assertThrows(java.io.IOException.class,()->store.save(a));
    }
    @Test void corruptSavedWaterFailsClosed() throws Exception {
        var store=new PreparedWaterStore(temp,1,"p");store.save(PreparedWaterArea.plan(1,0,0,bowl(64)));
        Path file=temp.resolve("water_0_0.ewh");byte[] bytes=Files.readAllBytes(file);
        bytes[bytes.length-5]^=0x31;Files.write(file,bytes);
        assertThrows(java.io.IOException.class,store::loadAll);
    }
    @Test void wholeLakeFillsAcrossChunkBordersRegardlessOfOrder() {
        var a=PreparedWaterArea.plan(7,0,0,bowl(64));
        var columns=new RiverWaterPlanning.ColumnAccess() {
            public boolean isAir(int x,int y,int z){return y>=a.carvedHeight(x,z,101);}
            public boolean isSolidDry(int x,int y,int z){return !isAir(x,y,z);}
        };
        Set<BlockPos> forward=new HashSet<>(),backward=new HashSet<>();
        for(int z=0;z<4;z++) for(int x=0;x<4;x++) forward.addAll(RiverWaterPlanning.planChunk(a,x,z,-64,320,columns));
        for(int z=3;z>=0;z--) for(int x=3;x>=0;x--) backward.addAll(RiverWaterPlanning.planChunk(a,x,z,-64,320,columns));
        assertEquals(forward,backward);
        for(int z=12;z<52;z++) for(int x=12;x<52;x++) for(int y=a.bedY(x,z,0)+1;y<=100;y++)
            assertTrue(forward.contains(new BlockPos(x,y,z)),"lake hole at "+x+","+y+","+z);
        assertFalse(forward.contains(new BlockPos(16,a.bedY(16,16,0),16)),"solid bed must not be replaced by water");
    }
    @Test void existingWaterDoesNotLeaveHolesOnRepeatedNeighbourDecoration() {
        var a=PreparedWaterArea.plan(7,0,0,bowl(64));
        var columns=new RiverWaterPlanning.ColumnAccess() {
            public boolean isAir(int x,int y,int z){return y>100;}
            public boolean isWater(int x,int y,int z){return a.waterY(x,z)!=PreparedWaterArea.NONE&&y>=86&&y<=100;}
            public boolean isSolidDry(int x,int y,int z){return y<a.carvedHeight(x,z,101);}
        };
        assertEquals(chunkWaterVolume(a,16,16),RiverWaterPlanning.planChunk(a,1,1,-64,320,columns).size());
    }
    @Test void unsealedDryWallIsRejected() {
        var a=PreparedWaterArea.plan(7,0,0,bowl(64));
        int wx=0,wz=0;
        outer: for(int z=8;z<56;z++) for(int x=8;x<56;x++)
            if(a.waterY(x,z)!=PreparedWaterArea.NONE&&a.waterY(x-1,z)==PreparedWaterArea.NONE) {wx=x;wz=z;break outer;}
        assertNotEquals(0,wx);
        final int holeX=wx-1;
        var columns=new RiverWaterPlanning.ColumnAccess() {
            public boolean isAir(int x,int y,int z){return y>=a.carvedHeight(x,z,101);}
            public boolean isSolidDry(int x,int y,int z){return x!=holeX&&!isAir(x,y,z);}
        };
        assertFalse(FluidContainment.canPlace(FluidContainment.Fluid.WATER,a,wx,wz,a.bedY(wx,wz,0),a.waterY(wx,wz),-64,320,columns));
    }
    @Test void controllerRejectsUnsafeFrontierBeforeDoingWorkAndRetiresCleanly() throws Exception {
        var session=WorldSession.activate(GenerationProfile.defaultsFor(2,"model","cpu","cpu"),7);
        try(var controller=new PreparedWaterController(session,temp,300)) {
            session.attachPreparedWater(controller);
            assertThrows(IllegalStateException.class,()->controller.start(new PreparedWaterController.Bounds(1024,1024,512),
                ()->{throw new IllegalStateException("already generated");},Runnable::run));
            assertEquals(321,controller.carvedHeight(1,1,321));
        } finally {WorldSession.deactivate(session);}
    }
    @Test void boundsAreAlignedBoundedAndNegativeSafe() {
        var b=PreparedWaterController.Bounds.around(-1,-1,256);
        assertEquals(-512,b.x());assertEquals(-512,b.z());assertEquals(1024,b.side());
        assertThrows(IllegalArgumentException.class,()->PreparedWaterController.Bounds.around(0,0,10000));
        assertThrows(IllegalArgumentException.class,()->new PreparedWaterController.Bounds(Integer.MIN_VALUE,0,512));
    }
    @Test void liningSealsCaveHolesInLakeFloorAndShoreWithOneColumnHalo() {
        var a=PreparedWaterArea.plan(7,0,0,bowl(64));
        Set<BlockPos> lining=new HashSet<>();
        WaterLining.forChunk(a,16,16,-64,320,(x,y,z,bed)->lining.add(new BlockPos(x,y,z)));
        assertTrue(lining.contains(new BlockPos(16,a.bedY(16,16,0),16)));
        assertTrue(lining.contains(new BlockPos(15,a.bedY(15,16,0),16)),"neighbouring chunk floor must also be sealed");
        assertTrue(lining.stream().allMatch(p->p.getX()>=15&&p.getX()<=32&&p.getZ()>=15&&p.getZ()<=32));
        var columns=new RiverWaterPlanning.ColumnAccess() {
            public boolean isAir(int x,int y,int z){return y>=a.carvedHeight(x,z,101)||y==a.bedY(x,z,0);}
            public boolean isSolidDry(int x,int y,int z){return lining.contains(new BlockPos(x,y,z))||!isAir(x,y,z);}
        };
        assertEquals(chunkWaterVolume(a,16,16),RiverWaterPlanning.planChunk(a,1,1,-64,320,columns).size());
    }
    private static int chunkWaterVolume(PreparedWaterArea area,int baseX,int baseZ) {
        int volume=0;
        for(int z=baseZ;z<baseZ+16;z++) for(int x=baseX;x<baseX+16;x++)
            if(area.waterY(x,z)!=PreparedWaterArea.NONE) volume+=area.waterY(x,z)-area.bedY(x,z,0);
        return volume;
    }
    @Test void steppedRiverCanFlowIntoLowerPlannedWater() {
        int[] air=new int[64*64],water=new int[64*64];Arrays.fill(air,110);Arrays.fill(water,PreparedWaterArea.NONE);
        water[16*64+15]=105;air[16*64+15]=103;
        water[16*64+16]=100;air[16*64+16]=98;
        var a=new PreparedWaterArea(0,0,64,air,water);
        var columns=new RiverWaterPlanning.ColumnAccess() {
            public boolean isAir(int x,int y,int z){return y>=a.carvedHeight(x,z,110);}
            public boolean isSolidDry(int x,int y,int z){return !isAir(x,y,z);}
        };
        assertTrue(RiverWaterPlanning.planChunk(a,0,1,-64,320,columns).contains(new BlockPos(15,105,16)));
        assertTrue(RiverWaterPlanning.planChunk(a,1,1,-64,320,columns).contains(new BlockPos(16,100,16)));
    }
    @Test void deepOceanOutsideWetMaskDoesNotInvalidatePlanning() {
        int[][] ocean=new int[64][64];for(var row:ocean) Arrays.fill(row,-100);
        var a=PreparedWaterArea.plan(1,0,0,ocean);
        assertEquals(-100,a.carvedHeight(16,16,999));assertEquals(0,a.wetColumns());
    }
    @Test void generatedChunkGuardRejectsInFlightChunksAndIndexedDiskChunks() throws Exception {
        assertFalse(GeneratedChunkGuard.check(temp,0,0,(x,z)->x==1&&z==1
            ?GeneratedChunkGuard.RuntimeState.PRESENT_OR_IN_FLIGHT:GeneratedChunkGuard.RuntimeState.ABSENT).safeToPublish());
        byte[] header=new byte[8192];header[3]=1;Files.write(temp.resolve("r.0.0.mca"),header);
        assertFalse(GeneratedChunkGuard.check(temp,0,0,(x,z)->GeneratedChunkGuard.RuntimeState.ABSENT).safeToPublish());
    }
    private WorldSession syntheticSession() throws Exception {
        var s=WorldSession.activate(GenerationProfile.defaultsFor(2,"model","cpu","cpu"),7);
        var repo=new TerrainRepository(new DiskTerrainStore(temp.resolve("terrain")),key->{
            int ts=key.tileSize();short[] heights=new short[ts*ts],biomes=new short[ts*ts];
            for(int z=0;z<ts;z++) for(int x=0;x<ts;x++) {
                int wx=key.blockStartX()+x,wz=key.blockStartZ()+z;
                heights[z*ts+x]=(short)(wx>=1120&&wz>=1120&&wx<1440&&wz<1440?300:600);
            }
            return new TerrainTile(key,heights,biomes);
        },256,16*1024*1024,1024L*1024*1024,s);
        s.attachRepository(repo);return s;
    }
    private static void awaitStatus(PreparedWaterController water,String expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!water.status().contains(expected)&&System.nanoTime()<deadline) Thread.sleep(10);
        assertTrue(water.status().contains(expected),water.status());
    }
    @Test void backgroundPreparationCommitsAndReloadsWithoutModelInference() throws Exception {
        var s=syntheticSession();var queue=new LinkedBlockingQueue<Runnable>();var checks=new AtomicInteger();
        try(var water=new PreparedWaterController(s,temp.resolve("water"),300)) {
            s.attachPreparedWater(water);
            water.start(new PreparedWaterController.Bounds(1024,1024,512),checks::incrementAndGet,queue::add);
            Runnable commit=queue.poll(30,TimeUnit.SECONDS);assertNotNull(commit,water.status());
            assertEquals(PreparedWaterArea.NONE,water.waterY(1200,1200),"uncommitted work must remain invisible");
            commit.run();awaitStatus(water,"Вода готова");
            assertEquals(2,checks.get());assertEquals(102,water.waterY(1200,1200));
            try(var restored=new PreparedWaterController(s,temp.resolve("water"),300)) {
                assertEquals(water.waterY(1200,1200),restored.waterY(1200,1200));
                assertEquals(water.carvedHeight(1200,1200,999),restored.carvedHeight(1200,1200,999));
                restored.close();
                assertEquals(102,restored.waterY(1200,1200),"shutdown must not change terrain while chunk work drains");
                assertThrows(IllegalStateException.class,()->restored.start(new PreparedWaterController.Bounds(4096,4096,512),()->{},Runnable::run));
            }
        } finally {WorldSession.deactivate(s);}
    }
    @Test void explorationDuringBackgroundPreparationPreventsPublication() throws Exception {
        var s=syntheticSession();var queue=new LinkedBlockingQueue<Runnable>();var clear=new AtomicBoolean(true);
        Path waterDir=temp.resolve("water");
        try(var water=new PreparedWaterController(s,waterDir,300)) {
            water.start(new PreparedWaterController.Bounds(1024,1024,512),()->{
                if(!clear.get()) throw new IllegalStateException("frontier generated during job");
            },queue::add);
            Runnable commit=queue.poll(30,TimeUnit.SECONDS);assertNotNull(commit,water.status());
            clear.set(false);commit.run();awaitStatus(water,"не завершена");
            assertEquals(PreparedWaterArea.NONE,water.waterY(1200,1200));
            assertFalse(Files.exists(waterDir),"rejected area must not be persisted");
        } finally {WorldSession.deactivate(s);}
    }
    @Test void savedWaterCannotActivateWithMissingBaseTerrain() throws Exception {
        var s=WorldSession.activate(GenerationProfile.defaultsFor(2,"model","cpu","cpu"),7);
        try {
            new PreparedWaterStore(temp,7,s.fingerprint()).save(PreparedWaterArea.plan(7,0,0,bowl(64)));
            assertThrows(java.io.IOException.class,()->new PreparedWaterController(s,temp,300));
        } finally {WorldSession.deactivate(s);}
    }
}

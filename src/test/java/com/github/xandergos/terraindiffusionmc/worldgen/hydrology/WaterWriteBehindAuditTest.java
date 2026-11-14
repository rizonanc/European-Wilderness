package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WaterWriteBehindAuditTest {
    @TempDir Path temp;
    private static final WorldWaterGrid GRID=WorldWaterGrid.DEFAULT;
    private static WorldWaterUnit unit(short level){return new WorldWaterUnit(GRID,new WorldWaterGrid.Cell(2,3),new int[]{500*GRID.unitSide()+500},new short[]{level},new byte[]{0},List.of(),2);}
    @Test void closeCannotLeaveAnOfferQueuedAfterTheWorkerHasExited() throws Exception {
        var store=new WorldWaterStore(temp,7,"fixture",256,GRID);var writer=new WaterWriteBehind(store,2);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var delayed=new ArrayBlockingQueue<Object>(2){@Override public boolean offer(Object value){entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}return super.offer(value);}};
        Field queue=WaterWriteBehind.class.getDeclaredField("queue");queue.setAccessible(true);queue.set(writer,delayed);
        Field closed=WaterWriteBehind.class.getDeclaredField("closed");closed.setAccessible(true);
        try(var pool=Executors.newFixedThreadPool(2)){
            var offered=pool.submit(()->writer.unit(unit((short)62)));assertTrue(entered.await(5,TimeUnit.SECONDS));
            var closing=pool.submit(writer::close);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(!closed.getBoolean(writer)&&System.nanoTime()<until)Thread.sleep(1);
            // On the broken implementation close completes before admission does.
            // With synchronized admission it must wait; release then allows both paths.
            if(closed.getBoolean(writer))closing.get(6,TimeUnit.SECONDS);
            release.countDown();offered.get(5,TimeUnit.SECONDS);closing.get(6,TimeUnit.SECONDS);
        }finally{release.countDown();writer.close();}
        assertTrue(store.hasUnit(unit((short)62).cell),"accepted save must reach disk or be counted as dropped");
        assertEquals(1,writer.flushedCount());assertEquals(0,writer.droppedCount());assertTrue(delayed.isEmpty());
    }
    @Test void corruptExistingFileIsNotAConfirmedDuplicate() throws Exception {
        Files.createDirectories(temp.resolve("units"));Path file=temp.resolve("units/2_3.eww");byte[] corrupt={1,2,3};Files.write(file,corrupt);
        var writer=new WaterWriteBehind(new WorldWaterStore(temp,7,"fixture",256,GRID),2);
        writer.unit(unit((short)62));writer.close();
        assertEquals(0,writer.duplicateCount());assertEquals(1,writer.droppedCount());assertArrayEquals(corrupt,Files.readAllBytes(file));
    }
    @Test void differentValidPlanCannotBeReportedAsTheSameFrozenData() throws Exception {
        var store=new WorldWaterStore(temp,7,"fixture",256,GRID);store.save(unit((short)63));
        Path file=temp.resolve("units/2_3.eww");byte[] before=Files.readAllBytes(file);
        var writer=new WaterWriteBehind(store,2);writer.unit(unit((short)62));writer.close();
        assertEquals(0,writer.duplicateCount());assertEquals(1,writer.droppedCount());assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test void identicalFrozenPlanIsVerifiedAsDuplicate() throws Exception {
        var store=new WorldWaterStore(temp,7,"fixture",256,GRID);store.save(unit((short)62));
        var writer=new WaterWriteBehind(store,2);writer.unit(unit((short)62));writer.close();
        assertEquals(1,writer.duplicateCount());assertEquals(0,writer.droppedCount());
    }
    @Test void failedWorkerClosesAdmissionAndLaterOffersFallBackToSynchronous() throws Exception {
        var store=new WorldWaterStore(temp,7,"fixture",256,GRID);var writer=new WaterWriteBehind(store,2);
        var failing=new ArrayBlockingQueue<Object>(2){@Override public Object poll(long time,TimeUnit units){throw new AssertionError("injected poll failure");}};
        Field queue=WaterWriteBehind.class.getDeclaredField("queue");queue.setAccessible(true);queue.set(writer,failing);
        Field worker=WaterWriteBehind.class.getDeclaredField("worker");worker.setAccessible(true);
        ((Thread)worker.get(writer)).join(3000);assertFalse(((Thread)worker.get(writer)).isAlive());
        writer.unit(unit((short)62));writer.close();
        assertEquals(1,writer.flushedCount());assertTrue(store.hasUnit(unit((short)62).cell));
    }
}

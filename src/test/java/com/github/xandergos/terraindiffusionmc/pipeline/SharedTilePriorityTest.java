package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.Priority.*;

class SharedTilePriorityTest {
    @TempDir Path temp;
    @Test void foregroundReaderPromotesTheExistingBackgroundTileWithoutDuplicateInference() throws Exception {
        var readers=Executors.newFixedThreadPool(2);var release=new CountDownLatch(1);
        try(var worker=new InferenceThread("shared-tile-test")) {
            var started=new CountDownLatch(1);worker.submit(()->{started.countDown();release.await();return 0;});assertTrue(started.await(5,TimeUnit.SECONDS));
            var queued=new CountDownLatch(1);var demand=new AtomicReference<InferenceDemand>();var count=new AtomicInteger();List<String> order=Collections.synchronizedList(new ArrayList<>());
            var key=TerrainTileKey.ofTile(7,0,0,16,"shared-priority");
            var repo=new TerrainRepository(new DiskTerrainStore(temp),k->{
                count.incrementAndGet();demand.set(InferenceDemand.current());
                var result=worker.submit(()->{order.add("tile");return new TerrainTile(k,new short[256],new short[256]);});queued.countDown();return result.get();
            },16,1024*1024,16L*1024*1024);
            var background=readers.submit(()->{try(var ignored=InferenceDemand.scope(DISTANT)){return repo.get(key);}});
            assertTrue(queued.await(5,TimeUnit.SECONDS));
            var near=worker.submit(new InferenceDemand(NEAR),()->true,()->order.add("near"));
            var foreground=readers.submit(()->repo.get(key));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(demand.get().priority()!=FOREGROUND&&System.nanoTime()<deadline)Thread.onSpinWait();
            assertEquals(FOREGROUND,demand.get().priority());release.countDown();
            assertSame(background.get(5,TimeUnit.SECONDS),foreground.get(5,TimeUnit.SECONDS));near.get(5,TimeUnit.SECONDS);
            assertEquals(1,count.get());assertEquals(List.of("tile","near"),order);
        }finally{release.countDown();readers.shutdownNow();}
    }
    @Test void interruptedReaderDoesNotRemoveAnotherReadersRunningFlight() throws Exception {
        var threads=Executors.newFixedThreadPool(2);var release=new CountDownLatch(1);var entered=new CountDownLatch(1);var readerEntered=new CountDownLatch(1);
        var count=new AtomicInteger();var key=TerrainTileKey.ofTile(7,0,0,16,"interrupted-flight");
        var repo=new TerrainRepository(new DiskTerrainStore(temp),k->{count.incrementAndGet();entered.countDown();release.await();return new TerrainTile(k,new short[256],new short[256]);},16,1024*1024,16L*1024*1024);
        try {
            var owner=threads.submit(()->repo.get(key));assertTrue(entered.await(5,TimeUnit.SECONDS));
            var failure=new AtomicReference<Throwable>();
            var waiter=new Thread(()->{readerEntered.countDown();try{assertThrows(InterruptedException.class,()->repo.get(key));}catch(Throwable error){failure.set(error);}});
            waiter.start();assertTrue(readerEntered.await(5,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(waiter.getState()!=Thread.State.WAITING&&waiter.isAlive()&&System.nanoTime()<deadline)Thread.onSpinWait();
            waiter.interrupt();waiter.join(5000);assertFalse(waiter.isAlive());assertNull(failure.get());
            var secondThread=new AtomicReference<Thread>();var secondStarted=new CountDownLatch(1);
            var second=threads.submit(()->{secondThread.set(Thread.currentThread());secondStarted.countDown();return repo.get(key);});
            assertTrue(secondStarted.await(5,TimeUnit.SECONDS));deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(secondThread.get().getState()!=Thread.State.WAITING&&!second.isDone()&&System.nanoTime()<deadline)Thread.onSpinWait();
            assertEquals(1,count.get(),"interrupted waiter must not detach the original flight");release.countDown();
            assertSame(owner.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));assertEquals(1,count.get());
        }finally{release.countDown();threads.shutdownNow();}
    }
}

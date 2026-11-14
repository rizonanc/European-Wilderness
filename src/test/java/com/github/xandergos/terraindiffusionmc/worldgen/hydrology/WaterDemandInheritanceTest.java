package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.pipeline.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.Priority.*;

class WaterDemandInheritanceTest {
    @Test void foregroundJoiningAnotherCorePromotesItsAlreadyJoinedUnitAndQueuedRawDependency() throws Exception {
        var release=new CountDownLatch(1);var running=new CountDownLatch(1);var rawQueued=new CountDownLatch(1);var otherJoined=new CountDownLatch(1);
        var cores=new WaterPlanCache<String,Integer>(4);var units=new WaterPlanCache<String,Integer>(4);
        var ownerDemand=new AtomicReference<InferenceDemand>();var otherDemand=new AtomicReference<InferenceDemand>();
        var rawCount=new AtomicInteger();var order=Collections.synchronizedList(new ArrayList<String>());
        var callers=Executors.newFixedThreadPool(3);
        try(var worker=new InferenceThread("water-priority-proof")){
            worker.submit(()->{running.countDown();release.await();return 0;});assertTrue(running.await(5,TimeUnit.SECONDS));
            var first=callers.submit(()->{try(var ignored=InferenceDemand.scope(DISTANT)){
                return cores.get("a",()->units.get("shared",()->{
                    ownerDemand.set(InferenceDemand.current());
                    var future=worker.submit(()->{rawCount.incrementAndGet();order.add("raw");return 7;});rawQueued.countDown();return future.get();
                }));
            }});assertTrue(rawQueued.await(5,TimeUnit.SECONDS));
            var near=worker.submit(new InferenceDemand(NEAR),()->true,()->order.add("near"));
            var other=callers.submit(()->{try(var ignored=InferenceDemand.scope(DISTANT)){
                return cores.get("b",()->{otherDemand.set(InferenceDemand.current());otherJoined.countDown();return units.get("shared",()->{fail("duplicate unit");return 0;});});
            }});assertTrue(otherJoined.await(5,TimeUnit.SECONDS));
            var foreground=callers.submit(()->cores.get("b",()->{fail("duplicate core");return 0;}));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(ownerDemand.get().priority()!=FOREGROUND&&System.nanoTime()<deadline)Thread.sleep(1);
            try{
                assertEquals(FOREGROUND,otherDemand.get().priority());assertEquals(FOREGROUND,ownerDemand.get().priority());
            }finally{release.countDown();}
            assertEquals(7,first.get(5,TimeUnit.SECONDS));assertEquals(7,other.get(5,TimeUnit.SECONDS));assertEquals(7,foreground.get(5,TimeUnit.SECONDS));near.get(5,TimeUnit.SECONDS);
            assertEquals(1,rawCount.get());assertEquals(List.of("raw","near"),order);
        }finally{release.countDown();callers.shutdownNow();}
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoarseUpdateQueueTest {
    private static long key(int level,int n){return ((long)level<<60)|((long)n<<4);}
    @Test void rootsPassBeforeALargeFineNeighbourBacklogAndCleanupKeepsItsOwnShare() {
        var queue=new CoarseUpdateQueue(100010,16);
        for(int i=0;i<100000;i++)assertTrue(queue.offer(key(0,i),false));
        queue.offer(key(1,0),true);queue.offer(key(4,0),false);queue.offer(key(4,1),false);
        var selected=queue.select(4,k->true,k->true);
        assertEquals(4,selected.size());assertTrue(selected.getFirst().forced());
        assertEquals(4,selected.get(1).key()>>>60);assertEquals(4,selected.get(2).key()>>>60);
        assertEquals(0,selected.get(3).key()>>>60);
    }
    @Test void unfinishedRootsDoNotPreventReadyNearSectorsFromUpdating() {
        var queue=new CoarseUpdateQueue(1000,16);
        for(int i=0;i<600;i++)queue.offer(key(4,i),false);queue.offer(key(0,1),false);
        var selected=queue.select(8,k->true,k->(k>>>60)==0);
        assertEquals(1,selected.size());assertEquals(key(0,1),selected.getFirst().key());
    }
    @Test void promotionAndReinsertionCannotBeAcknowledgedByAnOlderSelection() {
        var queue=new CoarseUpdateQueue(10,4);long key=key(4,1);queue.offer(key,false);
        var old=queue.select(1,k->true,k->true).getFirst();queue.offer(key,true);
        assertFalse(queue.complete(old));var cleanup=queue.select(1,k->true,k->false).getFirst();
        assertTrue(cleanup.forced());assertTrue(queue.complete(cleanup));assertEquals(0,queue.size());
        queue.offer(key,true);assertFalse(queue.complete(cleanup));assertEquals(1,queue.size());
        assertTrue(queue.complete(queue.select(1,k->true,k->false).getFirst()));assertEquals(0,queue.size());
    }
    @Test void boundedUrgentOverflowRemainsDiscoverableAndCancelledSelectionStaysPending() {
        var queue=new CoarseUpdateQueue(8,1);
        for(int i=0;i<8;i++)assertTrue(queue.offer(key(4,i),true));
        assertFalse(queue.offer(key(4,8),true));assertTrue(queue.offer(key(4,0),true));assertEquals(8,queue.size());
        assertEquals(2,queue.select(2,k->true,k->false).size());assertEquals(8,queue.size());
        int completed=0;for(int round=0;round<10;round++)for(var task:queue.select(3,k->true,k->false))if(queue.complete(task))completed++;
        assertEquals(8,completed);assertEquals(0,queue.size());
    }
    @Test void movementRetiresOutOfRangeWorkAndScanHasAFinitePerLevelBudget() {
        var queue=new CoarseUpdateQueue(4000,1);for(int i=0;i<1000;i++)queue.offer(key(4,i),false);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(queue.select(64,k->true,k->{calls.incrementAndGet();return false;}).isEmpty());assertEquals(512,calls.get());
        queue.select(64,k->false,k->true);assertTrue(queue.size()>0);
        for(int i=0;i<5;i++)queue.select(64,k->false,k->true);assertEquals(0,queue.size());
    }
    @Test void interruptedPublishRetainsCleanupPriorityAheadOfRoots() {
        var queue=new CoarseUpdateQueue(100010,8);
        for(int i=0;i<100000;i++)queue.offer(key(4,i),false);queue.offer(key(0,0),true);
        var borrowed=queue.select(4,k->true,k->true);assertEquals(0,borrowed.getFirst().key()>>>60);
        queue.retryUrgent(borrowed);var retried=queue.select(4,k->true,k->true);
        assertSame(borrowed.getFirst(),retried.getFirst());assertEquals(retried.size(),new HashSet<>(retried).size());
        assertEquals(100001,queue.size());
    }
    @Test void concurrentProducersDeduplicateWithoutLosingTheBound() throws Exception {
        var queue=new CoarseUpdateQueue(1000,8);
        try(var threads=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<java.util.concurrent.Future<?>>();
            for(int n=0;n<4;n++){final boolean forced=n%2==0;jobs.add(threads.submit(()->{for(int i=0;i<4000;i++)queue.offer(key(i%5,i%2000),forced);}));}
            for(var job:jobs)job.get();
        }
        assertEquals(1000,queue.size());assertEquals(1000,java.util.stream.IntStream.range(0,5).map(queue::levelSize).sum());
        for(int n=0;n<100&&queue.size()>0;n++)for(var task:queue.select(64,k->true,k->true))assertTrue(queue.complete(task));
        assertEquals(0,queue.size());
    }
    @Test void healthySustainedLowFpsDoesNotApplyTheSpikeQuotaAndBacklogPausesNewWork() {
        var clock=new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var frames=new com.github.xandergos.terraindiffusionmc.worldgen.preparation.FrameBudget(clock::get);
        for(int i=0;i<1000;i++){clock.addAndGet(33_000_000);frames.observe(33_000_000,25_000_000);}
        assertEquals(50_000_000,frames.transferInterval());assertEquals(0,frames.pressure());
        // Phase 5.3: the budget is adaptive (stateful, additive ramp from 16); mesh-queue
        // caps are unchanged, and severe pressure keeps a drain floor instead of 1.
        var budget=new CoarseUpdateBudget();
        for(int i=0;i<64&&budget.allowance()<64;i++)budget.quota(frames.pressure(),0);
        assertEquals(64,budget.allowance(),"healthy 33 ms frames reach the full quota");
        assertEquals(0,budget.quota(0,512));
        budget.quota(0,256); // multiplicative decrease converges to the cap in two cycles
        assertTrue(budget.quota(0,256)<=4);
        budget.quota(0,128);
        assertTrue(budget.quota(0,128)<=16);
        assertTrue(budget.quota(0,-1)<=16);
        var severe=new CoarseUpdateBudget();
        int severeQuota=severe.quota(4,0);
        assertTrue(severeQuota>=2&&severeQuota<=8,"pressure 4 keeps a drain floor with a free mesh queue");
    }
}

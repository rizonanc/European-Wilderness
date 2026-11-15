package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.Priority.*;

class InferencePriorityTest {
    private static void await(CountDownLatch latch) throws Exception {
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void queuedForegroundOvertakesNearAndDistantButDoesNotInterruptTheRunningJob() throws Exception {
        try (var worker = new InferenceThread("priority-test")) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var order = Collections.synchronizedList(new ArrayList<String>());
            var running =
                    worker.submit(() -> {
                        entered.countDown();
                        release.await();
                        order.add("running");
                        return 0;
                    });
            await(entered);
            try {
                var far = worker.submit(new InferenceDemand(DISTANT), () -> true, () -> order.add("far"));
                var near = worker.submit(new InferenceDemand(NEAR), () -> true, () -> order.add("near"));
                var fg = worker.submit(new InferenceDemand(FOREGROUND), () -> true, () -> order.add("foreground"));
                release.countDown();
                running.get(5, TimeUnit.SECONDS);
                fg.get(5, TimeUnit.SECONDS);
                near.get(5, TimeUnit.SECONDS);
                far.get(5, TimeUnit.SECONDS);
                assertEquals(List.of("running", "foreground", "near", "far"), order);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void queuedSharedDemandIsPromotedAndPromotionBeforeBindingIsNotLost() throws Exception {
        try (var worker = new InferenceThread("promotion-test")) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            worker.submit(() -> {
                entered.countDown();
                release.await();
                return 0;
            });
            await(entered);
            try {
                List<String> order = Collections.synchronizedList(new ArrayList<>());
                var demand = new InferenceDemand(DISTANT);
                var first = worker.submit(demand, () -> true, () -> order.add("promoted"));
                var near = worker.submit(new InferenceDemand(NEAR), () -> true, () -> order.add("near"));
                demand.promote(FOREGROUND);
                demand.promote(DISTANT);
                var early = new InferenceDemand(DISTANT);
                early.promote(FOREGROUND);
                var second = worker.submit(early, () -> true, () -> order.add("early"));
                release.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                near.get(5, TimeUnit.SECONDS);
                assertEquals(List.of("promoted", "early", "near"), order);
                assertTrue(worker.timings().contains("promoted=1"));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void retiredWorldAndCancelledQueuedJobsNeverExecuteAndTheWorkerSurvivesFailure() throws Exception {
        try (var worker = new InferenceThread("stale-test")) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            worker.submit(() -> {
                entered.countDown();
                release.await();
                return 0;
            });
            await(entered);
            try {
                var valid = new AtomicBoolean(true);
                var stale =
                        worker.submit(
                                new InferenceDemand(DISTANT),
                                valid::get,
                                () -> {
                                    fail("retired job ran");
                                    return 0;
                                });
                var cancelled =
                        worker.submit(() -> {
                            fail("cancelled job ran");
                            return 0;
                        });
                cancelled.cancel(true);
                valid.set(false);
                release.countDown();
                assertInstanceOf(
                        CancellationException.class,
                        assertThrows(ExecutionException.class, () -> stale.get(5, TimeUnit.SECONDS)).getCause());
                assertThrows(CancellationException.class, cancelled::get);
                assertEquals(42, worker.submit(() -> 42).get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void nestedScopesRestorePriorityEvenAfterFailure() throws Exception {
        assertEquals(FOREGROUND, InferenceDemand.currentPriority());
        try (var ignored = InferenceDemand.scope(DISTANT)) {
            assertEquals(DISTANT, InferenceDemand.currentPriority());
            assertThrows(
                    IllegalStateException.class,
                    () -> {
                        try (var nested = InferenceDemand.scope(NEAR)) {
                            throw new IllegalStateException();
                        }
                    });
            assertEquals(DISTANT, InferenceDemand.currentPriority());
        }
        assertEquals(FOREGROUND, InferenceDemand.currentPriority());
    }
}

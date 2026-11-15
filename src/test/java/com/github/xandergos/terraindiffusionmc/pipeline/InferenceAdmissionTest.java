package com.github.xandergos.terraindiffusionmc.pipeline;

import static com.github.xandergos.terraindiffusionmc.pipeline.InferenceDemand.Priority.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class InferenceAdmissionTest {
    private static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }

    private static Future<Integer> block(InferenceThread worker, CountDownLatch release) throws Exception {
        var entered = new CountDownLatch(1);
        var result = worker.submit(new InferenceDemand(FOREGROUND), () -> true, 1, () -> {
            entered.countDown();
            release.await();
            return 1;
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        return result;
    }

    @Test
    void testForegroundReserveProtectsRequiredWork() throws Exception {
        var release = new CountDownLatch(1);
        var order = Collections.synchronizedList(new ArrayList<String>());
        var submitters = Executors.newSingleThreadExecutor();
        try (var worker = new InferenceThread("bounded", 4, 100, 1)) {
            block(worker, release);
            var a = worker.submit(new InferenceDemand(NEAR), () -> true, 10, () -> order.add("a"));
            var b = worker.submit(new InferenceDemand(NEAR), () -> true, 10, () -> order.add("b"));
            var waiting = submitters.submit(
                    () -> worker.submit(new InferenceDemand(NEAR), () -> true, 10, () -> order.add("waiting"))
                            .get());
            until(() -> worker.snapshot().admissionWaiters() == 1);
            var fg = worker.submit(new InferenceDemand(FOREGROUND), () -> true, 10, () -> order.add("foreground"));
            assertEquals(4, worker.snapshot().pendingJobs());
            assertEquals(4, worker.snapshot().highWaterJobs());
            release.countDown();
            fg.get(5, TimeUnit.SECONDS);
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            waiting.get(5, TimeUnit.SECONDS);
            assertEquals("foreground", order.getFirst());
            assertEquals(4, order.size());
            until(() -> worker.snapshot().pendingJobs() == 0);
        } finally {
            release.countDown();
            submitters.shutdownNow();
        }
    }

    @Test
    void testByteBudgetDefersOptionalWork() throws Exception {
        var release = new CountDownLatch(1);
        var callers = Executors.newSingleThreadExecutor();
        try (var worker = new InferenceThread("bytes", 4, 100, 1)) {
            block(worker, release);
            var optional = worker.submitOptional(new InferenceDemand(DISTANT), () -> true, 80, () -> {
                fail("optional exceeded byte budget");
                return 0;
            });
            assertThrows(CancellationException.class, optional::get);
            assertEquals(1, worker.snapshot().optionalDeferred());
            var large = callers.submit(() -> worker.submit(new InferenceDemand(FOREGROUND), () -> true, 150, () -> {
                        assertEquals(1, worker.snapshot().pendingJobs());
                        return 42;
                    })
                    .get());
            until(() -> worker.snapshot().admissionWaiters() == 1);
            assertEquals(1, worker.snapshot().pendingBytes());
            release.countDown();
            assertEquals(42, large.get(5, TimeUnit.SECONDS));
            until(() -> worker.snapshot().pendingJobs() == 0);
            assertEquals(150, worker.snapshot().highWaterBytes());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void retiredQueuedRequestsAreRemovedBeforeWorkerBecomesAvailable() throws Exception {
        var release = new CountDownLatch(1);
        var valid = new AtomicBoolean(true);
        try (var worker = new InferenceThread("retired", 4, 100, 1)) {
            block(worker, release);
            var queued = worker.submit(new InferenceDemand(FOREGROUND), valid::get, 50, () -> {
                fail("retired work executed");
                return 0;
            });
            assertEquals(51, worker.snapshot().pendingBytes());
            valid.set(false);
            worker.discardStale();
            assertInstanceOf(
                    CancellationException.class,
                    assertThrows(ExecutionException.class, queued::get).getCause());
            assertEquals(1, worker.snapshot().pendingBytes());
            assertEquals(0, worker.snapshot().queuedJobs());
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationOfRunningNativeWorkDoesNotReleaseItsChargeOrInterruptIt() throws Exception {
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        try (var worker = new InferenceThread("native-cancel", 4, 100, 1)) {
            var entered = new CountDownLatch(1);
            var running = worker.submit(new InferenceDemand(FOREGROUND), () -> true, 70, () -> {
                entered.countDown();
                try {
                    release.await();
                    assertFalse(Thread.currentThread().isInterrupted());
                    return 1;
                } finally {
                    finished.countDown();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(running.cancel(true));
            assertEquals(70, worker.snapshot().pendingBytes());
            assertEquals(1, worker.snapshot().activeJobs());
            assertTrue(worker.snapshot().oldestForegroundAgeNanos() > 0);
            assertEquals(0, worker.snapshot().oldestForegroundWaitNanos());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            until(() -> worker.snapshot().pendingJobs() == 0);
            assertEquals(0, worker.snapshot().pendingBytes());
        } finally {
            release.countDown();
        }
    }

    @Test
    void testCancellingQueuedWorkFreesItsSlotAndBytes() throws Exception {
        var release = new CountDownLatch(1);
        try (var worker = new InferenceThread("queued-cancel", 4, 100, 1)) {
            block(worker, release);
            var queued = worker.submit(new InferenceDemand(FOREGROUND), () -> true, 60, () -> 0);
            assertTrue(queued.cancel(true));
            assertEquals(1, worker.snapshot().pendingJobs());
            assertEquals(1, worker.snapshot().pendingBytes());
            assertEquals(0, worker.snapshot().queuedJobs());
        } finally {
            release.countDown();
        }
    }

    @Test
    void testAgingProtectsRequiredBackgroundWork() throws Exception {
        var clock = new AtomicLong(1);
        var release = new CountDownLatch(1);
        var order = new ArrayList<String>();
        try (var worker = new InferenceThread("age", 8, 1000, 1, clock::get)) {
            block(worker, release);
            var requiredDemand = new InferenceDemand(NEAR);
            var required = worker.submit(requiredDemand, () -> true, 10, () -> order.add("aged"));
            var optional = worker.submitOptional(new InferenceDemand(DISTANT), () -> true, 10, () -> {
                fail("expired preview ran");
                return 0;
            });
            var foreground =
                    worker.submit(new InferenceDemand(FOREGROUND), () -> true, 10, () -> order.add("foreground"));
            clock.addAndGet(TimeUnit.SECONDS.toNanos(11));
            worker.discardStale();
            assertEquals(FOREGROUND, requiredDemand.priority());
            assertTrue(optional.isDone());
            assertEquals(TimeUnit.SECONDS.toNanos(11), worker.snapshot().oldestForegroundWaitNanos());
            release.countDown();
            required.get(5, TimeUnit.SECONDS);
            foreground.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("aged", "foreground"), order);
        } finally {
            release.countDown();
        }
    }

    @Test
    void promotionOfOneSharedDemandReordersEveryQueuedJobEvenAfterAnotherCompletes() throws Exception {
        var release = new CountDownLatch(1);
        var order = new ArrayList<String>();
        try (var worker = new InferenceThread("listeners", 8, 1000, 1)) {
            block(worker, release);
            var shared = new InferenceDemand(DISTANT);
            var cancelled = worker.submit(shared, () -> true, 10, () -> 0);
            cancelled.cancel(false);
            var a = worker.submit(shared, () -> true, 10, () -> order.add("a"));
            var b = worker.submit(shared, () -> true, 10, () -> order.add("b"));
            var near = worker.submit(new InferenceDemand(NEAR), () -> true, 10, () -> order.add("near"));
            shared.promote(FOREGROUND);
            release.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            near.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("a", "b", "near"), order);
        } finally {
            release.countDown();
        }
    }

    @Test
    void testRetirementWhileAwaitingAdmissionTerminates() throws Exception {
        var release = new CountDownLatch(1);
        var valid = new AtomicBoolean(true);
        var callers = Executors.newSingleThreadExecutor();
        try (var worker = new InferenceThread("retired-waiter", 2, 100, 1)) {
            block(worker, release);
            var waiting = callers.submit(() -> worker.submit(new InferenceDemand(NEAR), valid::get, 10, () -> 0)
                    .get());
            until(() -> worker.snapshot().admissionWaiters() == 1);
            valid.set(false);
            assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS));
            until(() -> worker.snapshot().admissionWaiters() == 0);
            assertEquals(1, worker.snapshot().pendingJobs());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void testNestedSubmissionFailsPromptly() throws Exception {
        try (var worker = new InferenceThread("recursive")) {
            var failed = worker.submit(() -> worker.submit(() -> 1).get());
            assertInstanceOf(
                    RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS))
                            .getCause());
            assertEquals(42, worker.submit(() -> 42).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void testCloseWakesBlockedAdmission() throws Exception {
        var release = new CountDownLatch(1);
        var callers = Executors.newSingleThreadExecutor();
        var worker = new InferenceThread("close", 2, 100, 1);
        try {
            var running = block(worker, release);
            var waiting = callers.submit(() -> worker.submit(new InferenceDemand(NEAR), () -> true, 10, () -> 0));
            until(() -> worker.snapshot().admissionWaiters() == 1);
            worker.close();
            assertInstanceOf(
                    RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> waiting.get(5, TimeUnit.SECONDS))
                            .getCause());
            release.countDown();
            assertEquals(1, running.get(5, TimeUnit.SECONDS));
            until(() -> worker.snapshot().pendingJobs() == 0);
        } finally {
            release.countDown();
            worker.close();
            callers.shutdownNow();
        }
    }

    @Test
    void testResultEstimatesRejectInvalidBounds() {
        assertEquals(4096L + 24 * 256 * 256, LocalTerrainProvider.estimateRegionBytes(-256, -256, 0, 0, 24));
        assertThrows(IllegalArgumentException.class, () -> LocalTerrainProvider.estimateRegionBytes(1, 0, 0, 1, 24));
        assertThrows(
                ArithmeticException.class,
                () -> LocalTerrainProvider.estimateRegionBytes(
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 24));
    }

    @Test
    void testManyRequiredProducersComplete() throws Exception {
        var workers = Executors.newFixedThreadPool(20);
        var completed = new AtomicInteger();
        try (var worker = new InferenceThread("stress", 6, 600, 1)) {
            var jobs = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++)
                jobs.add(workers.submit(() -> {
                    for (int n = 0; n < 20; n++)
                        assertEquals(
                                1,
                                worker.submit(
                                                new InferenceDemand(n % 2 == 0 ? FOREGROUND : NEAR),
                                                () -> true,
                                                100,
                                                () -> {
                                                    completed.incrementAndGet();
                                                    return 1;
                                                })
                                        .get(10, TimeUnit.SECONDS));
                    return null;
                }));
            for (var job : jobs) job.get(20, TimeUnit.SECONDS);
            assertEquals(400, completed.get());
            until(() -> worker.snapshot().pendingJobs() == 0);
            assertTrue(worker.snapshot().highWaterJobs() <= 6);
            assertTrue(worker.snapshot().highWaterBytes() <= 600);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void testInterruptedAdmissionRestoresInterrupt() throws Exception {
        var release = new CountDownLatch(1);
        var result = new AtomicReference<Future<Integer>>();
        var restored = new AtomicBoolean();
        try (var worker = new InferenceThread("interrupt", 2, 100, 1)) {
            block(worker, release);
            var caller = new Thread(() -> {
                result.set(worker.submit(new InferenceDemand(NEAR), () -> true, 10, () -> 42));
                restored.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            until(() -> worker.snapshot().admissionWaiters() == 1);
            caller.interrupt();
            release.countDown();
            caller.join(5000);
            assertFalse(caller.isAlive());
            assertTrue(restored.get());
            assertNotNull(result.get());
            assertEquals(42, result.get().get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class FrameBudgetTest {
    @Test
    void testRecurringJitterSlowsPreparation() {
        var now = new AtomicLong(1_000_000_000);
        var budget = new FrameBudget(now::get);
        for (int i = 0; i < 100; i++) frame(budget, now, 7);
        int admitted = 0;
        for (int i = 0; i < 1600; i++) {
            boolean spike = i % 50 == 0;
            frame(budget, now, spike ? 30 : 7);
            if (spike) assertFalse(budget.claimPreparation(), "never start in the immediate backoff window");
            if (budget.claimPreparation()) {
                admitted++;
                assertFalse(budget.claimPreparation(), "producers must share one token");
            }
        }
        assertEquals(4, budget.lastSpike().pressure());
        assertTrue(admitted >= 4 && admitted <= 12, "must make limited progress despite recurrent jitter: " + admitted);
    }

    @Test
    void testAllBackgroundProducersShareOneAdmission() {
        var now = new AtomicLong(1_000_000_000);
        var budget = new FrameBudget(now::get);
        assertFalse(budget.claimPreparation());
        budget.observe(16_000_000, 5_000_000);
        assertTrue(budget.claimPreparation());
        assertFalse(budget.claimPreparation());
        now.addAndGet(250_000_000);
        budget.observe(16_000_000, 5_000_000);
        assertTrue(budget.claimPreparation());
    }

    @Test
    void testFrameSpikeBacksOffThenRecovers() {
        var now = new AtomicLong(1_000_000_000);
        var budget = new FrameBudget(now::get);
        budget.observe(100_000_000, 80_000_000);
        assertFalse(budget.mayPublish());
        now.addAndGet(249_000_000);
        budget.observe(16_000_000, 5_000_000);
        assertFalse(budget.mayPublish());
        now.addAndGet(1_000_000);
        budget.observe(16_000_000, 5_000_000);
        assertTrue(budget.mayPublish());
        now.addAndGet(2_100_000_000L);
        assertFalse(budget.mayPublish());
        assertFalse(budget.mayPrepare());
    }

    @Test
    void consistentlySlowRenderingKeepsSmallTransfersButDefersHeavyPreparation() {
        var now = new AtomicLong(1_000_000_000);
        var budget = new FrameBudget(now::get);
        for (int i = 0; i < 150; i++) {
            now.addAndGet(50_000_000);
            budget.observe(50_000_000, 30_000_000);
        }
        assertTrue(budget.mayPublish());
        assertFalse(budget.mayPrepare());
        assertEquals(50_000_000, budget.transferInterval());
    }

    private static void frame(FrameBudget budget, AtomicLong now, long millis) {
        now.addAndGet(millis * 1_000_000);
        budget.observe(millis * 1_000_000, 3_000_000);
    }

    @Test
    void testHighFpsFrameSpikeTriggersBackoff() {
        var now = new AtomicLong(1_000_000_000);
        var budget = new FrameBudget(now::get);
        for (int i = 0; i < 150; i++) frame(budget, now, 7);
        assertEquals(8_000_000, budget.transferInterval());
        now.addAndGet(20_000_000);
        assertTrue(budget.observe(20_000_000, 4_000_000));
        assertTrue(budget.lastSpike().thresholdNanos() < 20_000_000);
        assertFalse(budget.mayPublish());
        assertEquals(32_000_000, budget.transferInterval());
        for (int i = 0; i < 12; i++) frame(budget, now, 7);
        assertTrue(budget.mayPublish());
        assertEquals(32_000_000, budget.transferInterval());
        assertTrue(budget.claimPreparation());
        assertFalse(budget.claimPreparation());
        for (int i = 0; i < 50; i++) frame(budget, now, 7);
        assertFalse(budget.claimPreparation(), "Heavy preparation must retain its longer cooldown after a spike");
        for (int i = 0; i < 300; i++) frame(budget, now, 7);
        assertTrue(budget.mayPrepare());
        assertEquals(8_000_000, budget.transferInterval());
    }

    @Test
    void repeatedSpikesSuspendSpeculationButSteadyLowerFpsEventuallyRecovers() {
        var now = new AtomicLong(1_000_000_000);
        var budget = new FrameBudget(now::get);
        for (int i = 0; i < 100; i++) frame(budget, now, 7);
        frame(budget, now, 25);
        frame(budget, now, 25);
        assertEquals(4, budget.lastSpike().pressure());
        assertFalse(budget.mayPrepare());
        for (int i = 0; i < 500; i++) frame(budget, now, 25);
        assertTrue(budget.mayPrepare(), "A sustained 40 FPS baseline must not permanently disable preparation");
        assertEquals(16_000_000, budget.transferInterval());
    }

    @Test
    void testRoutineFrameJitterDoesNotOscillateTheGate() {
        var now = new AtomicLong(1_000_000_000);
        var budget = new FrameBudget(now::get);
        for (int i = 0; i < 300; i++) {
            now.addAndGet((7 + i % 3) * 1_000_000);
            assertFalse(budget.observe((7 + i % 3) * 1_000_000, 4_000_000));
            assertTrue(budget.mayPublish());
        }
        budget.reset();
        assertFalse(budget.mayPublish());
        assertFalse(budget.claimPreparation());
        frame(budget, now, 16);
        assertTrue(budget.mayPublish());
    }

    @Test
    void testRenderCallSpikeIsDetectedEvenIfStartToStartGapWasShort() {
        var now = new AtomicLong(1_000_000_000);
        var budget = new FrameBudget(now::get);
        for (int i = 0; i < 100; i++) frame(budget, now, 7);
        now.addAndGet(22_000_000);
        assertTrue(budget.observe(7_000_000, 22_000_000));
        assertFalse(budget.mayPublish());
    }

    @Test
    void testMultiSecondStallsAreReported() {
        var now = new AtomicLong(1_000_000_000);
        var budget = new FrameBudget(now::get);
        frame(budget, now, 7);
        now.addAndGet(3_000_000_000L);
        assertTrue(budget.observe(3_000_000_000L, 3_000_000_000L));
        assertFalse(budget.mayPublish());
        now.addAndGet(250_000_000);
        budget.observe(7_000_000, 3_000_000);
        assertTrue(budget.mayPublish());
    }
}

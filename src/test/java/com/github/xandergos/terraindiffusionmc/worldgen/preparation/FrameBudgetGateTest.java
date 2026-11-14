package com.github.xandergos.terraindiffusionmc.worldgen.preparation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 5.1: the publication gate must be explainable and must recover from both
 * silence (no frames observed) and pressure (spike backoff) as soon as fresh cheap
 * frames arrive again — this is the regression for the confirmed alt-tab stall where
 * publish stayed false with coarse data fully ready. */
class FrameBudgetGateTest {
    private long now=1_000_000_000L;
    private final FrameBudget budget=new FrameBudget(()->now);

    private void frames(long gapNanos,int count) {
        for(int i=0;i<count;i++){now+=gapNanos;budget.observe(gapNanos,gapNanos/4);}
    }

    @Test void silentBudgetExplainsItselfAndRecovers() {
        assertEquals("silent",budget.heartbeatState());
        assertFalse(budget.mayPublish());
        // First observed frame immediately un-silences the gate.
        now+=8_000_000L;budget.observe(8_000_000L,2_000_000L);
        assertEquals("fresh",budget.heartbeatState());
        assertTrue(budget.mayPublish());
    }

    @Test void staleHeartbeatExplainsTheAltTabStallAndRecovers() {
        frames(8_000_000L,50);
        assertTrue(budget.mayPublish());
        now+=2_100_000_000L; // window silent for over two seconds
        assertEquals("stale",budget.heartbeatState());
        assertFalse(budget.mayPublish());
        // Fresh cheap frames arrive again (unfocused rendering continues) -> gate reopens.
        now+=8_000_000L;budget.observe(8_000_000L,2_000_000L);
        assertEquals("fresh",budget.heartbeatState());
        assertTrue(budget.mayPublish());
    }

    @Test void spikePressureExplainsItselfAndDecays() {
        frames(50_000_000L,4); // heavy gaps -> spikes -> pressure
        assertTrue(budget.pressure()>0);
        assertTrue(budget.pressureBlocked());
        assertFalse(budget.mayPublish());
        // Cheap frames after the block window: pressure decays once per healthy second.
        long until=now;now+=200_000_000L;
        frames(8_000_000L,1);
        while(budget.pressure()>0&&now<until+10_000_000_000L){now+=1_100_000_000L;budget.observe(8_000_000L,2_000_000L);}
        assertEquals(0,budget.pressure());
        assertFalse(budget.pressureBlocked());
        assertTrue(budget.mayPublish());
    }

    @Test void prepareFollowsPublishAndTypicalFrameTime() {
        frames(8_000_000L,50); // cheap frames: typical well under 40 ms
        assertTrue(budget.mayPublish());
        assertTrue(budget.mayPrepare());
        assertTrue(budget.claimPreparation());
        // Claim throttle: immediately after a claim the next claim is refused.
        assertFalse(budget.claimPreparation());
    }
}

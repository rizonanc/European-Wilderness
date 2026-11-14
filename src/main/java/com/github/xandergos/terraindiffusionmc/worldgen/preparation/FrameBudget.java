package com.github.xandergos.terraindiffusionmc.worldgen.preparation;
import java.util.function.LongSupplier;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.StageTimings;

/** CPU frame pacing signal, not a GPU timer. Shared admission for speculative work. */
public final class FrameBudget {
    private final LongSupplier clock;private final StageTimings timings=new StageTimings(8192);
    private long last,blockedUntil,nextPreparation,healthySince;private double typical=16_000_000;
    private int pressure;private long spikes;private Decision decision=new Decision(16_000_000,28_800_000,0);
    public record Decision(long baselineNanos,long thresholdNanos,int pressure) {}
    public FrameBudget(){this(System::nanoTime);} public FrameBudget(LongSupplier clock){this.clock=clock;}
    public synchronized boolean observe(long frameGap,long renderTime) {
        long now=clock.getAsLong();
        if(last!=0&&now-last>=2_000_000_000L) resetControl();
        last=now;
        long baseline=(long)typical,threshold=Math.max(12_000_000L,Math.max(baseline+4_000_000L,(long)(typical*1.8)));
        boolean spike=frameGap>threshold || renderTime>threshold;
        if(spike) {
            spikes++;pressure=Math.min(4,pressure+2);healthySince=now;
            long delay=Math.max(75_000_000L,Math.min(62_500_000L,Math.max(frameGap,renderTime))*4);
            blockedUntil=Math.max(blockedUntil,now+delay);
        } else {
            if(healthySince==0) healthySince=now;
            if(now-healthySince>=1_000_000_000L) {pressure=Math.max(0,pressure-1);healthySince=now;}
        }
        // Keep learning a sustained lower frame rate, but do not let one spike
        // immediately redefine the normal frame time and erase the slowdown.
        if(frameGap>0&&frameGap<2_000_000_000L) {
            timings.record("frameGap",frameGap);
            typical=typical*.95+Math.min(frameGap,typical*1.25)*.05;
        }
        timings.record("renderCpu",renderTime);
        // Allocate a decision snapshot only on the exceptional reporting path.
        if(spike) decision=new Decision(baseline,threshold,pressure);
        return spike;
    }
    private void resetControl(){last=blockedUntil=nextPreparation=healthySince=0;typical=16_000_000;pressure=0;}
    public synchronized void reset(){resetControl();}
    public synchronized Decision lastSpike(){return decision;}
    public synchronized int pressure(){return pressure;}
    public synchronized boolean mayPublish(){long now=clock.getAsLong();return last!=0&&now-last<2_000_000_000L&&now>=blockedUntil;}
    /** Phase 5.1 gate telemetry: "fresh" when frame observations are current, "stale"
     * after two seconds without frames, "silent" before the first observed frame. */
    public synchronized String heartbeatState(){long now=clock.getAsLong();if(last==0)return "silent";return now-last<2_000_000_000L?"fresh":"stale";}
    public synchronized boolean pressureBlocked(){return clock.getAsLong()<blockedUntil;}
    public synchronized boolean mayPrepare(){return mayPublish()&&typical<40_000_000;}
    public synchronized boolean claimPreparation(){long now=clock.getAsLong();if(!mayPrepare()||now<nextPreparation) return false;nextPreparation=now+250_000_000L*(1+pressure);return true;}
    public synchronized long transferInterval(){long normal=typical>30_000_000?50_000_000L:typical>18_000_000?16_000_000L:8_000_000L;
        return Math.max(normal,switch(pressure){case 0->8_000_000L;case 1->16_000_000L;case 2->32_000_000L;case 3->64_000_000L;default->100_000_000L;});}
    public java.util.Map<String,StageTimings.Snapshot> measurements(){return timings.snapshot();}
    public synchronized void resetMeasurements(){timings.clear();}
    public synchronized String statistics(){return String.format(java.util.Locale.ROOT,"baseline=%.2fms pressure=%d/4 transfer=%.0fms spikes=%d %s",
        typical/1e6,pressure,transferInterval()/1e6,spikes,timings);}
}

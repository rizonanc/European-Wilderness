package com.github.xandergos.terraindiffusionmc.worldgen.diagnostics;

import com.github.xandergos.terraindiffusionmc.worldgen.preparation.FrameBudget;
import java.lang.management.ManagementFactory;

/** Standalone CPU/allocation probe; no game, GPU or frame-time improvement claim. */
public final class PerformanceOverheadProbe {
    private static final com.sun.management.ThreadMXBean BEAN=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static long allocated(){return BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId());}
    private static void spans(WorkTimeline trace,int count){for(int i=0;i<count;i++){long token=trace.begin(WorkTimeline.Stage.LOD_INSERT);trace.end(token);}}
    public static void main(String[] args) {
        if(!BEAN.isThreadAllocatedMemorySupported())throw new IllegalStateException("Allocation accounting unavailable");
        BEAN.setThreadAllocatedMemoryEnabled(true);
        int count=200_000;var trace=new WorkTimeline(512,System::nanoTime);trace.enabled(true);
        spans(trace,100_000);
        long[] clock={1_000_000_000};var budget=new FrameBudget(()->clock[0]);
        for(int i=0;i<100_000;i++){clock[0]+=7_000_000;budget.observe(7_000_000,3_000_000);}
        StringBuilder result=new StringBuilder("{\"scope\":\"isolated CPU probe, not game FPS\",\"iterations_per_trial\":"+count+",\"trials\":[");
        for(int trial=0;trial<3;trial++) {
            long memory=allocated(),start=System.nanoTime();spans(trace,count);
            long spanTime=System.nanoTime()-start,spanBytes=allocated()-memory;
            memory=allocated();start=System.nanoTime();
            for(int i=0;i<count;i++){clock[0]+=7_000_000;budget.observe(7_000_000,3_000_000);}
            long frameTime=System.nanoTime()-start,frameBytes=allocated()-memory;
            if(trial>0)result.append(',');
            result.append("{\"span_pair_ns\":").append((double)spanTime/count).append(",\"span_allocated_bytes\":").append(spanBytes)
                .append(",\"frame_observe_ns\":").append((double)frameTime/count).append(",\"frame_allocated_bytes\":").append(frameBytes).append('}');
        }
        System.out.println(result.append("]}").toString());
    }
}

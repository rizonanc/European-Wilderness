package com.github.xandergos.terraindiffusionmc.infinitetensor;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoarseIncrementalTest {
    private InfiniteTensor stage(AtomicInteger calls) {
        return new MemoryTileStore().getOrCreate("coarse",new Integer[]{1,null,null},(wi,deps)->{
            calls.incrementAndGet();var result=new FloatTensor(new int[]{1,4,4});
            for(int z=0;z<4;z++)for(int x=0;x<4;x++)result.data[z*4+x]=wi[1]*123+wi[2]*10+z+x;
            return result;
        },new TensorWindow(new int[]{1,4,4},new int[]{1,3,3}),new InfiniteTensor[0],new TensorWindow[0],1024*1024);
    }
    @Test void oneWindowPerStepAndFinalBlendMatchesExactSliceAtNegativeCoordinates() {
        var incrementalCalls=new AtomicInteger();var incremental=stage(incrementalCalls);
        int[] start={0,-5,-3},end={1,6,9};int steps=0;
        while(true) {
            int before=incrementalCalls.get();boolean ready=incremental.prepareIndependentSliceStep(start,end);
            assertTrue(incrementalCalls.get()-before<=1);if(ready)break;assertTrue(++steps<100);
        }
        var expectedCalls=new AtomicInteger();var expected=stage(expectedCalls).getSlice(start,end);
        var actual=incremental.getSlice(start,end);
        assertArrayEquals(expected.data,actual.data);assertEquals(expectedCalls.get(),incrementalCalls.get());
        assertTrue(incremental.prepareIndependentSliceStep(start,end));assertEquals(steps,incrementalCalls.get());
    }
}

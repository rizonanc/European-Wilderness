package com.github.xandergos.terraindiffusionmc.pipeline;

import ai.onnxruntime.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Native CPU Identity probe, not a benchmark of the terrain models or DirectML. */
public final class OnnxBufferProbe {
    private static volatile long checksum;
    private static long run(OrtSession session,OnnxInputBuffers pool,float[] values,int count) throws Exception {
        long sum=0;var env=OrtEnvironment.getEnvironment();
        for(int i=0;i<count;i++) {
            values[0]=i;values[values.length-1]=-i;
            if(pool==null) {
                try(var input=OnnxTensor.createTensor(env,FloatBuffer.wrap(values),new long[]{values.length});var result=session.run(Map.of("x",input))) {
                    var buffer=((OnnxTensor)result.get(0)).getFloatBuffer();sum+=Float.floatToRawIntBits(buffer.get(0))+Float.floatToRawIntBits(buffer.get(values.length-1));
                }
            }else try(var lease=pool.prepare(env,new Object[][]{{"x",values,new long[]{values.length}}});var result=session.run(lease.feed())) {
                var buffer=((OnnxTensor)result.get(0)).getFloatBuffer();sum+=Float.floatToRawIntBits(buffer.get(0))+Float.floatToRawIntBits(buffer.get(values.length-1));
            }
        }
        checksum=sum;return sum;
    }
    public static void main(String[] args) throws Exception {
        float[] values=new float[327680];for(int i=0;i<values.length;i++)values[i]=(i%97)*.01f;
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        List<String> rounds=new ArrayList<>();
        try(var session=OnnxInputBuffersTest.session()) {
            run(session,null,values,16);try(var warm=new OnnxInputBuffers(8L*1024*1024)){run(session,warm,values,16);}
            for(int round=0;round<3;round++) {
                long[] ns=new long[2],bytes=new long[2],checks=new long[2];long created=0,reused=0;
                for(int step=0;step<2;step++) {
                    int mode=round%2==0?step:1-step;long id=Thread.currentThread().threadId(),before=bean.getThreadAllocatedBytes(id),start=System.nanoTime();
                    if(mode==0)checks[mode]=run(session,null,values,64);
                    else try(var pool=new OnnxInputBuffers(8L*1024*1024)){checks[mode]=run(session,pool,values,64);created=pool.created();reused=pool.reused();}
                    ns[mode]=System.nanoTime()-start;bytes[mode]=bean.getThreadAllocatedBytes(id)-before;
                }
                if(checks[0]!=checks[1])throw new AssertionError("Changed output");
                rounds.add("{\"nanoseconds\":"+Arrays.toString(ns)+",\"javaAllocatedBytes\":"+Arrays.toString(bytes)+",\"legacyInputTensors\":64,\"pooledCreated\":"+created+",\"pooledReused\":"+reused+",\"checksum\":"+checks[0]+"}");
            }
        }
        String result="{\"scope\":\"Native CPU Identity, 327680 floats, 64 calls per mode; alternating order; excludes terrain models/game/DirectML\",\"rounds\":["+String.join(",",rounds)+"]}\n";
        Files.writeString(Path.of(args[0]),result);System.out.print(result);
    }
}

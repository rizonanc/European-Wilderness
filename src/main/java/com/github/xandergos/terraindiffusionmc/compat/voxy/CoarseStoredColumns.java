package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.concurrent.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongPredicate;

/** Worker-built evidence of exact Voxy columns, distinct from Anvil write protection. */
final class CoarseStoredColumns {
    interface Reader {long[] read(int x,int y,int z);}
    private final ConcurrentHashMap<Long,long[]> ready=new ConcurrentHashMap<>();
    private final Map<Long,Long> pending=new HashMap<>();
    private final ArrayBlockingQueue<Long> requests=new ArrayBlockingQueue<>(4096);
    private static long key(int x,int z){return ((long)Math.floorDiv(x,512)<<32)|(Math.floorDiv(z,512)&0xffffffffL);}
    boolean ready(int x,int z){
        long key=key(x,z);if(ready.containsKey(key))return true;
        if(ready.size()>=4096)return true; // Unknown columns remain protected at the cap.
        synchronized(pending){
            if(ready.containsKey(key))return true;
            if(!pending.containsKey(key)&&requests.offer(key))pending.put(key,0L);
        }
        return false;
    }
    boolean contains(int x,int z){
        long[] bits=ready.get(key(x,z));if(bits==null)return true;
        int i=Math.floorMod(z>>4,32)*32+Math.floorMod(x>>4,32);
        return (bits[i>>>6]&(1L<<(i&63)))!=0;
    }
    boolean knownContains(int x,int z){return (ready.size()>=4096||ready.containsKey(key(x,z)))&&contains(x,z);}
    /** Phase 5.4.1: drop cached probes for a region so a freshly ingested real chunk
     * becomes protected on the next prepare; without this the probe stays stale and the
     * neural preview keeps drawing over stored columns. */
    void invalidate(int x,int z){
        long key=key(x,z);
        synchronized(pending){
            ready.remove(key);
            Long version=pending.get(key);
            if(version!=null)pending.put(key,version+1);
            else if(requests.offer(key))pending.put(key,0L);
        }
    }
    int prepare(int limit,int bottom,int top,Reader reader,LongPredicate air){
        int count=0;for(;count<limit;count++){
            Long key=requests.poll();if(key==null)break;
            Long version;
            synchronized(pending){version=pending.get(key);}
            if(version==null)continue;
            try{
                if(ready.size()>=4096){synchronized(pending){pending.remove(key);}continue;}
                int x=(int)(key>>32),z=(int)(long)key;
                long[] bits=new long[16];
                for(int y=Math.floorDiv(bottom,512);y<=Math.floorDiv(top,512);y++){
                    long[] raw=reader.read(x,y,z);if(raw==null)continue;
                    for(int p=0;p<1024;p++)if((bits[p>>>6]&(1L<<(p&63)))==0)
                        for(int dy=0;dy<32;dy++)if(!air.test(raw[(dy<<10)+p])){bits[p>>>6]|=1L<<(p&63);break;}
                }
                synchronized(pending){
                    if(Objects.equals(pending.get(key),version)){
                        ready.put(key,bits);pending.remove(key);
                    }else if(!requests.offer(key))pending.remove(key);
                }
            }catch(RuntimeException|Error failure){synchronized(pending){pending.remove(key);}throw failure;}
        }
        return count;
    }
    String statistics(){synchronized(pending){return "storedColumns={regions="+ready.size()+", pending="+pending.size()+"}";}}
}

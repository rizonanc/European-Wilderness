package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import it.unimi.dsi.fastutil.longs.*;

/** Exact-preview ownership by 16x16 column. Overflow fails closed. */
public final class CoarseCoverage {
    private volatile Long2ObjectMap<AtomicLongArray> regions=Long2ObjectMaps.emptyMap();
    private volatile boolean overflow;
    public synchronized boolean mark(int chunkX,int chunkZ) {
        long key=key(chunkX,chunkZ);
        var bits=regions.get(key);
        if(bits==null){if(regions.size()>=4096){overflow=true;return false;}bits=new AtomicLongArray(16);var copy=new Long2ObjectOpenHashMap<>(regions);copy.put(key,bits);regions=copy;}
        int index=Math.floorMod(chunkZ,32)*32+Math.floorMod(chunkX,32);long bit=1L<<(index&63);int word=index>>>6;
        long old;do{old=bits.get(word);if((old&bit)!=0)return false;}while(!bits.compareAndSet(word,old,old|bit));return true;
    }
    public boolean contains(int chunkX,int chunkZ) {
        if(overflow)return true;var bits=regions.get(key(chunkX,chunkZ));
        if(bits==null)return false;int index=Math.floorMod(chunkZ,32)*32+Math.floorMod(chunkX,32);
        return (bits.get(index>>>6)&(1L<<(index&63)))!=0;
    }
    private static long key(int chunkX,int chunkZ){return ((long)Math.floorDiv(chunkX,32)<<32)|(Math.floorDiv(chunkZ,32)&0xffffffffL);}
}

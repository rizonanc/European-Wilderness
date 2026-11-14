package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed-memory monotonic Bloom history. False positives only cause extra render-only
 * invalidations; evicting a real entry would leave old virtual sky on screen forever. */
public final class CoarseVirtualHistory {
    private final AtomicLongArray words;private final int mask;
    public CoarseVirtualHistory(){this(25);}
    CoarseVirtualHistory(int bits){if(bits<6||bits>26)throw new IllegalArgumentException();mask=(1<<bits)-1;words=new AtomicLongArray((1<<bits)>>>6);}
    private static long mix(long x){x=(x^(x>>>30))*0xbf58476d1ce4e5b9L;x=(x^(x>>>27))*0x94d049bb133111ebL;return x^(x>>>31);}
    public void mark(long position) {
        long a=mix(position),b=mix(position^0x9e3779b97f4a7c15L)|1;
        for(int i=0;i<3;i++){int bit=(int)(a+i*b)&mask,index=bit>>>6;long flag=1L<<(bit&63),old;
            do{old=words.get(index);if((old&flag)!=0)break;}while(!words.compareAndSet(index,old,old|flag));}
    }
    public boolean contains(long position) {
        long a=mix(position),b=mix(position^0x9e3779b97f4a7c15L)|1;
        for(int i=0;i<3;i++){int bit=(int)(a+i*b)&mask;if((words.get(bit>>>6)&(1L<<(bit&63)))==0)return false;}return true;
    }
    public int storageBytes(){return words.length()*Long.BYTES;}
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.*;

/** Read-only snapshots for render workers. No disk access, models or waiting there. */
public final class CoarseLodCache {
    private final int span,limit;
    private final LinkedHashMap<CoarseLodPatch.Key,CoarseLodPatch> patches=new LinkedHashMap<>(64,.75f,true);
    private volatile Map<CoarseLodPatch.Key,CoarseLodPatch> snapshot=Map.of();
    public CoarseLodCache(int span,int limit){if(span<1||limit<1)throw new IllegalArgumentException();this.span=span;this.limit=limit;}
    public CoarseLodPatch at(int x,int z){return snapshot.get(new CoarseLodPatch.Key(Math.floorDiv(x,span),Math.floorDiv(z,span)));}
    public CoarseLodPatch.Surface surface(int x,int z){var patch=at(x,z);return patch==null?null:patch.surface(x,z);}
    public boolean contains(CoarseLodPatch.Key key){return snapshot.containsKey(key);}
    public int size(){return snapshot.size();}
    public synchronized void put(CoarseLodPatch patch){
        if(patch.span!=span)throw new IllegalArgumentException("Mismatched patch span");
        patches.put(patch.key,patch);while(patches.size()>limit)patches.remove(patches.keySet().iterator().next());
        snapshot=Map.copyOf(patches);
    }
    /** At most a few dozen candidates even for a 2048-chunk radius. */
    public CoarseLodPatch.Key next(int x,int z,int radius) {
        CoarseLodPatch.Key best=null;long distance=Long.MAX_VALUE;
        for(int pz=Math.floorDiv(z-radius,span);pz<=Math.floorDiv(z+radius,span);pz++)
          for(int px=Math.floorDiv(x-radius,span);px<=Math.floorDiv(x+radius,span);px++) {
            var key=new CoarseLodPatch.Key(px,pz);if(contains(key))continue;
            long dx=Math.max(0,Math.max((long)px*span-x,x-((long)px+1)*span));
            long dz=Math.max(0,Math.max((long)pz*span-z,z-((long)pz+1)*span));
            long d=dx*dx+dz*dz;if(d>(long)radius*radius)continue;
            long cx=(long)px*span+span/2-x,cz=(long)pz*span+span/2-z;long rank=cx*cx+cz*cz;
            if(rank<distance){distance=rank;best=key;}
          }
        return best;
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.function.BiPredicate;

/** Worker-owned scan of water cores, independent of voxel export progress. */
public final class LodPreparationFrontier {
    public record Point(int x,int z){}
    private final LodScan scan=new LodScan();private final int core,scale;
    public LodPreparationFrontier(int core){if(core<32||core%32!=0)throw new IllegalArgumentException("Core must align to LOD columns");this.core=core;scale=core/32;}
    public Point next(int x,int z,int radius,BiPredicate<Integer,Integer> ready) {
        scan.move(Math.floorDiv(x,scale),Math.floorDiv(z,scale),Math.max(64,(radius+scale-1)/scale));
        for(int attempt=0;attempt<8;attempt++) {
            var cell=scan.next();if(cell==null)return null;
            int wx=cell.x()*core,wz=cell.z()*core;long dx=(long)wx+core/2-x,dz=(long)wz+core/2-z;
            if(Math.abs((long)wx)>=29_989_000||Math.abs((long)wz)>=29_989_000
                ||dx*dx+dz*dz>(long)(radius+core)*(radius+core)||ready.test(wx,wz)){scan.completed(cell);continue;}
            return new Point(wx,wz);
        }
        return null;
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;

/** A tiny dry cap surrounded by lower ground, rather than a continuous shoreline terrace. */
final class BankCaps {
    static boolean isolated(WorldSession.RiverOverlay overlay,int x,int z,int bed) {
        int[] xs=new int[4],zs=new int[4];xs[0]=x;zs[0]=z;
        int read=0,count=1;boolean touchesWater=false;
        int[] dx={-1,1,0,0},dz={0,0,-1,1};
        while(read<count) {
            int cx=xs[read],cz=zs[read++];
            for(int direction=0;direction<4;direction++) {
                int nx=cx+dx[direction],nz=cz+dz[direction];
                if(!overlay.isReady(nx,nz)||overlay.waterfallAt(nx,nz)!=null) return false;
                int water=overlay.waterY(nx,nz),other=overlay.bedY(nx,nz,water==PreparedWaterArea.NONE?0:water+1);
                if(other>bed) return false;
                if(water!=PreparedWaterArea.NONE) {touchesWater=true;if(other==bed)return false;continue;}
                if(other!=bed) continue;
                boolean found=false;for(int i=0;i<count;i++) if(xs[i]==nx&&zs[i]==nz) found=true;
                if(!found) {
                    if(count==4) return false;
                    xs[count]=nx;zs[count++]=nz;
                }
            }
        }
        return touchesWater;
    }
}

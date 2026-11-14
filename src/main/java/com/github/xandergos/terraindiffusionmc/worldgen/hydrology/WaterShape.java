package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

/** Shape cleanup without joining different water levels or cutting large islands. */
final class WaterShape {
    static final int MAX_SMALL_ISLAND_BLOCKS=128;
    static final int MAX_SMALL_ISLAND_RISE=3;
    private static final int[] DX={1,0,-1,0},DZ={0,1,0,-1};

    static int[][] smoothDrainageHeights(int[][] ground) {
        int h=ground.length,w=ground[0].length;
        int[][] result=new int[h][w];
        int[] weights={1,2,1};
        for(int z=0;z<h;z++) for(int x=0;x<w;x++) {
            // Preserve actual boundary elevations/outlets.
            if(x==0||z==0||x==w-1||z==h-1) {result[z][x]=ground[z][x];continue;}
            int sum=0;
            for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) sum+=ground[z+dz][x+dx]*weights[dz+1]*weights[dx+1];
            result[z][x]=Math.floorDiv(sum+8,16);
        }
        return result;
    }

    static void removeSmallLowIslands(int side,int[] rawFirstAir,int[] water) {
        boolean[] seen=new boolean[water.length];
        int[] queue=new int[water.length];
        for(int start=0;start<water.length;start++) {
            if(seen[start]||water[start]!=PreparedWaterArea.NONE) continue;
            int head=0,tail=0,highestGround=Integer.MIN_VALUE;
            int lowWater=Integer.MAX_VALUE,highWater=Integer.MIN_VALUE;
            boolean touchesEdge=false;
            queue[tail++]=start;seen[start]=true;
            while(head<tail) {
                int p=queue[head++],x=p%side,z=p/side;
                highestGround=Math.max(highestGround,rawFirstAir[p]-1);
                if(x<PreparedWaterArea.COLLAR||z<PreparedWaterArea.COLLAR
                    ||x>=side-PreparedWaterArea.COLLAR||z>=side-PreparedWaterArea.COLLAR) touchesEdge=true;
                for(int d=0;d<4;d++) {
                    int nx=x+DX[d],nz=z+DZ[d];
                    if(nx<0||nz<0||nx>=side||nz>=side) continue;
                    int n=nz*side+nx;
                    if(water[n]==PreparedWaterArea.NONE) {
                        if(!seen[n]) {seen[n]=true;queue[tail++]=n;}
                    } else {lowWater=Math.min(lowWater,water[n]);highWater=Math.max(highWater,water[n]);}
                }
            }
            if(!touchesEdge&&tail<=MAX_SMALL_ISLAND_BLOCKS&&highWater!=Integer.MIN_VALUE
                &&lowWater==highWater&&highestGround<=(long)lowWater+MAX_SMALL_ISLAND_RISE)
                for(int i=0;i<tail;i++) water[queue[i]]=lowWater;
        }
    }
}

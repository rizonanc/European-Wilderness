package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

/** D8 accumulation on a uniform slope creates many independent parallel drains.
 * Keep valley-supported starts and their entire downstream route to the outlet. */
final class NaturalChannels {
    private static final int[] SCALES={16,32};
    private NaturalChannels(){}
    static boolean[] select(DrainagePlanner.DrainagePlan plan,boolean[] selected,int[][] raw) {
        int n=plan.width();boolean[] keep=new boolean[selected.length];
        for(int c=0;c<selected.length;c++) {
            if(!selected[c]||keep[c])continue;
            int x=c%n,z=c/n,d=plan.direction(x,z);
            if(d<0)continue;
            if(!valley(raw,x*2,z*2,DrainagePlanner.DX[d],DrainagePlanner.DZ[d]))continue;
            int cursor=c;
            while(cursor>=0&&cursor<keep.length&&selected[cursor]&&!keep[cursor]) {
                keep[cursor]=true;int cx=cursor%n,cz=cursor/n,dir=plan.direction(cx,cz);
                if(dir<0)break;
                int nx=cx+DrainagePlanner.DX[dir],nz=cz+DrainagePlanner.DZ[dir];
                if(nx<0||nz<0||nx>=n||nz>=plan.height())break;
                cursor=nz*n+nx;
            }
        }
        return keep;
    }
    static boolean valley(int[][] raw,int x,int z,int dx,int dz) {
        double center=mean(raw,x,z);
        for(int distance:SCALES) {
            int ax=x-dz*distance,az=z+dx*distance,bx=x+dz*distance,bz=z-dx*distance;
            if(!inside(raw,ax,az)||!inside(raw,bx,bz))continue;
            double a=mean(raw,ax,az),b=mean(raw,bx,bz);
            if(Math.min(a,b)>=center-.5&&(a+b)*.5-center>=1.25)return true;
        }
        return false;
    }
    private static boolean inside(int[][] raw,int x,int z){return x>=0&&z>=0&&z+1<raw.length&&x+1<raw[0].length;}
    private static double mean(int[][] raw,int x,int z){return (raw[z][x]+raw[z][x+1]+raw[z+1][x]+raw[z+1][x+1])*.25;}
}

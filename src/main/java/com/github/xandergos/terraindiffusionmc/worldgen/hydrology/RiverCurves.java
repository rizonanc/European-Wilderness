package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.Arrays;

/** Geometry of an already selected drainage graph; never changes its outlets or branches. */
final class RiverCurves {
    static final double MAX_SHIFT_BLOCKS = 12;
    private static final int SMOOTHING_PASSES = 96;
    private static final int MAX_EXTRA_CUT = 10;

    @FunctionalInterface interface Sample {
        void accept(int cell, int x, int z, int waterY, double dx, double dz, double slope);
    }

    static void rasterize(DrainagePlanner.DrainagePlan plan, boolean[] channels,
                          int[][] groundFirstAir, Sample sample) {
        int width=plan.width(), count=channels.length;
        int[] downstream=new int[count], upstream=new int[count];
        Arrays.fill(downstream,-1); Arrays.fill(upstream,-1);
        boolean[] active=channels.clone();
        for(int c=0;c<count;c++) if(channels[c]) {
            int d=plan.direction(c%width,c/width);
            if(d<0) continue;
            int target=(c/width+DrainagePlanner.DZ[d])*width+c%width+DrainagePlanner.DX[d];
            downstream[c]=target; active[target]=true;
            int previous=upstream[target];
            if(previous<0 || plan.accumulation(c%width,c/width)>
                    plan.accumulation(previous%width,previous/width)) upstream[target]=c;
        }
        double[][] positions=smooth(width,active,downstream,upstream,groundFirstAir,plan);
        double[] px=positions[0], pz=positions[1];
        for(int c=0;c<count;c++) if(channels[c] && downstream[c]>=0) {
            int target=downstream[c];
            double length=Math.hypot(px[target]-px[c],pz[target]-pz[c]);
            double[] a=tangent(c,downstream,upstream,px,pz);
            double[] b=tangent(target,downstream,upstream,px,pz);
            // Shared endpoint and tangent at a confluence also guide its side tributaries.
            double ax=px[c]+a[0]*length/3, az=pz[c]+a[1]*length/3;
            double bx=px[target]-b[0]*length/3, bz=pz[target]-b[1]*length/3;
            int level=Math.max(63,plan.filledHeight(c%width,c/width));
            int nextLevel=Math.max(63,plan.filledHeight(target%width,target/width));
            double slope=(level-nextLevel)/Math.max(1,length);
            int steps=Math.max(1,(int)Math.ceil(length*4));
            boolean clear=true;
            for(int step=0;step<=steps;step++) {
                double t=step/(double)steps, s=1-t;
                double x=s*s*s*px[c]+3*s*s*t*ax+3*s*t*t*bx+t*t*t*px[target];
                double z=s*s*s*pz[c]+3*s*s*t*az+3*s*t*t*bz+t*t*t*pz[target];
                double y=level+(nextLevel-(double)level)*t;
                int gx=(int)Math.round(x),gz=(int)Math.round(z);
                if(gz<0 || gz>=groundFirstAir.length || gx<0 || gx>=groundFirstAir[0].length
                        || groundFirstAir[gz][gx]-1-y>MAX_EXTRA_CUT) {
                    clear=false; break;
                }
            }
            if(!clear) {
                // A curve must not cut a new notch through a high ridge. Rejoin
                // the original valley via the checked node offsets instead.
                line(sample,c,px[c],pz[c],level,c%width*2,c/width*2,level);
                line(sample,c,c%width*2,c/width*2,level,target%width*2,target/width*2,nextLevel);
                line(sample,c,target%width*2,target/width*2,nextLevel,px[target],pz[target],nextLevel);
                continue;
            }
            for(int step=0;step<=steps;step++) {
                double t=step/(double)steps, s=1-t;
                double x=s*s*s*px[c]+3*s*s*t*ax+3*s*t*t*bx+t*t*t*px[target];
                double z=s*s*s*pz[c]+3*s*s*t*az+3*s*t*t*bz+t*t*t*pz[target];
                double dx=s*s*(ax-px[c])+2*s*t*(bx-ax)+t*t*(px[target]-bx);
                double dz=s*s*(az-pz[c])+2*s*t*(bz-az)+t*t*(pz[target]-bz);
                sample.accept(c,(int)Math.round(x),(int)Math.round(z),
                    (int)Math.round(level+(nextLevel-(double)level)*t),dx,dz,slope);
            }
        }
    }

    private static double[][] smooth(int width,boolean[] active,int[] down,int[] up,
                                     int[][] ground,DrainagePlanner.DrainagePlan plan) {
        int count=active.length;
        double[] x=new double[count],z=new double[count];
        for(int c=0;c<count;c++) {x[c]=c%width*2;z[c]=c/width*2;}
        double[] nextX=x.clone(),nextZ=z.clone();
        for(int pass=0;pass<SMOOTHING_PASSES;pass++) {
            for(int c=0;c<count;c++) {
                // Pin headwaters and outlets, including the original point of entry to a lake.
                if(!active[c] || up[c]<0 || down[c]<0) continue;
                double nx=x[c]*.5+(x[up[c]]+x[down[c]])*.25;
                double nz=z[c]*.5+(z[up[c]]+z[down[c]])*.25;
                double ox=c%width*2,oz=c/width*2;
                double distance=Math.hypot(nx-ox,nz-oz);
                if(distance>MAX_SHIFT_BLOCKS) {
                    nx=ox+(nx-ox)*MAX_SHIFT_BLOCKS/distance;
                    nz=oz+(nz-oz)*MAX_SHIFT_BLOCKS/distance;
                }
                int level=Math.max(63,plan.filledHeight(c%width,c/width));
                int originalCut=ground[(int)oz][(int)ox]-1-level;
                boolean clear=true;
                int steps=Math.max(1,(int)Math.ceil(Math.hypot(nx-ox,nz-oz)*4));
                for(int step=0;step<=steps;step++) {
                    double t=step/(double)steps;
                    int gx=(int)Math.round(ox+(nx-ox)*t),gz=(int)Math.round(oz+(nz-oz)*t);
                    if(ground[gz][gx]-1-level>Math.max(MAX_EXTRA_CUT,originalCut)) {clear=false;break;}
                }
                if(clear) {
                    nextX[c]=nx; nextZ[c]=nz;
                } else {nextX[c]=x[c]; nextZ[c]=z[c];}
            }
            double[] swap=x; x=nextX; nextX=swap;
            swap=z; z=nextZ; nextZ=swap;
        }
        return new double[][]{x,z};
    }

    private static void line(Sample sample,int cell,double x,double z,int y,
                             double targetX,double targetZ,int targetY) {
        double dx=targetX-x,dz=targetZ-z,length=Math.hypot(dx,dz);
        int steps=Math.max(1,(int)Math.ceil(length*4));
        for(int step=0;step<=steps;step++) {
            double t=step/(double)steps;
            sample.accept(cell,(int)Math.round(x+(targetX-x)*t),(int)Math.round(z+(targetZ-z)*t),
                (int)Math.round(y+(targetY-(double)y)*t),dx,dz,(y-targetY)/Math.max(1,length));
        }
    }

    private static double[] tangent(int c,int[] down,int[] up,double[] x,double[] z) {
        int a=up[c]<0?c:up[c],b=down[c]<0?c:down[c];
        double dx=x[b]-x[a],dz=z[b]-z[a],length=Math.hypot(dx,dz);
        return length<1e-9?new double[]{0,0}:new double[]{dx/length,dz/length};
    }
}

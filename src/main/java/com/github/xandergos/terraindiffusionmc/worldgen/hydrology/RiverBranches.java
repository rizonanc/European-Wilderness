package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;

/** Keep a dominant stem and a small hierarchy of meaningful tributaries. */
final class RiverBranches {
    static final double MIN_TRIBUTARY_LENGTH = 16;
    static final double MIN_CONFLUENCE_SPACING = 24;
    private record Tributary(int root,double alongStem,double score) {}
    static boolean[] select(DrainagePlanner.DrainagePlan plan,boolean[] eligible,long seed,int originX,int originZ) {
        int width=plan.width(),count=eligible.length;
        int[] head=new int[count],next=new int[count];Arrays.fill(head,-1);Arrays.fill(next,-1);
        int[] downstream=new int[count],incomingCount=new int[count];Arrays.fill(downstream,-1);
        ArrayDeque<Integer> work=new ArrayDeque<>();
        for(int c=0;c<count;c++) if(eligible[c]) {
            int x=c%width,z=c/width,d=plan.direction(x,z);
            int target=d<0?-1:(z+DrainagePlanner.DZ[d])*width+x+DrainagePlanner.DX[d];
            if(target>=0&&target<count&&eligible[target]) {
                next[c]=head[target];head[target]=c;downstream[c]=target;incomingCount[target]++;
            }
            else work.add(c);
        }
        // Length of a tributary's own channel, before its junction with this stem.
        double[] length=new double[count];
        ArrayDeque<Integer> sources=new ArrayDeque<>();
        for(int c=0;c<count;c++) if(eligible[c]&&incomingCount[c]==0) sources.add(c);
        while(!sources.isEmpty()) {
            int c=sources.removeFirst(),target=downstream[c];
            if(target<0) continue;
            length[target]=Math.max(length[target],length[c]+distance(c,target,width));
            if(--incomingCount[target]==0) sources.addLast(target);
        }
        boolean[] keep=new boolean[count];
        while(!work.isEmpty()) {
            int root=work.removeFirst();
            List<Tributary> tributaries=new ArrayList<>();
            double alongStem=0;
            for(int c=root;c>=0;) {
                keep[c]=true;
                int strongest=-1;
                for(int incoming=head[c];incoming>=0;incoming=next[incoming])
                    if(strongest<0||rank(plan,incoming,seed,originX,originZ)>rank(plan,strongest,seed,originX,originZ)) strongest=incoming;
                for(int incoming=head[c];incoming>=0;incoming=next[incoming]) if(incoming!=strongest
                        &&length[incoming]>=MIN_TRIBUTARY_LENGTH
                        &&plan.accumulation(incoming%width,incoming/width)>=
                            plan.accumulation(strongest%width,strongest/width)*.04) {
                    double score=rank(plan,incoming,seed,originX,originZ)*Math.sqrt(length[incoming]);
                    tributaries.add(new Tributary(incoming,alongStem,score));
                }
                if(strongest>=0) alongStem+=distance(c,strongest,width);
                c=strongest;
            }
            // Budget and spacing apply to the WHOLE stem, rather than separately
            // at each adjacent grid cell (which produced the artificial comb).
            tributaries.sort(Comparator.comparingDouble(Tributary::score).reversed());
            List<Tributary> selected=new ArrayList<>();
            int budget=2+(int)(alongStem/96);
            for(Tributary tributary:tributaries) {
                if(selected.size()>=budget) break;
                if(selected.stream().anyMatch(other->Math.abs(other.alongStem-tributary.alongStem)<MIN_CONFLUENCE_SPACING)) continue;
                selected.add(tributary);work.addLast(tributary.root);
            }
        }
        return keep;
    }
    private static double distance(int a,int b,int width) {
        return 2*Math.hypot(a%width-b%width,a/width-b/width);
    }
    private static double rank(DrainagePlanner.DrainagePlan p,int c,long seed,int ox,int oz) {
        long coordinate=((long)(ox+c%p.width()*2)<<32)^((oz+c/p.width()*2)&0xffffffffL);
        double jitter=(mix(seed^coordinate)>>>11)*0x1.0p-53;
        return p.accumulation(c%p.width(),c/p.width())*(0.9+0.2*jitter);
    }
    private static long mix(long n) {n=(n^(n>>>30))*0xbf58476d1ce4e5b9L;n=(n^(n>>>27))*0x94d049bb133111ebL;return n^(n>>>31);}
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedDrainageTest {
    @Test void flatRoutesToANearestExitInsteadOfFollowingTheRowScan() {
        int side=25;int[][] heights=new int[side][side];for(int[] row:heights) Arrays.fill(row,100);
        var p=DrainagePlanner.planPrepared(31,0,0,heights);
        for(int z=0;z<side;z++) for(int x=0;x<side;x++) {
            int px=x,pz=z,steps=0;
            while(p.direction(px,pz)>=0) {
                assertTrue(steps++<side*side,"cyclic route on a flat");
                int d=p.direction(px,pz);px+=DrainagePlanner.DX[d];pz+=DrainagePlanner.DZ[d];
            }
            assertEquals(Math.min(Math.min(x,z),Math.min(side-1-x,side-1-z)),steps,
                "flat must drain by distance from real exits, not by scan position");
        }
    }
    @Test void downhillDirectionUsesSlopeAndAccountsForDiagonalDistance() {
        int[][] heights={{110,110,110},{110,100,97},{110,110,96}};
        var p=DrainagePlanner.planPrepared(1,0,0,heights);
        assertEquals(0,p.direction(1,1),"east has steeper slope than the lower but more distant diagonal");
    }
    @Test void routingPreservesLakesAndConservesCatchmentsWithoutCyclesOrUphillEdges() {
        int width=48,height=32;Random random=new Random(49371);int[][] heights=new int[height][width];
        for(int[] row:heights) for(int x=0;x<width;x++) row[x]=90+random.nextInt(40);
        int[][] original=Arrays.stream(heights).map(int[]::clone).toArray(int[][]::new);
        var baseline=DrainagePlanner.planCoarse(7,-2048,512,heights);
        var p=DrainagePlanner.planPrepared(7,-2048,512,heights);
        var repeat=DrainagePlanner.planPrepared(7,-2048,512,heights);
        long outletArea=0;
        for(int z=0;z<height;z++) for(int x=0;x<width;x++) {
            assertEquals(baseline.kind(x,z),p.kind(x,z));
            assertEquals(baseline.filledHeight(x,z),p.filledHeight(x,z));
            assertEquals(repeat.direction(x,z),p.direction(x,z));
            int px=x,pz=z,steps=0;
            if(p.direction(x,z)<0) outletArea+=p.accumulation(x,z);
            while(p.direction(px,pz)>=0) {
                assertTrue(steps++<width*height,"cyclic prepared-water route");
                int d=p.direction(px,pz),nx=px+DrainagePlanner.DX[d],nz=pz+DrainagePlanner.DZ[d];
                assertTrue(p.filledHeight(nx,nz)<=p.filledHeight(px,pz));
                px=nx;pz=nz;
            }
        }
        assertEquals(width*height,outletArea);
        assertTrue(Arrays.deepEquals(original,heights));
    }
}

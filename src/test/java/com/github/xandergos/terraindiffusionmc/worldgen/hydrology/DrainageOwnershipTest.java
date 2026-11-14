package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DrainageOwnershipTest {
 @Test void planOwnsFreshArraysAndPublicCopiesCannotMutateIt(){
  int[][] a={{80,80,80},{80,10,80},{80,80,80}};
  var p=DrainagePlanner.planPrepared(7,-4,-4,a);int h=p.filledHeight(1,1),d=p.direction(1,1);long count=p.accumulation(1,1);
  a[1][1]=Integer.MIN_VALUE;p.filledSurface()[1][1]=0;p.directions()[1][1]=-123;p.accumulation()[1][1]=-1;
  assertEquals(h,p.filledHeight(1,1));assertEquals(d,p.direction(1,1));assertEquals(count,p.accumulation(1,1));
 }
 @Test void everyCellReachesAnOutletAndContributesExactlyOnceOnFlatAndExtremeTerrain(){
  for(int style=0;style<3;style++){
   int[][] a=new int[53][41];var random=new Random(42);
   for(var row:a)for(int x=0;x<row.length;x++)row[x]=style==0?64:style==1?random.nextInt():random.nextBoolean()?Integer.MIN_VALUE:Integer.MAX_VALUE;
   for(boolean prepared:new boolean[]{false,true}){
    var p=prepared?DrainagePlanner.planPrepared(17,-500,700,a):DrainagePlanner.planCoarse(17,-500,700,a);
    long outlets=0;long[][] counted=new long[53][41];
    for(int z=0;z<53;z++)for(int x=0;x<41;x++){
     int xx=x,zz=z,steps=0;
     while(true){counted[zz][xx]++;int dir=p.direction(xx,zz);if(dir<0)break;assertTrue(++steps<=53*41);xx+=DrainagePlanner.DX[dir];zz+=DrainagePlanner.DZ[dir];}
    }
    for(int z=0;z<53;z++)for(int x=0;x<41;x++){assertEquals(counted[z][x],p.accumulation(x,z));if(p.direction(x,z)<0)outlets+=p.accumulation(x,z);}
    assertEquals(53*41,outlets);
   }
  }
 }
}

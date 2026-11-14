package com.github.xandergos.terraindiffusionmc.compat.voxy;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Offline geometry replay of read-only saved sections, not a visual success claim. */
public final class GrassSavedReplayProbe {
 public static void main(String[] args)throws Exception{
  var f=new WaterMipPolicyTest.Fixture();int changedSections=0,changedCells=0,total;
  try(var in=new DataInputStream(Files.newInputStream(Path.of(args[0])))){
   total=in.readInt();for(int section=0;section<total;section++){
    int sx=in.readInt(),sy=in.readInt(),sz=in.readInt();long[] d=new long[4681];
    for(int i=0;i<4096;i++){int id=in.readInt();d[i]=id==0?0:((long)id<<27)|(15L<<56)|(17L<<47);}
    WaterMipRepairData.stock(d,(a,b,c,e,g,h,i,j)->f.reduce(new long[]{a,b,c,e,g,h,i,j},false));
    long[] before=d.clone();int changed=GrassSurfaceMip.correct(d,sx,sz,WaterMipPolicyTest::grassKind);
    if(changed>0)changedSections++;changedCells+=changed;
    if(!Arrays.equals(Arrays.copyOf(before,4096),Arrays.copyOf(d,4096)))throw new AssertionError("Fine data changed");
    if(!Arrays.equals(Arrays.copyOfRange(before,4672,4681),Arrays.copyOfRange(d,4672,4681)))throw new AssertionError("Far data changed");
    for(int i=4096;i<4672;i++)if(d[i]!=before[i]){
     int a=WaterMipPolicyTest.grassKind(WaterMipPolicy.blockId(before[i])),b=WaterMipPolicyTest.grassKind(WaterMipPolicy.blockId(d[i]));
     if((a!=0&&a<3)||(b!=0&&b<3))throw new AssertionError("Terrain/water/structure replaced");
    }
   }
  }
  if(changedSections==0)throw new AssertionError("Saved scene did not exercise correction");
  String result="PASS: saved scene sections="+total+", sections with plant-only corrections="+changedSections+", changed L1/L2 cells="+changedCells+"; L0, terrain/water/foreign cells and far mips unchanged. Semantic block classes remapped for offline fixture; no live writes.\n";
  Files.writeString(Path.of(args[1]),result);System.out.print(result);
 }
}

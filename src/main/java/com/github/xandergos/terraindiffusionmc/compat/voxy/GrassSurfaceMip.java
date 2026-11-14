package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Keep separate vegetation caps on L1/L2 without replacing terrain or changing L0. */
public final class GrassSurfaceMip {
    private static final long BLOCK_MASK=0xfffffL<<27;
    private static final class Scan {final int[] root=new int[256];final long[] grass=new long[256];}
    private static final ThreadLocal<Scan> SCAN=ThreadLocal.withInitial(Scan::new);
    private GrassSurfaceMip(){}
    public static int correct(long[] data,int sectionX,int sectionZ,GrassMipMapping mapping){
        if(data.length<4681)throw new IllegalArgumentException("Incomplete mip array");
        Scan scan=SCAN.get();int plants=0;
        for(int col=0;col<256;col++){
            scan.root[col]=-1;scan.grass[col]=0;
            for(int y=15;y>=0;y--){
                long v=data[y*256+col];int kind=mapping.ewh$grassKind(WaterMipPolicy.blockId(v));
                if(kind==GrassMipMapping.AIR)continue;
                if(kind==GrassMipMapping.GROUND)scan.root[col]=y;
                else if(kind>=GrassMipMapping.SHORT){
                    int root=y-1;
                    if(kind==GrassMipMapping.TALL&&root>=0&&mapping.ewh$grassKind(WaterMipPolicy.blockId(data[root*256+col]))==GrassMipMapping.TALL)root--;
                    if(root>=0&&mapping.ewh$grassKind(WaterMipPolicy.blockId(data[root*256+col]))==GrassMipMapping.GROUND){
                        scan.root[col]=root;scan.grass[col]=v;plants++;
                    }
                }
                break;
            }
        }
        if(plants==0)return 0;
        int changes=0;
        for(int level=1;level<=2;level++){
            int size=1<<level,width=16>>level,plane=width*width,offset=WaterMipRepairData.offset(level);
            for(int z=0;z<16;z+=size)for(int x=0;x<16;x+=size){
                int max=-1,min=16,count=0;long representative=0;boolean open=true;
                for(int dz=0;dz<size&&open;dz++)for(int dx=0;dx<size;dx++){
                    int col=(z+dz)*16+x+dx,root=scan.root[col];
                    if(root<0){open=false;break;}
                    max=Math.max(max,root);min=Math.min(min,root);
                    if(scan.grass[col]!=0){count++;representative=scan.grass[col];}
                }
                if(!open||count==0||max-min>size)continue;
                int ground=max>>level,cap=ground+1;if(cap>=width)continue;
                int base=offset+(z>>level)*width+(x>>level);
                if(mapping.ewh$grassKind(WaterMipPolicy.blockId(data[base+ground*plane]))!=GrassMipMapping.GROUND)continue;
                int index=base+cap*plane,kind=mapping.ewh$grassKind(WaterMipPolicy.blockId(data[index]));
                if(kind!=GrassMipMapping.AIR&&kind<GrassMipMapping.SHORT)continue;
                boolean keep=sample(Math.floorDiv(sectionX*16+x,size),Math.floorDiv(sectionZ*16+z,size),level,size*size)<count;
                long value=keep?representative:data[index]&~BLOCK_MASK;
                if(data[index]!=value){data[index]=value;changes++;}
                // A tall plant may leave a second coarse plant above the cap.
                // Clear only that plant, never another material or source voxel.
                if(cap+1<width){int extra=index+plane;
                    if(mapping.ewh$isGrass(WaterMipPolicy.blockId(data[extra]))){data[extra]&=~BLOCK_MASK;changes++;}
                }
            }
        }
        return changes;
    }
    private static int sample(int x,int z,int level,int area){
        long h=x*0x9e3779b97f4a7c15L+z*0xc2b2ae3d27d4eb4fL+level*0x165667b19e3779f9L;
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;h=(h^(h>>>27))*0x94d049bb133111ebL;
        return (int)((h^(h>>>31))&(area-1));
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import java.util.*;
import me.cortex.voxy.common.world.other.Mapper;

/** Bounded, read-only engine census. Resident sections are not proof of disk contents;
 * a concurrent ingest or a virtual render overlay can differ from this snapshot. */
public final class VoxyCensus {
    private static final int SAMPLED_SECTIONS=64;

    /** Mutable totals fed by the scan; pure arithmetic so it is unit-testable. */
    public static final class Aggregator {
        public final long[] sections=new long[5];
        public long zeroMaskWithVoxels;
        public long parentBitsWithoutChild;
        public long sectionsWithMissingNeighbor;
        public long realChunksAvailable,staleChecked,staleMismatched;
        public int worstPersistedAboveReal,worstRealAbovePersisted;
        private String firstMismatch;

        public void section(int level){if(level>=0&&level<sections.length)sections[level]++;}
        public void zeroMaskWithVoxels(int level,int x,int y,int z){
            zeroMaskWithVoxels++;
            if(firstMismatch==null)firstMismatch="voxelsWithoutMask@"+level+" ("+x+","+y+","+z+")";
        }
        public void parentBitWithoutChild(int level,int x,int y,int z){
            parentBitsWithoutChild++;
            if(firstMismatch==null)firstMismatch="parent@"+level+" ("+x+","+y+","+z+")";
        }
        public void missingNeighbor(int level,int x,int y,int z){
            sectionsWithMissingNeighbor++;
            if(firstMismatch==null)firstMismatch="neighborless@"+level+" ("+x+","+y+","+z+")";
        }
        public void staleSample(boolean mismatch,int persistedTop,int realTop,int x,int z){
            staleChecked++;
            if(mismatch){
                staleMismatched++;
                if(firstMismatch==null)firstMismatch="stale ("+x+","+z+") persisted="+persistedTop+" real="+realTop;
            }
            worstPersistedAboveReal=Math.max(worstPersistedAboveReal,persistedTop-realTop);
            worstRealAbovePersisted=Math.max(worstRealAbovePersisted,realTop-persistedTop);
        }
        public String firstMismatch(){return firstMismatch;}
    }

    /** Topmost non-air voxel (block Y) of one column inside a 32³ level-0 raw tile, or
     * Integer.MIN_VALUE when the column is empty. Layout is Voxy's (y*32+z)*32+x. */
    static int persistedTopOf(long[] raw,int localX,int localZ) {
        for(int y=31;y>=0;y--)if(!Mapper.isAir(raw[y*1024+localZ*32+localX]))return y;
        return Integer.MIN_VALUE;
    }

    record Sample(int x,int z,int top){}
    interface Reader {
        int mask(int level,int x,int y,int z) throws Exception;
        long[] raw(int level,int x,int y,int z) throws Exception;
    }
    static String inspect(Reader reader,int level,int x,int y,int z) throws Exception {
        int side=32<<level,mask=reader.mask(level,x,y,z),nonAir=0;var ids=new TreeSet<Integer>();
        long[] raw=reader.raw(level,x,y,z);
        if(raw!=null)for(long value:raw)if(!Mapper.isAir(value)){nonAir++;if(ids.size()<16)ids.add(WaterMipPolicy.blockId(value));}
        var neighbors=new ArrayList<Integer>(6);
        for(var d:new int[][]{{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}})
            neighbors.add(reader.mask(level,x+d[0],y+d[1],z+d[2]));
        int parent=level==4?-1:reader.mask(level+1,x>>1,y>>1,z>>1),bit=(x&1)|((z&1)<<1)|((y&1)<<2);
        return "inspect L"+level+" ("+x+","+y+","+z+") span="+side+" blocks; Y="+(y*side)+".."+((y+1)*side-1)
            +"; engineMask="+mask+" nonAir="+nonAir+" blockIds(sample<16)="+ids+"; parentMask="+parent+" childBit="+bit
            +"; neighborMasks[-X,+X,-Y,+Y,-Z,+Z]="+neighbors+" (-1 absent); engine data is resident OR persisted, not virtual overlay";
    }
    private record Key(int level,int x,int y,int z){}
    private static final class Limit extends RuntimeException {}
    private static final class BoundedReader implements Reader {
        final Reader source;final Map<Key,Integer> masks=new HashMap<>();final long deadline;
        int reads,copies;
        BoundedReader(Reader source){this.source=source;deadline=System.nanoTime()+1_500_000_000L;}
        private void check(){if(Thread.currentThread().isInterrupted()||++reads>20000||System.nanoTime()>deadline)throw new Limit();}
        public int mask(int l,int x,int y,int z) throws Exception {
            var key=new Key(l,x,y,z);var value=masks.get(key);if(value!=null)return value;
            check();int result=source.mask(l,x,y,z);masks.put(key,result);return result;
        }
        public long[] raw(int l,int x,int y,int z) throws Exception {check();if(++copies>128)throw new Limit();return source.raw(l,x,y,z);}
    }
    /** Capture only loaded heightmaps on their owner thread; never retain chunks on a worker. */
    static List<Sample> capture(ClientWorld world,int centerX,int centerZ) {
        var result=new ArrayList<Sample>(64);
        for(int ring=0;ring<=4&&result.size()<64;ring++)for(int dz=-ring;dz<=ring&&result.size()<64;dz++)
          for(int dx=-ring;dx<=ring&&result.size()<64;dx++){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=ring)continue;
            WorldChunk chunk=world.getChunkManager().getChunk(centerX+dx,centerZ+dz,ChunkStatus.FULL,false);
            if(chunk==null)continue;
            var heights=chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
            for(int z:new int[]{2,10})for(int x:new int[]{2,10})
                result.add(new Sample((centerX+dx)*16+x,(centerZ+dz)*16+z,heights.get(x,z)-1));
        }
        return List.copyOf(result);
    }
    static String scan(Reader source,List<Sample> samples,int radius,int bottom,int top,int centerChunkX,int centerChunkZ) throws Exception {
        var reader=new BoundedReader(source);var agg=new Aggregator();boolean partial=false;
        try {
            compare(reader,samples,agg,bottom,top);
            scanSections(reader,agg,radius,bottom,top,centerChunkX,centerChunkZ);
        }catch(Limit exhausted){partial=true;}
        return report(agg,radius)+"; reads="+reader.reads+", rawCopies="+reader.copies
            +", partial="+partial+"; zero-mask raw sampling <=64; unmatched real samples="+(samples.size()-agg.staleChecked)
            +"; engine snapshot, NOT proof of persistent corruption; virtual overlay excluded";
    }
    private static void scanSections(Reader reader,Aggregator agg,int radius,int bottom,int top,int centerChunkX,int centerChunkZ) throws Exception {
        int centerBlockX=centerChunkX*16,centerBlockZ=centerChunkZ*16,radiusBlocks=radius*16;
        int zeroChecked=0;
        for(int level=4;level>=0;level--) {
            int spanBlocks=32<<level;
            int bottomSection=Math.floorDiv(bottom,spanBlocks),topSection=Math.floorDiv(top,spanBlocks);
            int half=radiusBlocks/spanBlocks+1;
            int cx=Math.floorDiv(centerBlockX,spanBlocks),cz=Math.floorDiv(centerBlockZ,spanBlocks);
            for(int sx=cx-half;sx<=cx+half;sx++)
             for(int sz=cz-half;sz<=cz+half;sz++)
              for(int sy=bottomSection;sy<=topSection;sy++){
                int mask=reader.mask(level,sx,sy,sz);if(mask<0)continue;
                agg.section(level);
                boolean hasData=mask!=0;
                if(level>0&&hasData) {
                    for(int bit=0;bit<8;bit++) {
                        if((mask&1<<bit)==0)continue;
                        int cx2=sx*2+(bit&1),cy2=sy*2+(bit>>2&1),cz2=sz*2+(bit>>1&1);
                        if(reader.mask(level-1,cx2,cy2,cz2)<0)agg.parentBitWithoutChild(level,sx,sy,sz);
                    }
                    // Voxy sections carry their own voxels above children; a zero mask with a
                    // non-empty raw tile would be the empty-child bookkeeping defect.
                }
                if(level>0&&mask==0&&zeroChecked++<SAMPLED_SECTIONS){
                    long[] raw=reader.raw(level,sx,sy,sz);
                    if(raw!=null)for(long voxel:raw)if(!Mapper.isAir(voxel)){agg.zeroMaskWithVoxels(level,sx,sy,sz);break;}
                }
                if(hasData)missingNeighborCheck(reader,agg,level,sx,sy,sz,bottomSection,topSection);
              }
        }
    }

    private static void missingNeighborCheck(Reader reader,Aggregator agg,int level,int sx,int sy,int sz,int bottomSection,int topSection) throws Exception {
        int[][] sides={{1,0},{-1,0},{0,1},{0,-1}};
        for(var side:sides) {
            boolean found=false;
            for(int y=bottomSection;y<=topSection&&!found;y++)found=reader.mask(level,sx+side[0],y,sz+side[1])>0;
            if(!found){agg.missingNeighbor(level,sx+side[0],sy,sz+side[1]);return;}
        }
    }

    private static void compare(Reader reader,List<Sample> samples,Aggregator agg,int bottom,int top) throws Exception {
        var columns=new LinkedHashMap<Key,List<Sample>>();
        for(var p:samples)columns.computeIfAbsent(new Key(0,Math.floorDiv(p.x,32),0,Math.floorDiv(p.z,32)),k->new ArrayList<>()).add(p);
        agg.realChunksAvailable=samples.stream().map(p->new Key(0,Math.floorDiv(p.x,16),0,Math.floorDiv(p.z,16))).distinct().count();
        for(var entry:columns.entrySet()){
            var column=entry.getKey();var pending=new ArrayList<>(entry.getValue());
            for(int sy=Math.floorDiv(top,32);sy>=Math.floorDiv(bottom,32)&&!pending.isEmpty();sy--){
                if(reader.mask(0,column.x,sy,column.z)<0)continue;
                long[] raw=reader.raw(0,column.x,sy,column.z);if(raw==null)continue;
                for(var it=pending.iterator();it.hasNext();){
                    var p=it.next();int local=persistedTopOf(raw,Math.floorMod(p.x,32),Math.floorMod(p.z,32));
                    if(local==Integer.MIN_VALUE)continue;
                    int observed=sy*32+local;agg.staleSample(Math.abs(observed-p.top)>4,observed,p.top,p.x,p.z);it.remove();
                }
            }
        }
    }

    private static String report(Aggregator agg,int radius) {
        return String.format(Locale.ROOT,
            "census radius=%d ч: секций L0..L4=%d/%d/%d/%d/%d; parent-бит без ребёнка=%d; воксели-без-маски=%d;"
            +" секций без соседнего столбца=%d; сравнение L0 с загруженными чанками: доступно=%d, проверено=%d, расхождений=%d"
            +" (engine выше реала максимум на %d, реал выше engine максимум на %d)%s",
            radius,agg.sections[0],agg.sections[1],agg.sections[2],agg.sections[3],agg.sections[4],
            agg.parentBitsWithoutChild,agg.zeroMaskWithVoxels,
            agg.sectionsWithMissingNeighbor,
            agg.realChunksAvailable,agg.staleChecked,agg.staleMismatched,
            agg.worstPersistedAboveReal,agg.worstRealAbovePersisted,
            agg.firstMismatch()==null?"":("; первый: "+agg.firstMismatch()));
    }
    private VoxyCensus() {}
}

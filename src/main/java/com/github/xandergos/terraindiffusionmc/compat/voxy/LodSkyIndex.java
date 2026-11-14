package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Locate existing geometry through the public hierarchy, instead of probing every empty sky cube. */
public final class LodSkyIndex {
    public interface Reader {
        int mask(int level,int x,int y,int z) throws Exception;
        boolean childPresent(int mask,int childX,int childY,int childZ) throws Exception;
    }
    private LodSkyIndex(){}
    public static int highest(LodColumn column,Reader reader) throws Exception {
        return highest(column.x,column.z,column.bottom,column.top,reader);
    }
    public static int highest(int worldX,int worldZ,int bottom,int top,Reader reader) throws Exception {
        int x=Math.floorDiv(worldX,32),z=Math.floorDiv(worldZ,32);
        for(int y=Math.floorDiv(top,512);y>=Math.floorDiv(bottom,512);y--) {
            int result=find(reader,4,x,y,z);if(result!=Integer.MIN_VALUE)return Math.min(result,Math.floorDiv(top,32));
        }
        return Math.floorDiv(bottom,32)-1;
    }
    private static int find(Reader reader,int level,int x,int y,int z) throws Exception {
        int mask=reader.mask(level,x>>level,y,z>>level);if(mask<=0)return Integer.MIN_VALUE;
        if(level==0)return y;
        for(int dy=1;dy>=0;dy--) {
            int cy=y*2+dy;
            if(reader.childPresent(mask,x>>(level-1),cy,z>>(level-1))) {
                int result=find(reader,level-1,x,cy,z);if(result!=Integer.MIN_VALUE)return result;
            }
        }
        return Integer.MIN_VALUE;
    }
}

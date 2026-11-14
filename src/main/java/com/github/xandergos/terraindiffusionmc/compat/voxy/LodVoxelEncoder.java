package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Reuses palette references and the caller's 16-cube buffer. Does not encode Voxy IDs itself. */
public final class LodVoxelEncoder {
    private static final LodColumn.Kind[] KINDS=LodColumn.Kind.values();
    @FunctionalInterface public interface Palette {long[][] resolve(short biome) throws Exception;}
    private final LodColumn column;private final long[][][] values=new long[1024][][];
    public LodVoxelEncoder(LodColumn column,Palette palette) throws Exception {
        this.column=column;for(int z=0;z<32;z++) for(int x=0;x<32;x++) values[z*32+x]=palette.resolve(column.biome(x,z));
    }
    public int fill(int bx,int sy,int bz,long[] target) {
        if(bx<0||bx>1||bz<0||bz>1||target.length<4096) throw new IllegalArgumentException("Invalid section buffer");
        int count=0;
        byte[] forest=column.forestSection(bx,sy,bz);
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            int lx=bx*16+x,lz=bz*16+z;long[][] palette=values[lz*32+lx];int surface=column.firstAir(lx,lz)-1;
            for(int y=0;y<16;y++) {int wy=sy*16+y,index=y*256+z*16+x;int plant=forest==null?0:Byte.toUnsignedInt(forest[index]);
                var kind=plant==0?column.terrainKind(lx,wy,lz):KINDS[plant-1];if(kind!=LodColumn.Kind.AIR) count++;
                target[index]=palette[kind.ordinal()][wy>=surface?1:0];}
        }
        return count;
    }
}

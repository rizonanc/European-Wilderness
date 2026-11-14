package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.function.Consumer;

/** World-coordinate-only decisions: no chunk-local random or feature-order dependency. */
public final class RiverDetailPlanning {
    enum Kind {
        SAND_SLAB, TURF_SLAB, DIRT_SLAB, STONE_SLAB, SEAGRASS, TALL_SEAGRASS;
        boolean slab(){return this!=SEAGRASS&&this!=TALL_SEAGRASS;}
    }
    record Detail(int x,int y,int z,Kind kind,boolean waterlogged) {}
    private static final int NONE=PreparedWaterArea.NONE;
    private static final int[][] NEIGHBOURS={{-1,0},{1,0},{0,-1},{0,1}};
    static void forChunk(WorldSession.RiverOverlay overlay,long seed,int baseX,int baseZ,int bottom,int top,
                         Consumer<Detail> out) {
        for(int dz=0;dz<16;dz++) for(int dx=0;dx<16;dx++) {
            int x=baseX+dx,z=baseZ+dz;
            Detail detail=column(overlay,seed,x,z,bottom,top);
            if(detail!=null) out.accept(detail);
        }
    }
    /** Conservative integer clearance above an actually planned dry half block. */
    public static int clearanceFirstAir(WorldSession.RiverOverlay overlay,long seed,int x,int z,int firstAir,int bottom,int top) {
        if(overlay==null||overlay.waterY(x,z)!=NONE) return firstAir;
        Detail detail=column(overlay,seed,x,z,bottom,top);
        return detail!=null&&detail.kind().slab()&&!detail.waterlogged()?Math.max(firstAir,detail.y()+1):firstAir;
    }
    private static Detail column(WorldSession.RiverOverlay overlay,long seed,int x,int z,int bottom,int top) {
            if(!overlay.isReady(x,z)) return null;
            if(overlay.waterfallAt(x,z)!=null) return null;
            int level=overlay.waterY(x,z),bed=overlay.bedY(x,z,level==NONE?0:level+1),y=bed+1;
            if(y<=bottom||y>top) return null;
            boolean wet=level!=NONE;
            int shoreLevel=level,shoreDistance=0;
            if(!wet) {
                var shore=RiverShorePalette.nearby(overlay,x,z);
                shoreLevel=shore.level();shoreDistance=shore.distance();
                if(shoreLevel==NONE||bed<shoreLevel||bed>shoreLevel+3) return null;
                // Lower the cap itself, rather than adding another half block on
                // top. A cap at the waterline must retain water inside the slab.
                if(bed<=shoreLevel+1&&BankCaps.isolated(overlay,x,z,bed)) {
                    Kind cap=bed<=shoreLevel?Kind.SAND_SLAB:switch(RiverShorePalette.material(seed,x,z,shoreDistance)) {
                        case SAND->Kind.SAND_SLAB;
                        case GRASS->Kind.TURF_SLAB;
                        default->Kind.DIRT_SLAB;
                    };
                    return new Detail(x,bed,z,cap,bed<=shoreLevel);
                }
            }
            boolean halfStep=false,stillWater=wet;
            for(int[] offset:NEIGHBOURS) {
                int nx=x+offset[0],nz=z+offset[1];
                if(!overlay.isReady(nx,nz)) {stillWater=false;continue;}
                int neighbourWater=overlay.waterY(nx,nz);
                int neighbourBed=overlay.bedY(nx,nz,neighbourWater==NONE?0:neighbourWater+1);
                // Replace the upper solid step with a half block, on both banks
                // and bed. Never add a raised row of soil above the lower shelf.
                if(neighbourBed==bed-1 && (neighbourWater==NONE||neighbourWater==level)) halfStep=true;
                if(neighbourWater!=level) stillWater=false;
            }
            if(halfStep && (!wet||level-bed<=5) && random(seed^0xa3f1197L,x,z)<.85) {
                Kind kind=Kind.SAND_SLAB;
                if(!wet) {
                    var material=RiverShorePalette.material(seed,x,z,shoreDistance);
                    kind=switch(material) {
                        case SAND->Kind.SAND_SLAB;
                        case GRASS->Kind.TURF_SLAB;
                        default->Kind.DIRT_SLAB;
                    };
                    if(random(seed^0x431b8fL,x,z)<.035) kind=Kind.STONE_SLAB;
                }
                return new Detail(x,bed,z,kind,wet);
            }
            if(!wet&&random(seed^0x52acbbL,x,z)<.012) {
                return new Detail(x,y,z,Kind.STONE_SLAB,false);
            }
            int depth=wet?level-bed:0;
            if(stillWater&&depth>=2&&depth<=8) {
                double density=.12+Math.max(0,RiverBanks.relief(seed^0x41be32L,x,z))*.2;
                if(random(seed^0x917ad3L,x,z)<density) {
                    Kind kind=depth>=3&&y+1<=top&&random(seed^0xf5a96bL,x,z)<.18?Kind.TALL_SEAGRASS:Kind.SEAGRASS;
                    return new Detail(x,y,z,kind,true);
                }
            }
        return null;
    }
    private static double random(long seed,int x,int z) {
        long v=seed^((long)x<<32)^(z&0xffffffffL);
        v=(v^(v>>>30))*0xbf58476d1ce4e5b9L;v=(v^(v>>>27))*0x94d049bb133111ebL;v^=v>>>31;
        return (v>>>11)*0x1.0p-53;
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.function.Consumer;

/** Sources only at the lip; the vertical curtain is flowing, falling water. */
final class WaterfallPlacement {
    enum Kind { SOURCE, FALLING }
    record Placement(int x,int y,int z,Kind kind) {}
    static void forChunk(WorldSession.RiverOverlay overlay,int baseX,int baseZ,int bottom,int top,
                         Consumer<Placement> writer) {
        for(var fall:overlay.waterfallsForChunk(Math.floorDiv(baseX,16),Math.floorDiv(baseZ,16))) {
            if(fall.upperY()>top||fall.lowerY()-6<bottom) continue;
            for(int t=-fall.radius();t<=fall.radius();t++) {
                emit(fall.worldX(0,t),fall.upperY(),fall.worldZ(0,t),Kind.SOURCE,baseX,baseZ,writer);
                int x=fall.worldX(1,t),z=fall.worldZ(1,t);
                for(int y=fall.lowerY()+1;y<=fall.upperY();y++) emit(x,y,z,Kind.FALLING,baseX,baseZ,writer);
            }
        }
    }
    private static void emit(int x,int y,int z,Kind kind,int bx,int bz,Consumer<Placement> writer) {
        if((long)x-bx>=0&&(long)x-bx<16&&(long)z-bz>=0&&(long)z-bz<16) writer.accept(new Placement(x,y,z,kind));
    }
}

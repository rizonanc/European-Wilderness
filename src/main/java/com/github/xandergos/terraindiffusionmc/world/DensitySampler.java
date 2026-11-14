package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.RiverRasterizer;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;

/** Density sampling without Minecraft registry bootstrap. */
final class DensitySampler {
    @FunctionalInterface interface Lookup {HeightmapData fetch(int startZ,int startX,int endZ,int endX);}
    private static final class Worker {
        final DensityWindowCache windows=new DensityWindowCache();
        final DensitySurfaceCache surfaces=new DensitySurfaceCache();
    }
    private final ThreadLocal<Worker> workers=ThreadLocal.withInitial(Worker::new);
    HeightmapData window(WorldSession session,Lookup lookup,int x,int z) {
        return window(session,lookup,x,z,workers.get().windows);
    }
    private HeightmapData window(WorldSession session,Lookup lookup,int x,int z,DensityWindowCache cache) {
        WorldSession.requireActive(session);var data=cache.get(session.id(),lookup,x,z);
        if(data==null){data=lookup.fetch(z,x,z+16,x+16);WorldSession.requireActive(session);cache.put(session.id(),lookup,x,z,data);}
        return data;
    }
    double sample(WorldSession session,Lookup lookup,int x,int y,int z) {
        WorldSession.requireActive(session);
        var worker=workers.get();var water=session.automaticWater();
        // Only automatic plans are immutable. Manual/prepared overlays may change
        // in place, and incomplete raw windows must never enter this cache.
        boolean reusable=water!=null&&session.riverOverlay()==water;
        if(reusable) {
            long surface=worker.surfaces.get(session.id(),water.densityCacheId(),lookup,x,z);
            if(surface!=DensitySurfaceCache.MISSING) {
                WorldSession.requireActive(session);
                return (double)surface-y;
            }
        }
        if(water!=null)water.ensureColumn(x,z);
        int sx=Math.floorDiv(x,16)*16,sz=Math.floorDiv(z,16)*16;
        var data=window(session,lookup,sx,sz,worker.windows);
        int surface=surface(session,data,sx,sz,x,z);
        WorldSession.requireActive(session);
        if(reusable&&data!=null&&data.complete()&&data.heightmap!=null&&data.width==16&&data.height==16
            &&session.automaticWater()==water&&session.riverOverlay()==water)
            worker.surfaces.put(session.id(),water.densityCacheId(),lookup,x,z,surface);
        return (double)surface-y;
    }
    double at(WorldSession session,HeightmapData data,int sx,int sz,int x,int y,int z) {
        WorldSession.requireActive(session);
        return (double)surface(session,data,sx,sz,x,z)-y;
    }
    private int surface(WorldSession session,HeightmapData data,int sx,int sz,int x,int z) {
        if(data==null||data.heightmap==null)return 0;
        int lx=Math.max(0,Math.min(data.width-1,x-sx)),lz=Math.max(0,Math.min(data.height-1,z-sz));
        int surface=data.surfaceYAt(lx,lz,session.profile().worldScale());
        var overlay=session.riverOverlay();
        if(overlay!=null&&RiverRasterizer.shouldRasterize(!data.complete())) {overlay.waterY(x,z);surface=overlay.carvedHeight(x,z,surface);}
        return surface;
    }
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.lang.reflect.*;

/** Optional public Chunky API v0. Never creates a scheduler, edits selection or cancels another task. */
public final class ChunkyApiAdapter {
    private final Object api;private final Method running,start,pause;
    public ChunkyApiAdapter(Object api) throws ReflectiveOperationException {
        this.api=api;Class<?> type=api.getClass();
        if((int)type.getMethod("version").invoke(api)!=0)throw new IllegalStateException("Unsupported Chunky API version");
        running=type.getMethod("isRunning",String.class);pause=type.getMethod("pauseTask",String.class);
        start=type.getMethod("startTask",String.class,String.class,double.class,double.class,double.class,double.class,String.class);
    }
    public boolean running(String world) throws ReflectiveOperationException{return (boolean)running.invoke(api,world);}
    public boolean startChunks(String world,int x,int z,int radiusChunks) throws ReflectiveOperationException {
        if(radiusChunks<1||radiusChunks>2048)throw new IllegalArgumentException("Radius must be 1..2048 chunks");
        int radius=Math.multiplyExact(radiusChunks,16);
        if(running(world))return false;
        return (boolean)start.invoke(api,world,"circle",(double)x,(double)z,(double)radius,(double)radius,"region");
    }
    public boolean pause(String world) throws ReflectiveOperationException{return (boolean)pause.invoke(api,world);}
    public static ChunkyApiAdapter installed() throws ReflectiveOperationException {
        Object chunky=Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
        return new ChunkyApiAdapter(chunky.getClass().getMethod("getApi").invoke(chunky));
    }
}

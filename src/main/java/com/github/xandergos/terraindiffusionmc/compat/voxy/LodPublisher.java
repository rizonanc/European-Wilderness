package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.util.function.BooleanSupplier;

/** Publishes complete 32-cubes only, yielding between them and preserving existing data. */
public final class LodPublisher {
    public interface Sink {
        boolean active() throws Exception;
        boolean occupied(int x,int y,int z) throws Exception;
        default boolean existing(int x,int y,int z) throws Exception{return occupied(x,y,z);}
        void write(LodColumn column,int y) throws Exception;
    }
    public record Result(boolean complete,int written,int skipped){}
    public record Progress(int y,int clearY,int skyCeiling,boolean forestPending){}
    public static final class Cursor {
        private final LodColumn column;private int y,clearY,skyCeiling;private final boolean clearAbove;private int minimum;
        public Cursor(LodColumn column){this(column,false);}
        public Cursor(LodColumn column,boolean clearAbove){this.column=column;this.clearAbove=clearAbove;y=Math.floorDiv(column.maxY,32);clearY=y+1;skyCeiling=Math.floorDiv(column.top,32);minimum=Math.floorDiv(column.bottom,32);}
        public static Cursor visible(LodColumn column,int highestExisting){var cursor=new Cursor(column,true);cursor.minimum=column.visibleBottomCube();cursor.skyCeiling=highestExisting;return cursor;}
        public static Cursor resume(LodColumn column,Progress progress){var cursor=new Cursor(column,true);cursor.y=progress.y();cursor.clearY=progress.clearY();cursor.skyCeiling=progress.skyCeiling();return cursor;}
        public Progress progress(){return new Progress(y,clearY,skyCeiling,column.forestPending());}
        private boolean complete(){return y<minimum&&(!clearAbove||clearY>skyCeiling);}
        public Result step(Sink sink,BooleanSupplier current) throws Exception {
            if(complete()) return new Result(true,0,0);
            if(!current.getAsBoolean()||!sink.active()) return new Result(false,0,0);
            if(y<minimum) {
                boolean exists=sink.existing(Math.floorDiv(column.x,32),clearY,Math.floorDiv(column.z,32));
                if(exists)sink.write(column,clearY);clearY++;return new Result(complete(),exists?1:0,exists?0:1);
            }
            boolean occupied=sink.occupied(Math.floorDiv(column.x,32),y,Math.floorDiv(column.z,32));
            if(!occupied) sink.write(column,y);y--;
            return new Result(complete(),occupied?0:1,occupied?1:0);
        }
    }
    private LodPublisher(){}
    public static Result publish(LodColumn column,Sink sink,BooleanSupplier current) throws Exception {
        int written=0,skipped=0;
        for(int y=Math.floorDiv(column.bottom,32);y<=Math.floorDiv(column.maxY,32);y++) {
            if(!current.getAsBoolean()||!sink.active()) return new Result(false,written,skipped);
            if(sink.occupied(Math.floorDiv(column.x,32),y,Math.floorDiv(column.z,32))) skipped++;
            else {sink.write(column,y);written++;}
        }
        return new Result(true,written,skipped);
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.util.*;

/** A frozen output core. Wet columns may freely cross its edges. */
final class WorldWaterTile {
    final WorldWaterGrid.Cell cell;
    final int side,x,z;
    final int[] air,water;
    final short[] currents;
    final List<Waterfall> falls;
    final int revision;
    private PreparedWaterView exported;
    synchronized PreparedWaterView export(){if(exported==null) exported=PreparedWaterView.fromFrozenTile(this);return exported;}
    WorldWaterTile(WorldWaterGrid grid,WorldWaterGrid.Cell cell,int[] air,int[] water,List<Waterfall> falls) {
        this(grid,cell,air,water,falls,1);
    }
    WorldWaterTile(WorldWaterGrid grid,WorldWaterGrid.Cell cell,int[] air,int[] water,List<Waterfall> falls,int revision) {
        this(grid,cell,air,water,falls,revision,null);
    }
    WorldWaterTile(WorldWaterGrid grid,WorldWaterGrid.Cell cell,int[] air,int[] water,List<Waterfall> falls,int revision,short[] currents) {
        if(revision<1||revision>6) throw new IllegalArgumentException("Unsupported water algorithm");this.revision=revision;
        this.cell=cell;side=grid.core();x=grid.x(cell);z=grid.z(cell);this.air=air.clone();this.water=water.clone();this.falls=List.copyOf(falls);
        if(air.length!=side*side||water.length!=air.length||falls.size()>1) throw new IllegalArgumentException("Invalid world water tile");
        this.currents=RiverCurrent.freeze(currents,water);
        for(int i=0;i<air.length;i++) {
            if(air[i]<-1024||air[i]>4096||water[i]!=PreparedWaterArea.NONE
                &&(water[i]<(revision<2?63:62)||water[i]>=4096||air[i]>water[i]||(long)water[i]-air[i]+1>PreparedWaterArea.MAX_DEPTH))
                throw new IllegalArgumentException("Invalid world water column");
        }
        for(var f:falls) {
            if(!f.inside(x,z,side)) throw new IllegalArgumentException("Foreign waterfall");
            for(int t=-f.radius();t<=f.radius();t++)
                if(water[index(f.worldX(0,t),f.worldZ(0,t))]!=f.upperY()
                    ||water[index(f.worldX(1,t),f.worldZ(1,t))]!=f.lowerY()) throw new IllegalArgumentException("Inconsistent waterfall");
        }
    }
    int index(int wx,int wz){return (wz-z)*side+wx-x;}
    short currentAt(int wx,int wy,int wz) {
        if(currents==null) return 0;
        int p=index(wx,wz);
        return wy>=air[p]&&wy<=water[p]?currents[p]:0;
    }
    boolean contains(int wx,int wz){long dx=(long)wx-x,dz=(long)wz-z;return dx>=0&&dz>=0&&dx<side&&dz<side;}
    static WorldWaterTile render(long seed,WorldWaterGrid grid,WorldWaterGrid.Cell cell,int[][] raw,List<WorldWaterUnit> units) {
        int side=grid.renderSide(),originX=grid.x(cell)-grid.renderHalo(),originZ=grid.z(cell)-grid.renderHalo();
        if(raw.length!=side) throw new IllegalArgumentException("Wrong render grid");
        int[] air=new int[side*side],water=new int[side*side];boolean[] lakeFine=new boolean[side*side];Arrays.fill(water,PreparedWaterArea.NONE);
        for(int z=0;z<side;z++) {if(raw[z].length!=side) throw new IllegalArgumentException("Wrong render row");System.arraycopy(raw[z],0,air,z*side,side);}
        short[] flow=units.stream().anyMatch(unit->unit.currents!=null)?new short[side*side]:null;
        byte[] depth=units.stream().anyMatch(unit->unit.depthVariation!=100)?new byte[side*side]:null;
        int revision=1;for(var unit:units) {unit.merge(grid,originX,originZ,side,water,lakeFine,flow,depth);revision=Math.max(revision,unit.revision);}
        // A disposable border seeds the distance transform. The output is 128
        // blocks away, beyond the 64-block maximum depth and 6-block bank band.
        for(int i=0;i<side;i++) {water[i]=PreparedWaterArea.NONE;water[(side-1)*side+i]=PreparedWaterArea.NONE;water[i*side]=PreparedWaterArea.NONE;water[i*side+side-1]=PreparedWaterArea.NONE;}
        boolean[] lake=new boolean[side/2*(side/2)];
        for(int p=0;p<lakeFine.length;p++) if(lakeFine[p]) lake[(p/side/2)*(side/2)+p%side/2]=true;
        RiverBanks.shape(seed,originX,originZ,side,air,water,lake,flow,p->depth==null?100:Byte.toUnsignedInt(depth[p]));
        if(revision>=3) BankPeaks.lower(side,air,water);
        if(revision>=4) BankNeedles.lower(side,air,water);
        List<Waterfall> falls=List.of();
        for(var unit:units) if(unit.cell.equals(cell)) {
            falls=unit.falls;for(var fall:falls) WaterfallPlanner.apply(fall,originX,originZ,side,air,water,flow);
        }
        int core=grid.core(),h=grid.renderHalo();int[] croppedAir=new int[core*core],croppedWater=new int[core*core];
        short[] croppedFlow=flow==null?null:new short[core*core];
        for(int z=0;z<core;z++) {
            int start=(z+h)*side+h;
            System.arraycopy(air,start,croppedAir,z*core,core);
            System.arraycopy(water,start,croppedWater,z*core,core);
            if(flow!=null) System.arraycopy(flow,start,croppedFlow,z*core,core);
        }
        return new WorldWaterTile(grid,cell,croppedAir,croppedWater,falls,revision,croppedFlow);
    }
}

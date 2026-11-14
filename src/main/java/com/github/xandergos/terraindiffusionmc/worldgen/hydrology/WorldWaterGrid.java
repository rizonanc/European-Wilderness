package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

/** Global coordinates, including negative positions, define immutable plan identities. */
record WorldWaterGrid(int core,int unitHalo,int renderHalo) {
    static final WorldWaterGrid DEFAULT=new WorldWaterGrid(512,256,128);
    WorldWaterGrid {
        if(core<32||core%32!=0||unitHalo<16||unitHalo%2!=0||core+2*unitHalo>PreparedWaterArea.MAX_SIDE
            ||renderHalo<128||renderHalo%2!=0||core+2*renderHalo>PreparedWaterArea.MAX_SIDE)
            throw new IllegalArgumentException("Invalid world-water layout");
    }
    record Cell(int x,int z) {}
    Cell cell(int x,int z){return new Cell(Math.floorDiv(x,core),Math.floorDiv(z,core));}
    int x(Cell c){return Math.multiplyExact(c.x(),core);}
    int z(Cell c){return Math.multiplyExact(c.z(),core);}
    int unitSide(){return core+2*unitHalo;}
    int renderSide(){return core+2*renderHalo;}
}

package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.BiomeBalance;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.*;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.*;
import com.github.xandergos.terraindiffusionmc.worldgen.tree.*;
import java.util.*;

/** Reads frozen water and existing raw tiles only. Never invokes inference or loads game chunks. */
public final class LodTerrainSource {
    private final WorldSession ticket;
    private final boolean forests;private int missingX,missingZ;
    private final LinkedHashMap<Long,PreparedWaterView> views=new LinkedHashMap<>(8,.75F,true);
    public LodTerrainSource(WorldSession ticket){this(ticket,true);}
    public LodTerrainSource(WorldSession ticket,boolean forests){this.ticket=Objects.requireNonNull(ticket);this.forests=forests;}
    public int missingX(){return missingX;}public int missingZ(){return missingZ;}
    private PreparedWaterView readyView(int x,int z) throws Exception {
        int core=ticket.automaticWater().coreSize();long id=((long)Math.floorDiv(x,core)<<32)^(Math.floorDiv(z,core)&0xffffffffL);
        var view=views.get(id);
        if(view==null){view=ticket.automaticWater().frozenCoreIfReady(x,z);if(view==null)return null;views.put(id,view);
            while(views.size()>4)views.remove(views.keySet().iterator().next());}
        return view;
    }
    public LodColumn sample(int cellX,int cellZ,int bottom,int top) throws Exception {
        return sample(cellX,cellZ,bottom,top,true);
    }
    /** Buried continuation cubes and sky cleanup cannot contain this column's forest. */
    public LodColumn sample(int cellX,int cellZ,int bottom,int top,boolean includeForest) throws Exception {
        WorldSession.requireActive(ticket);var water=ticket.automaticWater();if(water==null) return null;
        int x=Math.multiplyExact(cellX,32),z=Math.multiplyExact(cellZ,32);missingX=x;missingZ=z;
        var view=readyView(x,z);if(view==null)return null;
        int[] air=new int[1024],levels=new int[1024];short[] biomes=new short[1024];
        var repository=ticket.repository();int ts=repository.tileSize();boolean climateAvailable=true,forestBiome=false;
        for(int tz=Math.floorDiv(z,ts);tz<=Math.floorDiv(z+31,ts);tz++) for(int tx=Math.floorDiv(x,ts);tx<=Math.floorDiv(x+31,ts);tx++) {
          var key=TerrainTileKey.ofTile(ticket.worldSeed(),tx,tz,ts,ticket.fingerprint());
          var tile=repository.peek(key).orElse(null);if(tile==null) return null;
          climateAvailable&=tile.hasClimate();
          for(int wz=Math.max(z,tz*ts);wz<Math.min(z+32,(tz+1)*ts);wz++) for(int wx=Math.max(x,tx*ts);wx<Math.min(x+32,(tx+1)*ts);wx++) {
            int p=(wz-z)*32+wx-x;
            if(!view.isReady(wx,wz)) return null;
            air[p]=view.carvedHeight(wx,wz,0);levels[p]=view.waterY(wx,wz);
            // Vanilla sea level 63 means water occupies y <= 62.
            if(air[p]<=62) levels[p]=Math.max(levels[p],62);
            var fall=view.waterfallAt(wx,wz);if(fall!=null&&fall.curtain(wx,wz)) levels[p]=fall.upperY();
            biomes[p]=tile.biomeAt(key.localX(wx),key.localZ(wz));
            forestBiome|=VegetationDistribution.acceptsBiome(biomes[p]);
          }
        }
        var column=LodColumn.owned(x,z,bottom,top,air,levels,biomes);
        if(includeForest&&forests&&climateAvailable&&forestBiome) {
            var builder=new LodColumn.ForestBuilder(column);
            var field=new ForestField(column);
            try{
                // Fail before planning roots if their read-only halo is not frozen yet.
                field.view(x-32,z-32);field.view(x-32,z+63);field.view(x+63,z-32);field.view(x+63,z+63);
                TreeLodPreview.paint(ticket.worldSeed(),x,z,32,bottom,top,field,builder::set);
            }
            catch(NotReady missing){missingX=missing.x;missingZ=missing.z;return column.awaitingForest();}
            column=builder.build();
        }
        WorldSession.requireActive(ticket);return column;
    }
    private static final class NotReady extends Exception {final int x,z;NotReady(int x,int z){this.x=x;this.z=z;}}
    private final class ForestField implements TreeLodPreview.Field {
        private final LodColumn column;private PreparedWaterView last;
        private final Map<Long,TerrainTile> raw=new HashMap<>();
        ForestField(LodColumn column){this.column=column;}
        private boolean inside(int x,int z){return x>=column.x&&z>=column.z&&x<column.x+32&&z<column.z+32;}
        private PreparedWaterView view(int x,int z) throws Exception {
            if(last!=null&&last.isReady(x,z))return last;
            last=readyView(x,z);if(last==null)throw new NotReady(x,z);return last;
        }
        public int firstAir(int x,int z) throws Exception {return inside(x,z)?column.firstAir(x-column.x,z-column.z):view(x,z).carvedHeight(x,z,0);}
        public int waterY(int x,int z) throws Exception {
            var v=view(x,z);int water=v.waterY(x,z);if(firstAir(x,z)<=62)water=Math.max(water,62);
            var fall=v.waterfallAt(x,z);return fall!=null&&fall.curtain(x,z)?Math.max(water,fall.upperY()):water;
        }
        @Override public BiomeBalance biomeBalance() { return ticket.profile().biomeBalance(); }
        @Override public boolean largerLandscapes() { return ticket.profile().largerLandscapes(); }
        public TreeLodPreview.Climate climate(int x,int z) throws Exception {
            var repository=ticket.repository();int ts=repository.tileSize(),tx=Math.floorDiv(x,ts),tz=Math.floorDiv(z,ts);long id=((long)tx<<32)^(tz&0xffffffffL);
            var tile=raw.get(id);
            if(tile==null){var key=TerrainTileKey.ofTile(ticket.worldSeed(),tx,tz,ts,ticket.fingerprint());tile=repository.peek(key).orElse(null);
                if(tile==null)throw new NotReady(x,z);raw.put(id,tile);}
            if(!tile.hasClimate())return null;int lx=tile.key().localX(x),lz=tile.key().localZ(z);
            return new TreeLodPreview.Climate(tile.biomeAt(lx,lz),tile.heightAt(lx,lz),tile.climateAt(0,lx,lz),tile.climateAt(2,lx,lz));
        }
    }
}

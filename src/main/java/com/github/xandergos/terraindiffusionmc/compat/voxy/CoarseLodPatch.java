package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanEcology;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.TropicalLandforms;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.CoastalTerrain;
import com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanBiomeClassifier;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.BiomeBalance;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;

/** Immutable neural conditioning map. Never enters the exact terrain/water stores. */
public final class CoarseLodPatch {
    public static final int SIDE=64,HALO=2,GRID=SIDE+HALO*2;
    private static final double TREE_DENSITY=TerrainDiffusionConfig.percent("nature.tree_density")/100.0;
    public record Key(int x,int z){}
    public record Surface(int firstAir,short biome,int canopy){}
    public final Key key;public final int spacing,span;
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.List<TropicalLandforms.Island>> islandGroups = new java.util.concurrent.ConcurrentHashMap<>();
    private final float[][] channels;private final long seed;private final int scale,cap;
    public CoarseLodPatch(Key key,int scale,int latentCompression,long seed,int cap,FloatTensor weighted) {
        if(scale<1||latentCompression<1||weighted.shape.length!=3||weighted.shape[0]!=7
                ||weighted.shape[1]!=GRID||weighted.shape[2]!=GRID)throw new IllegalArgumentException("Coarse patch shape/scale");
        this.key=key;this.scale=scale;this.seed=seed;this.cap=cap;
        spacing=Math.multiplyExact(32*latentCompression,scale);span=Math.multiplyExact(SIDE,spacing);
        int cells=GRID*GRID;channels=new float[6][cells];
        for(int p=0;p<cells;p++) {
            float w=weighted.data[6*cells+p];if(!Float.isFinite(w)||w<=1e-6f)throw new IllegalArgumentException("Missing coarse weight");
            for(int c=0;c<6;c++) {
                float value=weighted.data[c*cells+p]/w;if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite coarse sample");
                channels[c][p]=value;
            }
        }
    }
    float channel(int channel,int x,int z) {
        // Same half-pixel convention as WorldPipeline.computeClimate; halo makes
        // adjacent patches use identical source corners at their shared boundary.
        double gx=x/(double)spacing-key.x*SIDE+HALO+.5,gz=z/(double)spacing-key.z*SIDE+HALO+.5;
        if(gx<0||gz<0||gx>=GRID-1||gz>=GRID-1)throw new IllegalArgumentException("Outside coarse halo");
        int ix=(int)Math.floor(gx),iz=(int)Math.floor(gz);double tx=gx-ix,tz=gz-iz;
        float[] values=channels[channel];int p=iz*GRID+ix;
        return (float)((1-tz)*((1-tx)*values[p]+tx*values[p+1])+tz*((1-tx)*values[p+GRID]+tx*values[p+GRID+1]));
    }
    public Surface surface(int x,int z) {
        var session=WorldSession.current();
        boolean active=session!=null&&session.isActive()&&session.worldSeed()==seed&&session.profile().worldScale()==scale;
        boolean largerLandscapes=active&&session.profile().largerLandscapes();
        var balance=active?session.profile().biomeBalance():BiomeBalance.DEFAULT;
        float root=channel(0,x,z);
        float metres=balance.adjustElevation(Math.copySign(root*root,root));
        boolean regional = active && session.profile().climateShift().isPresent();
        if (regional) metres = CoastalTerrain.height(seed, x, z, metres);
        double temperature=channel(2,x,z)+(regional ? session.profile().climateShift().orElse(0) : 0),precip=Math.max(0,channel(4,x,z));
        var island = active && session.profile().tropicalLandforms()
            ? islandColumn(metres, x, z, (float)temperature, session.profile().largeIslands()) : null;
        short height=(short)Math.max(Short.MIN_VALUE,Math.min(10000,Math.round(island == null ? metres : island.height())));
        int air=MountainHeightField.sampleForWorld(seed,x,z,height,scale,cap).surfaceY();
        if (island != null) return new Surface(air, island.biome(), 0);
        var condition=EuropeanEcology.evaluate(seed,temperature,precip,height,0,Double.POSITIVE_INFINITY,x,z,largerLandscapes,balance);
        short biome=(short)(switch(condition.zone()) {
            case DECIDUOUS_FOREST->208;case MIXED_FOREST->209;case CONIFEROUS_FOREST->215;
            case ALPINE_SNOW->32;case ROCKY_SLOPE->35;case DESERT->5;case MOUNTAIN_MEADOW->29;default->229;
        });
        if(height<0)biome=0;
        if(regional) biome=CoastalTerrain.biome(seed,x,z,metres,scale,biome);
        if(largerLandscapes) biome=EuropeanBiomeClassifier.withOpenFields(seed,x,z,biome,balance);
        if(active)
            biome=EuropeanEcology.varyBiome(seed,x,z,biome,session.profile().biomeVariety());
        // A canopy silhouette, not individual trees. Exact crowns come from near LOD/chunks.
        long hash=mix(seed^((long)Math.floorDiv(x,32)*73428767)^((long)Math.floorDiv(z,32)*912931));
        double density=(biome==108||biome==115)?condition.forestDensity()*0.4:condition.forestDensity();
        int canopy=air>63&&(biome==208||biome==209||biome==215||biome==108||biome==115)
                &&((hash>>>11)*0x1.0p-53)<density*TREE_DENSITY
                ?(biome==215||biome==115?20:14)+(int)(hash&7):0;
        return new Surface(air,biome,canopy);
    }
    private TropicalLandforms.IslandColumn islandColumn(float seabed, int x, int z, float temperature, boolean largeIslands) {
        if (seabed >= -40) return null;
        int cell = TropicalLandforms.ISLAND_CELL;
        int cx = Math.floorDiv(x, cell), cz = Math.floorDiv(z, cell);
        long key = (((long)cx << 32) | (cz & 0xffffffffL)) << 1 | (largeIslands ? 1L : 0L);
        var group = islandGroups.computeIfAbsent(key, ignored -> TropicalLandforms.islands(seed, cx, cz, largeIslands));
        return TropicalLandforms.islandColumn(group, seabed, x, z, temperature, scale);
    }

    private static long mix(long x){x=(x^(x>>>30))*0xbf58476d1ce4e5b9L;x=(x^(x>>>27))*0x94d049bb133111ebL;return x^(x>>>31);}
}

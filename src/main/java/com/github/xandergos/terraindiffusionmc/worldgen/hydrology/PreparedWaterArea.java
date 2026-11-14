package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WaterGenerationOptions;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/** Immutable, finite catchment. Coordinates and heights are Minecraft blocks. */
public final class PreparedWaterArea implements WorldSession.RiverOverlay {
    public static final int NONE = Integer.MIN_VALUE;
    public static final int MAX_SIDE = 1536;
    public static final int COLLAR = 8;
    public static final int MAX_DEPTH = 64;
    public static final int RIVER_MIN_CATCHMENT_CELLS = 96;
    private static final short FOOTHILL_HEIGHT_METRES = 1500;
    private final int x, z, side;
    private final int[] firstAir, water;
    private final short[] currents;
    private final int wetColumns;
    private final List<Waterfall> waterfalls;

    PreparedWaterArea(int x, int z, int side, int[] firstAir, int[] water) {
        this(x,z,side,firstAir,water,List.of());
    }
    PreparedWaterArea(int x,int z,int side,int[] firstAir,int[] water,List<Waterfall> waterfalls) {
        this(x,z,side,firstAir,water,waterfalls,null);
    }
    PreparedWaterArea(int x,int z,int side,int[] firstAir,int[] water,List<Waterfall> waterfalls,short[] currents) {
        if (side < 32 || side > MAX_SIDE || side % 2 != 0)
            throw new IllegalArgumentException("Water area size must be even, 32.." + MAX_SIDE);
        Math.addExact(x, side); Math.addExact(z, side);
        if (firstAir.length != side*side || water.length != firstAir.length)
            throw new IllegalArgumentException("Invalid water area arrays");
        this.x=x; this.z=z; this.side=side;
        this.firstAir=firstAir.clone(); this.water=water.clone();
        this.currents=RiverCurrent.freeze(currents,water);
        int count=0;
        for (int i=0; i<water.length; i++) {
            if (firstAir[i] < -1024 || firstAir[i] > 4096)
                throw new IllegalArgumentException("Invalid bed height");
            if (water[i]!=NONE) {
                int lx=i%side, lz=i/side;
                if (lx<COLLAR || lz<COLLAR || lx>=side-COLLAR || lz>=side-COLLAR
                    || water[i]<63 || water[i]>=4096 || water[i]<firstAir[i]
                    || (long)water[i]-firstAir[i]+1>MAX_DEPTH)
                    throw new IllegalArgumentException("Invalid water column at ("+(x+lx)+", "+(z+lz)+"): firstAir="+firstAir[i]+", water="+water[i]);
                count++;
            }
        }
        this.wetColumns=count;
        this.waterfalls=List.copyOf(waterfalls);
        if(this.waterfalls.size()>1) throw new IllegalArgumentException("Only one prototype waterfall per area");
        for(var fall:this.waterfalls) {
            if(!fall.inside(x,z,side)) throw new IllegalArgumentException("Waterfall outside prepared area");
            for(int t=-fall.radius();t<=fall.radius();t++) {
                int lip=(fall.worldZ(0,t)-z)*side+fall.worldX(0,t)-x;
                int foot=(fall.worldZ(1,t)-z)*side+fall.worldX(1,t)-x;
                if(water[lip]!=fall.upperY()||water[foot]!=fall.lowerY()||firstAir[foot]>fall.lowerY())
                    throw new IllegalArgumentException("Waterfall metadata does not match terrain");
            }
        }
    }

    public static PreparedWaterArea plan(long seed, int x, int z, int[][] rawFirstAir) {
        return plan(seed,x,z,rawFirstAir,WaterGenerationOptions.DEFAULT,2);
    }
    static PreparedWaterArea plan(long seed,int x,int z,int[][] rawFirstAir,WaterGenerationOptions options,int scale) {
        var data=calculate(seed,x,z,rawFirstAir,null,1,true,true,options,scale);
        for(var fall:data.falls()) WaterfallPlanner.apply(fall,x,z,rawFirstAir.length,data.air(),data.water(),data.currents());
        return new PreparedWaterArea(x,z,rawFirstAir.length,data.air(),data.water(),data.falls(),data.currents());
    }
    record Owner(int x,int z,int side) {
        boolean contains(int wx,int wz){return (long)wx-x>=0&&(long)wz-z>=0&&(long)wx-x<side&&(long)wz-z<side;}
    }
    record PlanningData(int[] air,int[] water,boolean[] lake,List<Waterfall> falls,short[] currents) {}
    static PlanningData calculate(long seed,int x,int z,int[][] rawFirstAir,Owner owner) {
        return calculate(seed,x,z,rawFirstAir,owner,1);
    }
    static PlanningData calculate(long seed,int x,int z,int[][] rawFirstAir,Owner owner,int revision) {
        return calculate(seed,x,z,rawFirstAir,owner,revision,revision>=5,revision>=5,WaterGenerationOptions.DEFAULT,2);
    }
    static PlanningData calculate(long seed,int x,int z,int[][] rawFirstAir,Owner owner,int revision,
                                  boolean liveCurrents,boolean varyGeometry,WaterGenerationOptions options,int scale) {
        // Use the same real-world foothill elevation at every world scale.
        int riverCeiling=options.mountainRivers()?Integer.MAX_VALUE:
            HeightConverter.convertToMinecraftHeight(FOOTHILL_HEIGHT_METRES,scale);
        int side=rawFirstAir.length;
        if (side<32 || side>MAX_SIDE || side%2!=0) throw new IllegalArgumentException("Invalid area size");
        int n=side/2;
        int[][] ground=new int[n][n];
        int[] air=new int[side*side], water=new int[side*side];
        Arrays.fill(water,NONE);
        for(int dz=0;dz<side;dz++) {
            if(rawFirstAir[dz].length!=side) throw new IllegalArgumentException("Area must be square");
            for(int dx=0;dx<side;dx++) air[dz*side+dx]=rawFirstAir[dz][dx];
        }
        for(int cz=0;cz<n;cz++) for(int cx=0;cx<n;cx++) {
            int low=Integer.MAX_VALUE;
            for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++)
                low=Math.min(low,air[(cz*2+dz)*side+cx*2+dx]-1);
            ground[cz][cx]=low;
        }
        var plan=DrainagePlanner.planPrepared(seed,x,z,ground,options.lakeThreshold());
        // Only whole, contained lake components qualify. Never cut a lake at the area edge.
        boolean[] lake=new boolean[n*n], blockedLake=new boolean[n*n], seen=new boolean[n*n];
        ArrayDeque<Integer> queue=new ArrayDeque<>(), component=new ArrayDeque<>();
        for(int cz=0;cz<n;cz++) for(int cx=0;cx<n;cx++) {
            int start=cz*n+cx;
            if(seen[start] || plan.kind(cx,cz)!=DrainagePlanner.CellKind.LAKE) continue;
            int level=plan.filledHeight(cx,cz);
            boolean contained=level>63;
            int minimum=start;
            queue.add(start); seen[start]=true; component.clear();
            while(!queue.isEmpty()) {
                int c=queue.removeFirst(), px=c%n,pz=c/n; component.add(c);
                if(ground[pz][px]<ground[minimum/n][minimum%n]
                    ||ground[pz][px]==ground[minimum/n][minimum%n]&&c<minimum) minimum=c;
                if(px*2<COLLAR || pz*2<COLLAR || px*2>=side-COLLAR || pz*2>=side-COLLAR) contained=false;
                for(int d=0;d<8;d++) {
                    int nx=px+DrainagePlanner.DX[d],nz=pz+DrainagePlanner.DZ[d];
                    if(nx>=0&&nz>=0&&nx<n&&nz<n) {
                        int nc=nz*n+nx;
                        if(!seen[nc]&&plan.kind(nx,nz)==DrainagePlanner.CellKind.LAKE&&plan.filledHeight(nx,nz)==level) {
                            seen[nc]=true; queue.add(nc);
                        }
                    }
                }
            }
            if(contained) {
                boolean owned=owner==null||owner.contains(x+minimum%n*2,z+minimum/n*2);
                if(revision>=2&&!WaterFrequency.keep(seed,x+minimum%n*2,z+minimum/n*2,true,revision)) owned=false;
                for(int c:component) {lake[c]=owned;blockedLake[c]=!owned;}
            }
        }
        // A stream must reach the sea or a contained lake, not an arbitrary dry boundary.
        byte[] destination=new byte[n*n];
        byte[] outletDistance=liveCurrents?new byte[n*n]:null;
        int[] path=new int[n*n];
        for(int c=0;c<n*n;c++) {
            if(destination[c]!=0) continue;
            int cursor=c, length=0;
            byte result;
            while(true) {
                if(destination[cursor]!=0) { result=destination[cursor]; break; }
                int cx=cursor%n,cz=cursor/n;
                if(blockedLake[cursor]) {result=2;break;}
                if(revision>=2&&lake[cursor]) {result=1;break;}
                // Revision 1 stopped at dry y=64 beaches. A real sea column starts at first-air <=62.
                if(ground[cz][cx]<=(revision>=2?61:63)) {result=(byte)((owner==null||owner.contains(x+cx*2,z+cz*2))
                    &&(revision<2||WaterFrequency.keep(seed,x+cx*2,z+cz*2,false,revision))?1:2);break;}
                if(lake[cursor]) {result=1;break;}
                if(cx*2<COLLAR||cz*2<COLLAR||cx*2>=side-COLLAR||cz*2>=side-COLLAR) { result=2; break; }
                if(length==path.length) throw new IllegalStateException("Cyclic drainage");
                path[length++]=cursor;
                int d=plan.direction(cx,cz);
                if(d<0) {result=2;break;}
                cursor=(cz+DrainagePlanner.DZ[d])*n+cx+DrainagePlanner.DX[d];
            }
            destination[cursor]=result;
            int distance=liveCurrents?outletDistance[cursor]:0;
            for(int i=length-1;i>=0;i--) {
                destination[path[i]]=result;
                if(liveCurrents) outletDistance[path[i]]=(byte)(distance=Math.min(12,distance+1));
            }
        }
        boolean[] candidates=new boolean[n*n];
        for(int cz=0;cz<n;cz++) for(int cx=0;cx<n;cx++) {
            int c=cz*n+cx;
            candidates[c]=plan.filledHeight(cx,cz)<=riverCeiling&&!lake[c]&&destination[c]==1&&ground[cz][cx]>=(revision>=2?62:63)
                &&plan.accumulation(cx,cz)>=RIVER_MIN_CATCHMENT_CELLS;
        }
        boolean[] channels=RiverBranches.select(plan,candidates,seed,x,z);
        if(revision>=4)channels=NaturalChannels.select(plan,channels,rawFirstAir);
        // Lakes occupy the whole depression, including cells below the river threshold.
        for(int cz=0;cz<n;cz++) for(int cx=0;cx<n;cx++) {
            int c=cz*n+cx;
            if(lake[c]) {
                int level=plan.filledHeight(cx,cz);
                for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++) {
                    int px=cx*2+dx,pz=cz*2+dz;
                    if(air[pz*side+px]-1<level) setWater(water,side,px,pz,level);
                }
            }
        }
        short[] currents=liveCurrents?new short[water.length]:null;
        byte[] nearest=liveCurrents?new byte[water.length]:null;
        if(liveCurrents) Arrays.fill(nearest,Byte.MAX_VALUE);
        RiverCurves.rasterize(plan,channels,rawFirstAir,(cell,px,pz,y,flowX,flowZ,slope)-> {
            if(y>riverCeiling) return;
            long accumulation=plan.accumulation(cell%n,cell/n);
            int radius=riverWidth(accumulation)/2;
            if(varyGeometry) radius=Math.clamp(radius+(int)Math.round(2.0*RiverBanks.relief(seed^0x7531L,x+px,z+pz)
                *(options.widthVariation()/100.0)),1,9);
            short current=liveCurrents?RiverCurrent.pack(flowX,flowZ,slope,accumulation,outletDistance[cell]/12.0):0;
            for(int dz=-radius;dz<=radius;dz++) for(int dx=-radius;dx<=radius;dx++) {
                int distance=dx*dx+dz*dz;
                int wx=px+dx,wz=pz+dz;
                if(distance>radius*radius+1||wx<COLLAR||wz<COLLAR||wx>=side-COLLAR||wz>=side-COLLAR) continue;
                if(rawFirstAir[wz][wx]-1>riverCeiling) continue;
                int p=wz*side+wx;
                if(liveCurrents&&(y>water[p]||y==water[p]&&distance<=nearest[p])) {
                    currents[p]=lake[(wz/2)*n+wx/2]?0:current;
                    nearest[p]=(byte)distance;
                }
                water[p]=Math.max(water[p],y);
            }
        });
        ShoreAlcoves.extend(seed,x,z,side,air,water);
        if(revision>=2) RiverMouths.connect(plan,channels,rawFirstAir,water);
        if(liveCurrents) addLakeThroughflow(plan,channels,lake,water,currents,side);
        if(liveCurrents) for(int p=0;p<water.length;p++) if(water[p]<=62) currents[p]=0;
        if(owner==null) RiverBanks.shape(seed,x,z,side,air,water,lake,currents,p->options.depthVariation());
        if(owner==null&&revision>=3) BankPeaks.lower(side,air,water);
        if(owner==null&&revision>=4) BankNeedles.lower(side,air,water);
        var falls=side>=512?WaterfallPlanner.select(x,z,side,rawFirstAir,water,owner):List.<Waterfall>of();
        return new PlanningData(air,water,lake,falls,currents);
    }

    private static void addLakeThroughflow(DrainagePlanner.DrainagePlan plan,boolean[] channels,
                                           boolean[] lake,int[] water,short[] currents,int side) {
        int n=plan.width();
        boolean[] connected=new boolean[lake.length];
        ArrayDeque<Integer> frontier=new ArrayDeque<>();
        for(int c=0;c<channels.length;c++) if(channels[c]) {
            int x=c%n,z=c/n,d=plan.direction(x,z);
            if(d<0) continue;
            int next=(z+DrainagePlanner.DZ[d])*n+x+DrainagePlanner.DX[d];
            if(lake[next]&&!connected[next]) {
                connected[next]=true;
                frontier.add(next);
            }
        }
        while(!frontier.isEmpty()) {
            int c=frontier.removeFirst(),x=c%n,z=c/n;
            int level=plan.filledHeight(x,z);
            int dx=0,dz=0,nextX=x,nextZ=z;
            for(int step=0;step<3;step++) {
                int d=plan.direction(nextX,nextZ);
                if(d<0) break;
                dx+=DrainagePlanner.DX[d];
                dz+=DrainagePlanner.DZ[d];
                nextX=x+dx;
                nextZ=z+dz;
            }
            short flow=RiverCurrent.pack(dx,dz,0,plan.accumulation(x,z),0.42);
            for(int oz=0;oz<2;oz++) for(int ox=0;ox<2;ox++) {
                int p=(z*2+oz)*side+x*2+ox;
                if(water[p]==level) currents[p]=flow;
            }
            for(int d=0;d<8;d++) {
                int nx=x+DrainagePlanner.DX[d],nz=z+DrainagePlanner.DZ[d];
                if(nx<0||nz<0||nx>=n||nz>=n) continue;
                int next=nz*n+nx;
                if(lake[next]&&!connected[next]&&plan.filledHeight(nx,nz)==level) {
                    connected[next]=true;
                    frontier.add(next);
                }
            }
        }
    }

    private static void setWater(int[] water,int side,int x,int z,int y) {
        if(x>=COLLAR&&z>=COLLAR&&x<side-COLLAR&&z<side-COLLAR) {
            int p=z*side+x;
            water[p]=Math.max(water[p],y);
        }
    }
    private static int riverWidth(long accumulation) {
        long ratio=Math.max(1,accumulation/RIVER_MIN_CATCHMENT_CELLS);
        return Math.min(15,3+2*(63-Long.numberOfLeadingZeros(ratio)));
    }
    public boolean contains(int wx,int wz) {return (long)wx-x>=0&&(long)wz-z>=0&&(long)wx-x<side&&(long)wz-z<side;}
    public boolean overlaps(PreparedWaterArea other) {return (long)x+side>other.x&&(long)other.x+other.side>x&&(long)z+side>other.z&&(long)other.z+other.side>z;}
    @Override public boolean isReady(int wx,int wz) {return contains(wx,wz);}
    @Override public int carvedHeight(int wx,int wz,int rawY) {return contains(wx,wz)?firstAir[(wz-z)*side+wx-x]:rawY;}
    @Override public int waterY(int wx,int wz) {return contains(wx,wz)?water[(wz-z)*side+wx-x]:NONE;}
    @Override public short currentAt(int wx,int wy,int wz) {
        if(currents==null||!contains(wx,wz)) return 0;
        int p=(wz-z)*side+wx-x;
        return wy>=firstAir[p]&&wy<=water[p]?currents[p]:0;
    }
    @Override public int bedY(int wx,int wz,int rawY) {return carvedHeight(wx,wz,rawY)-1;}
    public int x(){return x;} public int z(){return z;} public int side(){return side;}
    public int wetColumns(){return wetColumns;}
    @Override public List<Waterfall> waterfalls(){return waterfalls;}
    @Override public Waterfall waterfallAt(int wx,int wz){for(var f:waterfalls) if(f.contains(wx,wz)) return f;return null;}
    int[] firstAirCopy(){return firstAir.clone();} int[] waterCopy(){return water.clone();}
    void writeCurrents(java.io.DataOutput out) throws java.io.IOException {RiverCurrent.write(out,currents);}
}

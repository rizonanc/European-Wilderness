package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.preparation.PreparationProgress;
import com.github.xandergos.terraindiffusionmc.client.ClientPerformance;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.PerformanceDiagnostics;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.WorkTimeline.Stage.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors;
import static com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

/** Optional singleplayer/LAN-host bridge. No remote seed guessing or actual chunk generation. */
public final class VoxyCompat {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(VoxyCompat.class);
    private static volatile Voxy016Api api;private static volatile Context context;
    private static boolean apiPending;
    private static java.util.UUID guardSession;private static LodChunkGuard realGuard;
    private static synchronized LodChunkGuard guardFor(WorldSession ticket,net.minecraft.server.MinecraftServer server) {
        if(!ticket.id().equals(guardSession)){guardSession=ticket.id();realGuard=new LodChunkGuard(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("region"));}
        return realGuard;
    }
    private static void loaded(Context ctx,int x,int z) {
        ctx.guard.markLoaded(x,z);var work=ctx.active;
        if(ctx.coarse!=null)ctx.coarse.realChunk(x,z);
        if(work!=null&&Math.floorDiv(x,2)==work.cell.x()&&Math.floorDiv(z,2)==work.cell.z())work.cancelled.set(true);
    }
    private static volatile boolean enabled;private static volatile boolean generate;
    private static int radius;private static boolean manualRadius;private static String unavailable="Voxy не установлен";
    private static long nextReport;
    private VoxyCompat(){}
    public static String supportProblem(String version,boolean seedGen) {
        return VoxySupport.problem(version,seedGen);
    }
    public static void install() {
        enabled=TerrainDiffusionConfig.voxyEnabled();radius=TerrainDiffusionConfig.voxyRadius();generate=TerrainDiffusionConfig.voxyPrepareAhead();
        manualRadius=TerrainDiffusionConfig.voxyManualRadius();
        ChunkyCommands.install();
        var loader=FabricLoader.getInstance();var mod=loader.getModContainer("voxy");
        if(mod.isPresent()) {
            boolean seedGen=loader.getAllMods().stream().anyMatch(m->{String text=(m.getMetadata().getId()+m.getMetadata().getName()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");return text.contains("voxyseedgen");});
            unavailable=supportProblem(mod.get().getMetadata().getVersion().getFriendlyString(),seedGen);
            apiPending=unavailable==null;
            if(!apiPending)emit(Code.VOX_VERSION,current("VOXY_BRIDGE_INIT"),new IllegalArgumentException(unavailable),Outcome.DEGRADED,"Bridge disabled; native generation unchanged",false);
        }
        if(mod.isEmpty())emit(Code.VOX_ABSENT,current("VOXY_BRIDGE_INIT"),null,Outcome.DEGRADED,"Optional bridge not installed; native generation unchanged",false);
        WorldGenCompat.install();
        ServerChunkEvents.CHUNK_LOAD.register((world,chunk)->{
            if(api==null)return;
            var ticket=WorldSession.current();
            if(ticket==null||!ticket.isActive()||!world.getRegistryKey().equals(World.OVERWORLD)
                ||!(world.getChunkManager().getChunkGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource))return;
            var pos=chunk.getPos();guardFor(ticket,world.getServer()).markLoaded(pos.x,pos.z);
            var ctx=context;if(ctx!=null&&ctx.ticket==ticket)loaded(ctx,pos.x,pos.z);
            var cs=chunksState;if(cs!=null&&cs.ticket==ticket)cs.controller.realChunk(chunk);
        });
        ClientTickEvents.END_CLIENT_TICK.register(VoxyCompat::tick);
        ClientChunkEvents.CHUNK_LOAD.register((world,chunk)->{
            var ctx=context;if(ctx!=null&&ctx.world==world) {var pos=chunk.getPos();loaded(ctx,pos.x,pos.z);}
            var cs=chunksState;if(cs!=null&&cs.world==world)cs.controller.realChunk(chunk);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->dispatcher.register(literal("td-voxy")
            .then(literal("status").executes(c->{c.getSource().sendFeedback(Text.literal(status()));c.getSource().sendFeedback(Text.literal("frames="+ClientPerformance.FRAMES.statistics()+(api==null?"":" transfer="+api.statistics())));
                if(api!=null){String water=VoxyWaterDiagnostics.snapshot();c.getSource().sendFeedback(Text.literal(water));c.getSource().sendFeedback(Text.literal(WaterMipRebuild.status()));c.getSource().sendFeedback(Text.literal(VegetationMipRebuild.status()));LOG.info("{}",water);LOG.info("{}",VoxyWaterFrameDiagnostics.status());}return 1;}))
            .then(literal("census").executes(c->{c.getSource().sendFeedback(Text.literal(census(8)));return 1;})
                .then(argument("radiusChunks",IntegerArgumentType.integer(1,32)).executes(c->{c.getSource().sendFeedback(Text.literal(census(IntegerArgumentType.getInteger(c,"radiusChunks"))));return 1;})))
            .then(literal("inspect").then(argument("level",IntegerArgumentType.integer(0,4))
                .then(argument("sx",IntegerArgumentType.integer(-1000000,1000000))
                .then(argument("sy",IntegerArgumentType.integer(-128,127))
                .then(argument("sz",IntegerArgumentType.integer(-1000000,1000000)).executes(c->{
                    c.getSource().sendFeedback(Text.literal(census(0,new int[]{IntegerArgumentType.getInteger(c,"level"),IntegerArgumentType.getInteger(c,"sx"),IntegerArgumentType.getInteger(c,"sy"),IntegerArgumentType.getInteger(c,"sz")})));return 1;}))))))
            .then(literal("inspect-at").then(argument("x",IntegerArgumentType.integer(-29999984,29999984))
                .then(argument("y",IntegerArgumentType.integer(-4096,4096))
                .then(argument("z",IntegerArgumentType.integer(-29999984,29999984))
                .then(argument("level",IntegerArgumentType.integer(0,4)).executes(c->{
                    int l=IntegerArgumentType.getInteger(c,"level"),x=IntegerArgumentType.getInteger(c,"x"),y=IntegerArgumentType.getInteger(c,"y"),z=IntegerArgumentType.getInteger(c,"z"),side=32<<l;
                    c.getSource().sendFeedback(Text.literal(census(0,new int[]{l,Math.floorDiv(x,side),Math.floorDiv(y,side),Math.floorDiv(z,side)},new int[]{x,y,z})));return 1;}))))))
            .then(literal("vegetation-rebuild").executes(c->{c.getSource().sendFeedback(Text.literal(rebuildVegetation(32)));return 1;})
                .then(argument("radiusChunks",IntegerArgumentType.integer(1,64)).executes(c->{c.getSource().sendFeedback(Text.literal(rebuildVegetation(IntegerArgumentType.getInteger(c,"radiusChunks"))));return 1;})))
            .then(literal("vegetation-rebuild-stop").executes(c->{c.getSource().sendFeedback(Text.literal(VegetationMipRebuild.stop()));return 1;}))
            .then(literal("water-trace").executes(c->{c.getSource().sendFeedback(Text.literal(VoxyWaterFrameDiagnostics.request()));return 1;}))
            .then(literal("water-rebuild").executes(c->{c.getSource().sendFeedback(Text.literal(rebuildWater(16)));return 1;})
                .then(argument("radiusChunks",IntegerArgumentType.integer(1,256)).executes(c->{
                    c.getSource().sendFeedback(Text.literal(rebuildWater(IntegerArgumentType.getInteger(c,"radiusChunks"))));return 1;})))
            .then(literal("water-rebuild-stop").executes(c->{c.getSource().sendFeedback(Text.literal(WaterMipRebuild.stop()));return 1;}))
            .then(literal("water-rebuild-at")
                .then(argument("x",IntegerArgumentType.integer(-30000000,30000000))
                .then(argument("z",IntegerArgumentType.integer(-30000000,30000000))
                .then(argument("radiusChunks",IntegerArgumentType.integer(1,256)).executes(c->{
                    c.getSource().sendFeedback(Text.literal(rebuildWaterAt(IntegerArgumentType.getInteger(c,"x"),IntegerArgumentType.getInteger(c,"z"),IntegerArgumentType.getInteger(c,"radiusChunks"))));return 1;})))))
            .then(literal("start").then(argument("radius",IntegerArgumentType.integer(64,LodScan.MAX_RADIUS_BLOCKS)).executes(c->{
                if(TerrainDiffusionConfig.voxyNativeChunks()) {enabled=true;c.getSource().sendFeedback(Text.literal("Voxy получает реальные чанки. Для явной предгенерации: /td-voxy chunky <радиус в чанках>. /chunky progress"));return 1;}
                if(api==null){c.getSource().sendError(Text.literal(unavailable));return 0;}
                radius=IntegerArgumentType.getInteger(c,"radius");manualRadius=true;enabled=true;generate=true;if(context!=null&&context.closed) retire();
                c.getSource().sendFeedback(Text.literal("Voxy: фоновый расчёт European Wilderness и LOD включён, радиус "+radius+" блоков. /td-voxy status; /td-voxy stop"));return 1;
            })))
            .then(literal("stop").executes(c->{enabled=false;generate=false;retire();retireChunksCoarse();c.getSource().sendFeedback(Text.literal("Мост Voxy остановлен; сохранённые данные оставлены."));return 1;}))));
        if(api!=null) LOG.info("Optional Voxy 0.2.16 continuous streaming available; VoxyWorldGen 2.2.4 coordination: {}",WorldGenCompat.present());
    }
    public static boolean usable(){return api!=null;}
    /** Phase 4.5: pure eligibility for the chunks-mode renderer-only Native Neural LOD.
     * Deliberately independent of the prepared bridge: in chunks mode no persistent TD
     * writer runs, so an experimental coarse preview can coexist with native ingest. */
    static boolean chunksCoarseGate(boolean voxyEnabled,boolean nativeChunks,boolean experimental) {
        return voxyEnabled&&nativeChunks&&experimental;
    }
    static boolean preparedGate(boolean voxyEnabled,boolean nativeChunks){return voxyEnabled&&!nativeChunks;}
    static boolean preparedEnabled(){return preparedGate(enabled&&TerrainDiffusionConfig.voxyEnabled(),TerrainDiffusionConfig.voxyNativeChunks());}
    /** Phase 4.4 read-only storage census around the player (no writes, no meshing). */
    private static String census(int radiusChunks) {
        return census(radiusChunks,null);
    }
    private static String census(int radiusChunks,int[] section) {
        return census(radiusChunks,section,null);
    }
    private static String census(int radiusChunks,int[] section,int[] point) {
        var client=net.minecraft.client.MinecraftClient.getInstance();
        if(api==null)return "Voxy census: "+unavailable;
        var world=client.world;var player=client.player;
        if(world==null||player==null)return "Voxy census: зайдите в мир";
        try{
            int cx=player.getBlockX()>>4,cz=player.getBlockZ()>>4,bottom=world.getBottomY(),top=world.getTopYInclusive();
            var ticket=WorldSession.current();
            String origin="seed="+(ticket==null?"UNKNOWN":ticket.worldSeed())+" cameraXYZ="+player.getX()+","+player.getY()+","+player.getZ()
                +" yawPitch="+player.getYaw()+","+player.getPitch()+"; ";
            var samples=section==null?VoxyCensus.capture(world,cx,cz):java.util.List.<VoxyCensus.Sample>of();
            var bound=api.acquire(world);if(bound==null)return "Voxy census: движок недоступен";
            boolean queued=PerformanceDiagnostics.requestSnapshot(()->{
                String result;
                try(bound){
                    if(ticket!=WorldSession.current()||ticket!=null&&!ticket.isActive()||!bound.active())result="Voxy census: отменено после смены мира";
                    else {var reader=new VoxyCensus.Reader(){
                        public int mask(int l,int x,int y,int z) throws Exception{return bound.nonEmptyChildrenMask(l,x,y,z);}
                        public long[] raw(int l,int x,int y,int z) throws Exception{return bound.rawCopy(l,x,y,z);}
                    };
                    result=section==null?VoxyCensus.scan(reader,samples,radiusChunks,bottom,top,cx,cz)
                        :VoxyCensus.inspect(reader,section[0],section[1],section[2],section[3])+"; "+(point==null?CoarseRenderHooks.describe(bound.identity(),
                            me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(section[0],section[1],section[2],section[3])):
                            CoarseRenderHooks.describePoint(bound.identity(),me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(section[0],section[1],section[2],section[3]),point[0],point[1],point[2]));}
                }catch(Exception|LinkageError failure){
                    emit(Code.VOX_ASYNC,DiagnosticErrors.current("VOXY_CENSUS"),failure,Outcome.DEGRADED,"Read-only census failed; world unchanged",false);
                    LOG.warn("Voxy census failed",failure);result="Voxy census: ошибка "+failure.getClass().getSimpleName();
                }
                result=origin+result;LOG.info("{}",result);String message=result;
                client.execute(()->{if(client.world==world&&client.player!=null)client.player.sendMessage(Text.literal(message),false);});
                return java.util.Map.of("voxy_census",result);
            });
            if(!queued){bound.close();return "Voxy census: диагностическая очередь занята";}
            return "Voxy census: запущено в фоне, результат появится в чате и latest.log";
        }
        catch(Exception|LinkageError failure){LOG.warn("Voxy census failed",failure);return "Voxy census: ошибка "+failure.getClass().getSimpleName()+" (подробности в логе)";}
    }

    // ---- Phase 4.5: renderer-only Native Neural LOD in chunks mode ----
    private static volatile ChunksCoarse chunksState;
    /** Phase 4.5: an experimental coarse preview may run beside native chunk ingest. The
     * prepared bridge stays fully retired in this mode, so nothing approximate reaches
     * RocksDB; the preview is renderer-only and real chunks remain authoritative. */
    private static void tickChunksCoarse(MinecraftClient client,WorldSession ticket,net.minecraft.server.MinecraftServer server,ClientWorld world) {
        if(!chunksCoarseGate(enabled&&TerrainDiffusionConfig.voxyEnabled(),TerrainDiffusionConfig.voxyNativeChunks(),TerrainDiffusionConfig.voxyCoarseExperimental())){retireChunksCoarse();return;}
        if(api==null||world==null||server==null||ticket==null||!ticket.isActive()
            ||!world.getRegistryKey().equals(World.OVERWORLD)
            ||!(server.getOverworld().getChunkManager().getChunkGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)){retireChunksCoarse();return;}
        if(chunksState!=null&&(chunksState.closed||chunksState.world!=world||chunksState.ticket!=ticket))retireChunksCoarse();
        try {
            if(!api.available(world)){retireChunksCoarse();return;}
            if(chunksState!=null&&chunksState.engineIdentity!=api.engineIdentity(world))retireChunksCoarse();
        }catch(ReflectiveOperationException|LinkageError failure) {
            emit(Code.VOX_COARSE,DiagnosticErrors.current("VOXY_CHUNKS_COARSE_ACCESS"),failure,Outcome.DEGRADED,
                "Experimental chunks-mode coarse disabled; native generation unchanged",false);
            retireChunksCoarse();return;
        }
        if(chunksState==null) {
            try{chunksState=new ChunksCoarse(client,world,ticket);}
            catch(Exception|LinkageError failure) {
                emit(Code.VOX_COARSE,DiagnosticErrors.current("VOXY_CHUNKS_COARSE_INIT"),failure,Outcome.DEGRADED,
                    "Experimental coarse preview disabled; native generation unchanged",false);
                retireChunksCoarse();return;
            }
            LOG.info("Experimental Native Neural LOD active in chunks mode (renderer-only; no persistent coarse writes)");
        }
        var state=chunksState;
        if(client.player!=null) {
            int radius;
            try{radius=api.horizonBlocks();}catch(ReflectiveOperationException failure){radius=16384;}
            state.controller.update(client.player.getBlockX(),client.player.getBlockZ(),radius);
            // The provider can attach after CHUNK_LOAD; seed seam anchors from already loaded chunks too.
            int reach=Math.min(32,client.options.getViewDistance().getValue()),width=reach*2+1;
            for(int checked=0;checked<8;checked++){
                int i=state.observedIndex++%(width*width);
                int cx=(client.player.getBlockX()>>4)+i%width-reach,cz=(client.player.getBlockZ()>>4)+i/width-reach;
                var chunk=world.getChunkManager().getChunk(cx,cz,net.minecraft.world.chunk.ChunkStatus.FULL,false);
                if(chunk!=null)state.controller.realChunk(chunk);
            }
        }
    }
    private static void retireChunksCoarse() {
        if(chunksState!=null){chunksState.close();chunksState=null;}
    }
    private static final class ChunksCoarse {
        final MinecraftClient client;final ClientWorld world;final WorldSession ticket;
        final Object engineIdentity;final LodChunkGuard guard;
        final CoarseLodController controller;
        int observedIndex;
        volatile boolean closed;
        ChunksCoarse(MinecraftClient client,ClientWorld world,WorldSession ticket) throws Exception {
            this.client=client;this.world=world;this.ticket=ticket;
            this.engineIdentity=api.engineIdentity(world);
            var biomes=new TerrainDiffusionBiomeSource(world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME));
            biomes.recordedBiome((short)1);
            this.guard=guardFor(ticket,client.getServer());
            this.controller=new CoarseLodController(client,world,ticket,api,biomes,guard,this::current,
                ()->TerrainDiffusionConfig.voxyPrepareAhead()&&ticket.backgroundAllowed());
        }
        boolean current(){return !closed&&ticket.isActive()&&client.world==world
            &&chunksCoarseGate(enabled&&TerrainDiffusionConfig.voxyEnabled(),TerrainDiffusionConfig.voxyNativeChunks(),TerrainDiffusionConfig.voxyCoarseExperimental());}
        void close(){if(closed)return;closed=true;controller.close();}
    }
    public static String status() {
        if(api==null) return unavailable==null?"Voxy: ожидание инициализации":unavailable;
        if(TerrainDiffusionConfig.voxyNativeChunks()){
            var cs=chunksState;
            String coarse=cs==null
                ?(TerrainDiffusionConfig.voxyCoarseExperimental()?" NN-LOD: ожидание условий (Voxy активен в мире TD)":" NN-LOD: выключен (coarse_experimental=false)")
                :" NN-LOD активен (renderer-only): "+cs.controller.statistics();
            return "Voxy: реальные чанки. Chunky: /td-voxy chunky <радиус в чанках>; /chunky progress."+coarse;
        }
        if(!WorldGenCompat.safeToStream()) return WorldGenCompat.problem();
        if(!enabled) return "Мост Voxy выключен";
        var ctx=context;return ctx==null?"Voxy: ожидание мира TD и включённой отрисовки/ingest Voxy. Настройки TD → Видимость → Настройки Voxy":
            "Voxy: "+(generate?"расчёт и экспорт":"экспорт готовых планов")+", радиус "+radius+" блоков ("+(radius/16.0)+" чанков). "+ctx.progress
            +". Порций 32³ записано: "+ctx.written.get()+", проверок пропущено: "+ctx.skipped.get()+", ошибок: "+ctx.errors.get()
            +", ошибок чтения защиты: "+ctx.guard.failures()+(ctx.guard.overflow()?". Достигнут лимит защиты чанков":"")
            +". Достройка: "+ctx.scan.pendingDetails()+", из них ожидают лес: "+ctx.scan.pendingForests()
            +(ctx.coarse==null?"":". Точный радиус "+ctx.detailRadius()+" блоков; "+ctx.coarse.statistics())
            +(WorldGenCompat.present()?". VoxyWorldGen 2.2.4: прямой поток вместо FULL-генерации":"");
    }
    private static void retire(){if(context!=null) {context.closed=true;if(context.coarse!=null)context.coarse.close();context.targetApi.clearCache();}context=null;}
    public static String rebuildWater(int radiusChunks) {
        if(api==null)return unavailable;
        try{return WaterMipRebuild.start(api,MinecraftClient.getInstance(),radiusChunks);}
        catch(ReflectiveOperationException failure){LOG.warn("Cannot start water LOD rebuild",failure);return "Пересчёт LOD воды недоступен: "+failure.getClass().getSimpleName();}
    }
    public static String rebuildVegetation(int radiusChunks) {
        if(api==null)return unavailable;
        try{return VegetationMipRebuild.start(api,MinecraftClient.getInstance(),radiusChunks);}
        catch(ReflectiveOperationException failure){LOG.warn("Cannot start vegetation LOD rebuild",failure);return "Пересчёт LOD травы недоступен: "+failure.getClass().getSimpleName();}
    }
    private static String rebuildWaterAt(int x,int z,int radiusChunks) {
        if(api==null)return unavailable;
        try{return WaterMipRebuild.startAt(api,MinecraftClient.getInstance(),x,z,radiusChunks);}
        catch(ReflectiveOperationException failure){LOG.warn("Cannot start targeted water LOD rebuild",failure);return "Пересчёт LOD воды недоступен: "+failure.getClass().getSimpleName();}
    }
    private static void tick(MinecraftClient client) {
        if(apiPending)try {
            if(!Voxy016Api.runtimeReady())return;
            api=new Voxy016Api();apiPending=false;
        }catch(ReflectiveOperationException|LinkageError failure){apiPending=false;unavailable="Интерфейсы Voxy недоступны: "+failure.getClass().getSimpleName();emit(Code.VOX_API,current("VOXY_BRIDGE_INIT"),failure,Outcome.DEGRADED,"Optional bridge disabled; native generation unchanged",false);}
        if(System.nanoTime()>=nextReport){nextReport=System.nanoTime()+1_000_000_000L;
            var snapshot=WorldSession.current();PerformanceDiagnostics.previewStatus(status()+" publish="+ClientPerformance.mayPublish()
                +" gate="+com.github.xandergos.terraindiffusionmc.client.ClientPerformance.publishGateReason()
                +" prepare="+(snapshot!=null&&snapshot.backgroundAllowed())+" frames="+ClientPerformance.FRAMES.statistics());}
        var ticket=WorldSession.current();var server=client.getServer();var world=client.world;
        if(TerrainDiffusionConfig.voxyNativeChunks()){retire();tickChunksCoarse(client,ticket,server,world);return;}
        retireChunksCoarse();
        if(api==null||!preparedEnabled()||world==null||server==null||ticket==null||!ticket.isActive()||ticket.automaticWater()==null
            ||!world.getRegistryKey().equals(World.OVERWORLD)||!(server.getOverworld().getChunkManager().getChunkGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {retire();return;}
        WorldGenCompat.sync();if(!WorldGenCompat.safeToStream()){retire();return;}
        if(!manualRadius)try {
            if(TerrainDiffusionConfig.voxyCoarse()||TerrainDiffusionConfig.voxyCoarseExperimental())radius=api.horizonBlocks();
            else if(WorldGenCompat.present())radius=WorldGenCompat.radius();
        }catch(ReflectiveOperationException failure){LOG.debug("Voxy render radius unavailable",failure);}
        if(!WorldGenCompat.enabled()){if(context!=null) {context.voxyAvailable=false;context.activeCancel();context.progress="Выключено в настройках VoxyWorldGen";}return;}
        try {
            if(!api.available(world)) {retire();return;}
            if(context!=null&&context.engineIdentity!=api.engineIdentity(world))retire();
        } catch(ReflectiveOperationException|LinkageError failure) {
            unavailable="Voxy недоступен: "+failure.getClass().getSimpleName();emit(Code.VOX_API,current("VOXY_BRIDGE_ACCESS"),failure,Outcome.DEGRADED,"Optional bridge disabled; native generation unchanged",false);api=null;retire();return;
        }
        if(context==null||context.world!=world||context.ticket!=ticket) {retire();
            // Resolve client-side registry entries, matching Voxy's own mapper registry.
            context=new Context(client,world,ticket,new TerrainDiffusionBiomeSource(world.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.BIOME)));
        }
        var ctx=context;ctx.voxyAvailable=true;if(ctx.closed||client.player==null) return;
        ctx.centerX=client.player.getBlockX();ctx.centerZ=client.player.getBlockZ();ctx.activeRadius=radius;
        if(ctx.coarse!=null)ctx.coarse.update(ctx.centerX,ctx.centerZ,radius);
        ctx.scan.move(client.player.getBlockX(),client.player.getBlockZ(),ctx.detailRadius());
        var active=ctx.active;if(active!=null) {long dx=(long)active.cell.x()*32+16-client.player.getBlockX(),dz=(long)active.cell.z()*32+16-client.player.getBlockZ();
            if(dx*dx+dz*dz>(long)(radius+64)*(radius+64)) active.cancelled.set(true);}
        if(ctx.busy.get()||System.nanoTime()<ctx.nextJob||!ClientPerformance.mayPublish()) return;
        for(int attempt=0;attempt<32;attempt++) {
            var cell=ctx.scan.next();if(cell==null) return;
            if(ctx.loadedMask(cell)==15) {ctx.scan.completed(cell);continue;}
            var work=new Work(cell,ctx.scan.progress(cell));ctx.active=work;ctx.busy.set(true);ctx.queue.offer(work);return;
        }
    }
    private static final class Work {
        final LodScan.Cell cell;final AtomicBoolean cancelled=new AtomicBoolean();
        final LodPublisher.Progress resume;
        LodColumn column;LodPublisher.Cursor cursor;boolean refreshed;volatile int writableMask;
        Work(LodScan.Cell cell,LodPublisher.Progress resume){this.cell=cell;this.resume=resume;}
        boolean surface(){return resume==null||resume.forestPending();}
    }
    private static final class Context {
        final MinecraftClient client;final ClientWorld world;final WorldSession ticket;final TerrainDiffusionBiomeSource biomes;
        final Voxy016Api targetApi;
        final Object engineIdentity;
        final LodChunkGuard guard;
        final LodTerrainSource source;final LodScan scan=new LodScan();final ArrayBlockingQueue<Work> queue=new ArrayBlockingQueue<>(1);
        record Preparation(int x,int z){}
        final ArrayBlockingQueue<Preparation> preparation=new ArrayBlockingQueue<>(1);
        final LodPreparationFrontier frontier;
        final CoarseLodController coarse;
        final AtomicBoolean busy=new AtomicBoolean();final AtomicLong written=new AtomicLong(),skipped=new AtomicLong(),errors=new AtomicLong();
        volatile boolean closed;volatile Work active;volatile long nextJob;volatile String progress="Ожидание готовых планов";
        volatile int centerX,centerZ,activeRadius;
        volatile boolean voxyAvailable;
        Context(MinecraftClient client,ClientWorld world,WorldSession ticket,TerrainDiffusionBiomeSource biomes) {
            this.client=client;this.world=world;this.ticket=ticket;this.biomes=biomes;targetApi=api;source=new LodTerrainSource(ticket,TerrainDiffusionConfig.voxyTrees());
            try{engineIdentity=targetApi.engineIdentity(world);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
            guard=guardFor(ticket,client.getServer());
            frontier=new LodPreparationFrontier(ticket.automaticWater().coreSize());
            // Initialise the immutable biome map on the client thread before worker use.
            biomes.recordedBiome((short)1);
            coarse=TerrainDiffusionConfig.voxyCoarseExperimental()?buildCoarse():null;
            Thread worker=new Thread(this::run,"terrain-voxy-bridge");worker.setDaemon(true);worker.start();
            Thread preparer=new Thread(this::prepare,"terrain-voxy-preparation");preparer.setDaemon(true);preparer.start();
        }
        /** Phase 4 opt-in experimental coarse horizon. Any failure disables only this preview;
         * exact LOD export and native generation continue unchanged. */
        private CoarseLodController buildCoarse() {
            try {
                return new CoarseLodController(client,world,ticket,targetApi,biomes,guard,this::current,
                    ()->generate&&ticket.backgroundAllowed());
            } catch(Exception|LinkageError failure) {
                emit(Code.VOX_COARSE,com.github.xandergos.terraindiffusionmc.worldgen.diagnostics.DiagnosticErrors.current("VOXY_COARSE_INIT"),failure,Outcome.DEGRADED,
                    "Experimental coarse preview disabled; prepared LOD export unchanged",false);
                return null;
            }
        }
        boolean current(){return !closed&&ticket.isActive()&&client.world==world&&preparedEnabled();}
        int detailRadius(){return Math.min(activeRadius,TerrainDiffusionConfig.voxyDetailRadius());}
        void activeCancel(){preparation.clear();var work=active;if(work!=null) work.cancelled.set(true);}
        int loadedMask(LodScan.Cell cell) {
            int mask=0;
            for(int z=0;z<2;z++) for(int x=0;x<2;x++)
                if(world.getChunkManager().getChunk(cell.x()*2+x,cell.z()*2+z,ChunkStatus.FULL,false)!=null) {
                    guard.markLoaded(cell.x()*2+x,cell.z()*2+z);mask|=1<<(z*2+x);
                }
            return mask;
        }
        boolean mayGenerate() {
            var server=client.getServer();return current()&&voxyAvailable&&activeRadius>=64&&enabled&&WorldGenCompat.enabled()&&generate&&server!=null&&server.getAverageTickTime()<=35
                &&ticket.backgroundAllowed()
                &&!LocalTerrainProvider.foregroundDemandActive()&&!ticket.automaticWater().foregroundBusy()
                &&(ticket.preparation()==null||ticket.preparation().progress().state()!=PreparationProgress.State.RUNNING);
        }
        void requestPreparation(){if(generate)preparation.offer(new Preparation(source.missingX(),source.missingZ()));}
        void prepare() {
            Preparation point=null;
            while(!closed) {
                try {
                    if(point==null)point=preparation.poll(100,TimeUnit.MILLISECONDS);
                    if(point==null&&mayGenerate()) {
                        var next=frontier.next(centerX,centerZ,detailRadius(),ticket.automaticWater()::isPrepared);
                        if(next!=null)point=new Preparation(next.x(),next.z());
                    }
                    if(point==null)continue;
                    TimeUnit.MILLISECONDS.sleep(50);if(!mayGenerate())continue;
                    long dx=(long)point.x()-centerX,dz=(long)point.z()-centerZ;
                    long reach=(long)detailRadius()+ticket.automaticWater().coreSize();
                    if(dx*dx+dz*dz>reach*reach){point=null;continue;}
                    // The controller retains its single admission gate and finite preparation step.
                    // Inference never occupies the ready-data exporter or pins a Voxy engine.
                    boolean ready=ticket.automaticWater().prepareDistantStep(point.x(),point.z(),this::mayGenerate);
                    if(ready){point=null;client.execute(()->{if(current())scan.reconsider();});}
                }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                catch(Exception|LinkageError failure){errors.incrementAndGet();progress="Ошибка подготовки LOD: "+failure.getClass().getSimpleName();
                    if(current())emit(Code.VOX_ASYNC,DiagnosticErrors.current("LOD_PREPARATION"),failure,Outcome.DEGRADED,"Bridge stopped; authoritative terrain unchanged",false);closed=true;}
            }
        }
        void run() {
            Work continuation=null;
            while(!closed) {
                Work work;
                try {
                    if(continuation!=null) {TimeUnit.NANOSECONDS.sleep(ClientPerformance.FRAMES.transferInterval());work=continuation;}
                    else work=queue.poll(100,TimeUnit.MILLISECONDS);
                }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                if(work==null) continue;
                boolean finished=true;
                try {
                    if(current()&&!work.cancelled.get()&&!ClientPerformance.mayPublish()) {finished=false;continuation=work;continue;}
                    finished=publish(work);
                }catch(Exception|LinkageError failure) {
                    errors.incrementAndGet();progress="Ошибка моста: "+failure.getClass().getSimpleName();
                    if(current()) emit(Code.VOX_CONVERT,DiagnosticErrors.context("LOD_PUBLICATION","lod=("+work.cell.x()+","+work.cell.z()+")"),failure,Outcome.DEGRADED,"Optional export stopped; existing world data unchanged",false);closed=true;
                } finally {
                    if(work.cancelled.get()) refreshReal(work);
                    if(finished) {active=null;nextJob=System.nanoTime()+ClientPerformance.FRAMES.transferInterval();busy.set(false);}
                }
                continuation=finished?null:work;
                if(finished&&current()) {
                    try{TimeUnit.NANOSECONDS.sleep(ClientPerformance.FRAMES.transferInterval());}
                    catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                    client.execute(()->{if(current())tick(client);});
                }
            }
        }
        boolean publish(Work work) throws Exception {
            if(!current()||work.cancelled.get()) return true;
            work.writableMask=guard.writableMask(work.cell.x(),work.cell.z());
            if(work.writableMask==0){
                if(!guard.uncertain())client.execute(()->{if(current())scan.completed(work.cell);});
                else progress="Ожидание чтения заголовка региона";
                return true;
            }
            int x=work.cell.x()*32,z=work.cell.z()*32;
            if(work.column==null) {
                long trace=PerformanceDiagnostics.begin(LOD_SAMPLE);
                try{work.column=source.sample(work.cell.x(),work.cell.z(),world.getBottomY(),world.getTopYInclusive(),work.surface());}
                finally{PerformanceDiagnostics.end(trace);}
            }
            if(work.column==null) {
                progress="Ожидание рельефа около "+x+", "+z;
                client.execute(()->{if(current())scan.defer(work.cell);});
                if(!work.cancelled.get())requestPreparation();
                return true;
            }
            var column=work.column;
            if(column.forestPending()) {
                requestPreparation();
                if(work.resume!=null) {
                    client.execute(()->{if(current())scan.surfaceCompleted(work.cell,work.resume);});
                    return true;
                }
            }
            // No engine is pinned during potentially long model work or while waiting for the client thread.
            long trace=PerformanceDiagnostics.begin(LOD_CLIENT_WAIT);Voxy016Api.Bound bound;
            try{bound=client.submit(()->{
                if(!current()||work.cancelled.get()||!ClientPerformance.mayPublish()) return null;
                work.writableMask&=~loadedMask(work.cell)&guard.unseenMask(work.cell.x(),work.cell.z());
                if(work.writableMask==0)return null;
                try{return targetApi.acquire(world);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            }).get();}finally{PerformanceDiagnostics.end(trace);}
            if(bound==null){if(work.writableMask==0)client.execute(()->{if(current())scan.completed(work.cell);});progress="Ожидание активного Voxy или кадрового бюджета";return !current()||work.cancelled.get()||ClientPerformance.mayPublish();}
            boolean complete=true;
            try(bound) {
                if(work.cursor==null)work.cursor=work.surface()?LodPublisher.Cursor.visible(column,bound.highestKnownCube(column))
                    :LodPublisher.Cursor.resume(column,work.resume);
                if(coarse!=null)coarse.exact(work.cell.x(),work.cell.z(),work.writableMask,column);
                var sink=new LodPublisher.Sink(){
                    public boolean active() throws Exception{return bound.active();}
                    // Existing cache presence cannot distinguish a partial 32-cube
                    // from a complete one. Only unsaved/unseen 16-chunk columns are rewritten.
                    public boolean occupied(int x,int y,int z){return false;}
                    public boolean existing(int x,int y,int z) throws Exception{return bound.occupied(x,y,z);}
                    public void write(LodColumn column,int y) throws Exception{bound.cube(column,y,biomes,work.writableMask);}
                };
                // At most one write; cheaply skip up to eight already saved cubes.
                for(int checks=0;checks<8;checks++) {
                    var result=work.cursor.step(sink,()->current()&&!work.cancelled.get()&&ClientPerformance.mayPublish());complete=result.complete();
                    written.addAndGet(result.written());skipped.addAndGet(result.skipped());
                    if(complete||result.written()>0||result.skipped()==0) break;
                }
            }
            var saved=work.cursor.progress();
            if(work.surface()&&complete||!work.surface()&&!complete)
                client.execute(()->{if(current())scan.surfaceCompleted(work.cell,saved);});
            else if(complete)client.execute(()->{if(current())scan.completed(work.cell);});
            progress="LOD около "+x+", "+z;
            return complete||!work.surface();
        }
        void refreshReal(Work work) {
            if(work.refreshed) return;work.refreshed=true;
            client.execute(()->{
                if(client.world!=world) return;
                for(int cz=0;cz<2;cz++) for(int cx=0;cx<2;cx++) {
                    var real=world.getChunkManager().getChunk(work.cell.x()*2+cx,work.cell.z()*2+cz,ChunkStatus.FULL,false);
                    if(real!=null) try{targetApi.reingest(real);}catch(ReflectiveOperationException e){LOG.debug("Voxy real-chunk refresh skipped",e);}
                }
            });
        }
    }
}

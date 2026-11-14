package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldSessionBinding;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.server.command.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import static net.minecraft.server.command.CommandManager.literal;
import static net.minecraft.server.command.CommandManager.argument;

public final class PreparedWaterCommands {
    private PreparedWaterCommands() {}
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("td-water")
            .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
            .then(literal("status").executes(ctx->{
                try {var s=session(ctx.getSource());String status=s.automaticWater()!=null?s.automaticWater().status():s.preparedWater().status();ctx.getSource().sendFeedback(()->Text.literal(status),false);return 1;}
                catch(Exception e){ctx.getSource().sendError(Text.literal(e.getMessage()));return 0;}
            }))
            .then(literal("falls").executes(ctx->{
                try {
                    var s=session(ctx.getSource());var falls=s.riverOverlay().waterfalls();
                    if(falls.isEmpty()) ctx.getSource().sendFeedback(()->Text.literal(s.automaticWater()!=null
                        ?"В ближайших рассчитанных участках водопадов нет. Исследуйте мир: они появятся там, где найдётся подходящий уступ."
                        :"В сохранённых областях водопадов нет. При новой подготовке они появятся, если найдётся подходящий уступ."),false);
                    for(var f:falls) ctx.getSource().sendFeedback(()->Text.literal("Водопад: верх ("+f.x()+", "+f.upperY()+", "+f.z()+"), низ ("
                        +f.worldX(f.run(),0)+", "+f.lowerY()+", "+f.worldZ(f.run(),0)+"), высота "+(f.upperY()-f.lowerY())
                        +", ширина "+f.width()+". Осмотр: /tp "+f.x()+" "+(f.upperY()+5)+" "+f.z()),false);
                    return falls.size();
                } catch(Exception e){ctx.getSource().sendError(Text.literal(e.getMessage()));return 0;}
            }))
            .then(literal("prepare").then(argument("x",IntegerArgumentType.integer(-29_980_000,29_980_000))
                .then(argument("z",IntegerArgumentType.integer(-29_980_000,29_980_000))
                    .then(argument("radius",IntegerArgumentType.integer(64,512)).executes(ctx->{
                        var source=ctx.getSource();
                        try {
                            var water=controller(source);
                            var bounds=PreparedWaterController.Bounds.around(IntegerArgumentType.getInteger(ctx,"x"),
                                IntegerArgumentType.getInteger(ctx,"z"),IntegerArgumentType.getInteger(ctx,"radius"));
                            water.start(bounds,()->requireUnexplored(source.getWorld(),bounds),source.getServer()::execute);
                            source.sendFeedback(()->Text.literal("Подготовка рек и озёр запущена. Область: ("+bounds.x()+", "+bounds.z()+"), "+bounds.side()+"×"+bounds.side()+" блоков. Ход работы: /td-water status. До завершения не приближайтесь к области."),false);
                            return 1;
                        } catch(Exception e){source.sendError(Text.literal(e.getMessage()));return 0;}
                    }))))));
    }
    private static PreparedWaterController controller(ServerCommandSource source) {
        var session=session(source);
        if(session.automaticWater()!=null) throw new IllegalStateException("В этом мире реки, озёра и водопады создаются автоматически при генерации местности. Подготовка не требуется.");
        return session.preparedWater();
    }
    private static WorldSession session(ServerCommandSource source) {
        if(!WorldSessionBinding.ownsSession(source.getWorld().getRegistryKey())
            || !(source.getWorld().getChunkManager().getChunkGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource))
            throw new IllegalStateException("Команда работает только в обычном измерении мира Terrain Diffusion");
        var session=WorldSession.current();
        if(session==null||!session.isActive()||(session.preparedWater()==null&&session.automaticWater()==null)) throw new IllegalStateException("Нет активной сессии воды");
        return session;
    }
    static void requireUnexplored(ServerWorld world,PreparedWaterController.Bounds bounds) {
        var regions=world.getServer().getSavePath(WorldSavePath.ROOT).resolve("region");
        var manager=world.getChunkManager().chunkLoadingManager;
        for(int z=bounds.z();z<bounds.z()+bounds.side();z+=512) for(int x=bounds.x();x<bounds.x()+bounds.side();x+=512) {
            var result=GeneratedChunkGuard.check(regions,x,z,(cx,cz)->manager.getCurrentChunkHolder(ChunkPos.toLong(cx,cz))==null
                ?GeneratedChunkGuard.RuntimeState.ABSENT:GeneratedChunkGuard.RuntimeState.PRESENT_OR_IN_FLIGHT);
            if(!result.safeToPublish()) throw new IllegalStateException("Область уже загружалась или генерируется около ("+x+", "+z+"). Выберите неизведанную область дальше от игроков. Причина: "+result.reason());
        }
    }
}

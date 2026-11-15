package com.github.xandergos.terraindiffusionmc.compat.voxy;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.world.World;

public final class ChunkyCommands {
    public static void install() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(literal("td-voxy")
                .then(literal("chunky")
                        .then(argument("radiusChunks", IntegerArgumentType.integer(1, 2048))
                                .executes(c -> {
                                    var client = MinecraftClient.getInstance();
                                    var server = client.getServer();
                                    if (server == null
                                            || client.player == null
                                            || client.world == null
                                            || !client.world.getRegistryKey().equals(World.OVERWORLD)
                                            || !(server.getOverworld()
                                                            .getChunkManager()
                                                            .getChunkGenerator()
                                                            .getBiomeSource()
                                                    instanceof TerrainDiffusionBiomeSource)) {
                                        c.getSource()
                                                .sendError(
                                                        Text.literal(
                                                                "Нужен встроенный мир European Wilderness, измерение overworld"));
                                        return 0;
                                    }
                                    int x = client.player.getBlockX(),
                                            z = client.player.getBlockZ(),
                                            radius = IntegerArgumentType.getInteger(c, "radiusChunks");
                                    server.execute(() -> {
                                        String result;
                                        try {
                                            result = ChunkyApiAdapter.installed()
                                                            .startChunks("minecraft:overworld", x, z, radius)
                                                    ? "Chunky: реальные чанки, порядок region, центр "
                                                            + x
                                                            + ", "
                                                            + z
                                                            + ", радиус "
                                                            + radius
                                                            + " чанков ("
                                                            + (radius * 16)
                                                            + " блоков), примерно "
                                                            + Math.round(Math.PI * radius * radius)
                                                            + " чанков всего. /chunky progress; /chunky pause"
                                                    : "Chunky: задача уже существует или выборка отклонена. /chunky progress";
                                        } catch (Exception failure) {
                                            result = "Chunky API недоступен: "
                                                    + failure.getClass().getSimpleName();
                                        }
                                        String message = result;
                                        client.execute(() -> c.getSource().sendFeedback(Text.literal(message)));
                                    });
                                    return 1;
                                })))));
    }

    private ChunkyCommands() {}
}

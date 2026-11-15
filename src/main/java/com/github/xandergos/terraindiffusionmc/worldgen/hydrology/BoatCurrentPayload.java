package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record BoatCurrentPayload(int entityId, short flow) implements CustomPayload {
    public static final Id<BoatCurrentPayload> ID = new Id<>(Identifier.of("terrain-diffusion-mc", "boat_current"));
    public static final PacketCodec<RegistryByteBuf, BoatCurrentPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT,
                    BoatCurrentPayload::entityId,
                    PacketCodecs.SHORT,
                    BoatCurrentPayload::flow,
                    BoatCurrentPayload::new);

    @Override
    public Id<BoatCurrentPayload> getId() {
        return ID;
    }

    public interface Receiver {
        void terrainDiffusion$receiveBoatCurrent(short flow);
    }
}

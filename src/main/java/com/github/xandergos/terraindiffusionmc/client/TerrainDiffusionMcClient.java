package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.BoatCurrentPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

@Environment(EnvType.CLIENT)
public class TerrainDiffusionMcClient implements ClientModInitializer {
   public void onInitializeClient() {
      ClientPlayNetworking.registerGlobalReceiver(BoatCurrentPayload.ID,(payload,context)->{
         var boat=context.player().getVehicle();
         if(boat instanceof BoatCurrentPayload.Receiver receiver&&boat.getId()==payload.entityId())
            receiver.terrainDiffusion$receiveBoatCurrent(payload.flow());
      });
      ClientPerformance.install();
      com.github.xandergos.terraindiffusionmc.compat.voxy.VoxyCompat.install();
   }
}

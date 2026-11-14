package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.BoatCurrentPayload;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.RiverCurrent;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoatEntity.class)
abstract class BoatCurrentMixin implements BoatCurrentPayload.Receiver {
    @Unique private short terrainDiffusion$flow;
    @Unique private short terrainDiffusion$sentFlow;
    @Unique private int terrainDiffusion$lastReceived;

    @Inject(method="tick",at=@At(value="INVOKE",
        target="Lnet/minecraft/entity/vehicle/AbstractBoatEntity;isLogicalSideForUpdatingMovement()Z"))
    private void terrainDiffusion$sampleFlow(CallbackInfo ci) {
        AbstractBoatEntity boat=(AbstractBoatEntity)(Object)this;
        if(boat.getEntityWorld().isClient()) return;
        terrainDiffusion$flow=RiverCurrent.atEntity(boat);
        // A player steers and simulates their own boat on the client.
        if(boat.age%5==0&&boat.getControllingPassenger() instanceof ServerPlayerEntity player
                &&(terrainDiffusion$flow!=0||terrainDiffusion$sentFlow!=0)
                &&ServerPlayNetworking.canSend(player,BoatCurrentPayload.ID)) {
            ServerPlayNetworking.send(player,new BoatCurrentPayload(boat.getId(),terrainDiffusion$flow));
            terrainDiffusion$sentFlow=terrainDiffusion$flow;
        }
    }

    @Override public void terrainDiffusion$receiveBoatCurrent(short flow) {
        terrainDiffusion$flow=flow;
        terrainDiffusion$lastReceived=((AbstractBoatEntity)(Object)this).age;
    }

    @ModifyArg(method="tick",at=@At(value="INVOKE",
        target="Lnet/minecraft/entity/vehicle/AbstractBoatEntity;move(Lnet/minecraft/entity/MovementType;Lnet/minecraft/util/math/Vec3d;)V"),index=1)
    private Vec3d terrainDiffusion$drift(Vec3d velocity) {
        AbstractBoatEntity boat=(AbstractBoatEntity)(Object)this;
        if(terrainDiffusion$flow==0||boat.isOnGround()
                ||boat.getEntityWorld().isClient()&&boat.age-terrainDiffusion$lastReceived>12) return velocity;
        Vec3d drift=RiverCurrent.accelerate(velocity,terrainDiffusion$flow,true);
        boat.setVelocity(drift);
        return drift;
    }
}

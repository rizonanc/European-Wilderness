package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.RiverCurrent;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
abstract class ItemCurrentMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void terrainDiffusion$drift(CallbackInfo ci) {
        ItemEntity item = (ItemEntity) (Object) this;
        if (!item.isTouchingWater()) return;
        short flow = RiverCurrent.atEntity(item);
        if (flow != 0) item.setVelocity(RiverCurrent.accelerate(item.getVelocity(), flow, false));
    }
}

package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.roadweaver.RoadWeaverTerrain;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.shiroha233.roadweaver.pathfinding.cache.TerrainSamplingCache", remap = false)
abstract class RoadWeaverSamplingMixin {
    @Shadow @Final private ConcurrentHashMap<Long, Integer> heightCache;
    @Shadow @Final private ConcurrentHashMap<Long, Integer> oceanFloorCache;
    @Shadow @Final private ConcurrentHashMap<Long, Boolean> waterCache;

    @Shadow(remap = false)
    public abstract int oceanFloor(ServerWorld world, int x, int z);

    @Inject(method = "height", at = @At("HEAD"), cancellable = true)
    private void td$height(ServerWorld world, int x, int z, CallbackInfoReturnable<Integer> result) {
        var session = RoadWeaverTerrain.session(world);
        if (session == null) return;
        long key = td$key(x, z);
        Integer height = heightCache.get(key);
        if (height == null) {
            height =
                    Math.max(
                            oceanFloor(world, x, z),
                            RoadWeaverTerrain.waterFirstAir(session, x, z, world.getSeaLevel()));
            heightCache.put(key, height);
        }
        result.setReturnValue(height);
    }

    @Inject(method = "oceanFloor", at = @At("HEAD"), cancellable = true)
    private void td$floor(ServerWorld world, int x, int z, CallbackInfoReturnable<Integer> result) {
        var session = RoadWeaverTerrain.session(world);
        if (session == null) return;
        long key = td$key(x, z);
        Integer floor = oceanFloorCache.get(key);
        if (floor == null) {
            floor = RoadWeaverTerrain.firstAir(session, x, z);
            oceanFloorCache.put(key, floor);
        }
        result.setReturnValue(floor);
    }

    @Inject(
            method = {"isWaterLike", "isColumnWater"},
            at = @At("HEAD"),
            cancellable = true,
            require = 2)
    private void td$water(ServerWorld world, int x, int z, CallbackInfoReturnable<Boolean> result) {
        var session = RoadWeaverTerrain.session(world);
        if (session == null) return;
        long key = td$key(x, z);
        Boolean wet = waterCache.get(key);
        if (wet == null) {
            wet = oceanFloor(world, x, z) < RoadWeaverTerrain.waterFirstAir(session, x, z, world.getSeaLevel());
            waterCache.put(key, wet);
        }
        result.setReturnValue(wet);
    }

    @Inject(method = "prewarmRegion", at = @At("HEAD"), cancellable = true)
    private void td$skipUnusedHeightScan(
            ServerWorld world, int minX, int minZ, int maxX, int maxZ, int step, CallbackInfo callback) {
        if (RoadWeaverTerrain.session(world) != null) callback.cancel();
    }

    @Unique
    private static long td$key(int x, int z) {
        return (long) x << 32 | (z & 0xffffffffL);
    }
}

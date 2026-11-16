package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.WaterMipMapping;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.other.Mapper", remap = false)
public abstract class VoxyWaterMapperMixin implements WaterMipMapping {
    @Shadow
    @Final
    private ObjectArrayList<?> blockId2stateEntry;

    @Unique
    private static final VarHandle ewh$cacheElement = MethodHandles.arrayElementVarHandle(byte[].class);

    @Unique
    private final Object ewh$waterLock = new Object();

    @Unique
    private volatile byte[] ewh$waterIds = new byte[256];

    @Unique
    private volatile int ewh$sourceWaterId;

    @Override
    public boolean ewh$isWater(int id) {
        return ewh$waterKind(id) == 2;
    }

    @Override
    public long ewh$surfaceWaterValue(long value) {
        int kind = ewh$waterKind((int) ((value >>> 27) & 0xFFFFF));
        if (kind == 2) return value;
        if (kind != 3) return 0;
        int source = ewh$sourceWaterId;
        if (source == 0)
            synchronized (ewh$waterLock) {
                source = ewh$sourceWaterId;
                if (source == 0)
                    for (int i = 1, n = blockId2stateEntry.size(); i < n; i++) {
                        if (ewh$state(i).equals(Blocks.WATER.getDefaultState())) {
                            ewh$sourceWaterId = source = i;
                            break;
                        }
                    }
            }
        return source == 0 ? 0 : (value & ~(0xFFFFFL << 27)) | ((long) source << 27);
    }

    @Unique
    private byte ewh$waterKind(int id) {
        if (id <= 0) return 1;
        byte[] values = ewh$waterIds;
        byte cached = id < values.length ? (byte) ewh$cacheElement.getAcquire(values, id) : 0;
        if (cached != 0) return cached;
        synchronized (ewh$waterLock) {
            values = ewh$waterIds;
            if (id >= values.length) {
                values = Arrays.copyOf(values, Math.max(id + 1, values.length * 2));
                ewh$waterIds = values;
            }
            cached = (byte) ewh$cacheElement.getAcquire(values, id);
            if (cached != 0) return cached;
            BlockState block = ewh$state(id);
            if (block.isOf(Blocks.WATER)) {
                cached = 2;
                if (block.equals(Blocks.WATER.getDefaultState())) ewh$sourceWaterId = id;
            } else {
                boolean natural = block.isIn(BlockTags.CORALS)
                        || block.isIn(BlockTags.WALL_CORALS)
                        || block.isOf(Blocks.SEAGRASS)
                        || block.isOf(Blocks.TALL_SEAGRASS)
                        || block.isOf(Blocks.KELP)
                        || block.isOf(Blocks.KELP_PLANT)
                        || block.isOf(Blocks.SEA_PICKLE);
                cached = (byte) (natural && block.getFluidState().isIn(FluidTags.WATER) ? 3 : 1);
            }
            ewh$cacheElement.setRelease(values, id, cached);
            return cached;
        }
    }

    @Unique
    private BlockState ewh$state(int id) {
        try {
            Object entry = blockId2stateEntry.get(id);
            // The published JAR uses intermediary MC descriptors; reflect once per new id.
            return (BlockState) entry.getClass().getField("state").get(entry);
        } catch (ReflectiveOperationException | IndexOutOfBoundsException failure) {
            throw new IllegalStateException("Pinned Voxy water mapping ABI changed", failure);
        }
    }
}

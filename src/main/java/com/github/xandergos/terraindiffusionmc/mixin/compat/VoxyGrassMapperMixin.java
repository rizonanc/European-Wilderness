package com.github.xandergos.terraindiffusionmc.mixin.compat;

import com.github.xandergos.terraindiffusionmc.compat.voxy.GrassMipMapping;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets="me.cortex.voxy.common.world.other.Mapper",remap=false)
public abstract class VoxyGrassMapperMixin implements GrassMipMapping {
    @Shadow @Final private ObjectArrayList<?> blockId2stateEntry;
    @Unique private static final VarHandle ewh$grassElement=MethodHandles.arrayElementVarHandle(byte[].class);
    @Unique private final Object ewh$grassLock=new Object();
    @Unique private volatile byte[] ewh$grassIds=new byte[256];
    @Override public int ewh$grassKind(int id){
        if(id==0)return AIR;if(id<0)return OTHER;
        byte[] a=ewh$grassIds;byte v=id<a.length?(byte)ewh$grassElement.getAcquire(a,id):0;
        if(v!=0)return v;
        synchronized(ewh$grassLock){
            a=ewh$grassIds;if(id>=a.length){a=Arrays.copyOf(a,Math.max(id+1,a.length*2));ewh$grassIds=a;}
            v=(byte)ewh$grassElement.getAcquire(a,id);if(v!=0)return v;
            try{
                BlockState s=(BlockState)blockId2stateEntry.get(id).getClass().getField("state").get(blockId2stateEntry.get(id));
                v=(byte)(s.isOf(Blocks.GRASS_BLOCK)?GROUND:s.isOf(Blocks.SHORT_GRASS)||s.isOf(Blocks.FERN)?SHORT:
                    s.isOf(Blocks.TALL_GRASS)||s.isOf(Blocks.LARGE_FERN)?TALL:OTHER);
            }catch(ReflectiveOperationException|IndexOutOfBoundsException e){throw new IllegalStateException("Pinned Voxy grass palette ABI changed",e);}
            ewh$grassElement.setRelease(a,id,v);return v;
        }
    }
}

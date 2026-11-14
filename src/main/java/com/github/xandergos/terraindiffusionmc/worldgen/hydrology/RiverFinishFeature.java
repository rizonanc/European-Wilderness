package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import net.minecraft.block.Blocks;
import net.minecraft.block.PlantBlock;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

/** Touch only loose soil in planned wet columns; preserve trees, slabs and built structures. */
public final class RiverFinishFeature extends Feature<DefaultFeatureConfig> {
    public RiverFinishFeature(){super(DefaultFeatureConfig.CODEC);}
    @Override public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
        if(!(context.getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource))return false;
        var session=WorldSession.current();WorldSession.requireActive(session);
        var overlay=session.riverOverlay();if(overlay==null)return false;
        var world=context.getWorld();boolean[] changed={false};
        RiverFinishPlanning.finish(overlay,Math.floorDiv(context.getOrigin().getX(),16)*16,
            Math.floorDiv(context.getOrigin().getZ(),16)*16,world.getBottomY(),world.getTopYInclusive(),new RiverFinishPlanning.Access(){
                public boolean soil(int x,int y,int z){var state=world.getBlockState(new BlockPos(x,y,z));
                    return state.isOf(Blocks.DIRT)||state.isOf(Blocks.COARSE_DIRT)||state.isOf(Blocks.GRASS_BLOCK)
                        ||state.isOf(Blocks.DIRT_PATH)||state.isOf(Blocks.PODZOL)||state.isOf(Blocks.MYCELIUM)
                        ||state.isOf(Blocks.ROOTED_DIRT)||state.isOf(Blocks.MUD);}
                public void sand(int x,int y,int z){changed[0]|=world.setBlockState(new BlockPos(x,y,z),Blocks.SAND.getDefaultState(),2);}
                public void water(int x,int y,int z,boolean surface){var pos=new BlockPos(x,y,z);
                    changed[0]|=world.setBlockState(pos,Blocks.WATER.getDefaultState(),2);
                    if(surface)world.scheduleFluidTick(pos,Fluids.WATER,1);}
                public void removeUnsupportedPlant(int x,int y,int z){var pos=new BlockPos(x,y,z);
                    var state=world.getBlockState(pos);
                    if(state.getBlock() instanceof PlantBlock&&state.getFluidState().isEmpty()&&!state.canPlaceAt(world,pos))
                        changed[0]|=world.setBlockState(pos,Blocks.AIR.getDefaultState(),2);}
            });
        return changed[0];
    }
}

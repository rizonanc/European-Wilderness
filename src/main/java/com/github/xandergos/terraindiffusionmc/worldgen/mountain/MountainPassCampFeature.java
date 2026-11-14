package com.github.xandergos.terraindiffusionmc.worldgen.mountain;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.PerformanceProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.PreparedFallback;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainRepository;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

public final class MountainPassCampFeature extends Feature<DefaultFeatureConfig> {
   public MountainPassCampFeature() {
      super(DefaultFeatureConfig.CODEC);
   }

   @Override
   public boolean generate(FeatureContext<DefaultFeatureConfig> context) {
      if (!(context.getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
         return false;
      }

      StructureWorldAccess world = context.getWorld();
      WorldSession session = WorldSession.current();
      WorldSession.requireActive(session);
      TerrainRepository repository = session.repository();
      PassCampDecisionStore decisions = session.passCampDecisions();
      if (repository != null && decisions != null) {
         int chunkX = Math.floorDiv(context.getOrigin().getX(), 16);
         int chunkZ = Math.floorDiv(context.getOrigin().getZ(), 16);
         int scale = session.profile().worldScale();
         long seed = world.getSeed();
         TerrainTileKey key = TerrainTileKey.forBlock(
            Math.multiplyExact(chunkX, 16), Math.multiplyExact(chunkZ, 16), repository.tileSize(), seed, session.fingerprint()
         );
         PerformanceProfile.PreparationMode mode = PreparedFallback.mode();

         try {
            PassCampDecisionStore.Decision decision = decisions.decide(key, scale, 8, requested -> repository.peek(requested).orElseGet(() -> {
               if (mode == PerformanceProfile.PreparationMode.ON_DEMAND) {
                  throw new IllegalStateException("ON_DEMAND terrain tile was not ready before decoration: " + requested);
               } else {
                  return null;
               }
            }));
            if (decision == PassCampDecisionStore.Decision.DISABLED) {
               return false;
            }

            TerrainTile tile = repository.peek(key).orElseThrow(() -> new IllegalStateException("Active pass/camp tile disappeared: " + key));
            WorldSession.requireActive(session);
            PassCampPlanCache.Plan plan = session.passCampPlans()
               .getOrCompute(
                  key,
                  () -> {
                     Optional<MountainPassPlanner.PassPlan> pass = MountainPassPlanner.plan(
                        seed, key.tileX(), key.tileZ(), key.tileSize(), scale, world.getTopYInclusive(), tile
                     );
                     Optional<AbandonedCampPlanner.CampPlan> campx = pass.flatMap(
                        p -> AbandonedCampPlanner.plan(seed, tile, scale, world.getTopYInclusive(), p)
                     );
                     return new PassCampPlanCache.Plan(pass, campx);
                  }
               );
            if (plan.pass().isEmpty()) {
               return false;
            }

            List<MountainPassCampPlanning.RouteColumn> route = MountainPassCampPlanning.routeForChunk(
               seed, tile, scale, world.getTopYInclusive(), plan.pass().orElseThrow(), chunkX, chunkZ
            );
            List<AbandonedCampPlanner.Voxel> camp = plan.camp()
               .filter(c -> !waterChangesCamp(c,session,tile,scale,world.getTopYInclusive()))
               .map(c -> c.voxelsForChunk(chunkX, chunkZ)).orElseGet(List::of);
            if(session.riverOverlay()!=null) route=route.stream().filter(p ->
               session.riverOverlay().waterY(p.x(),p.z())==Integer.MIN_VALUE
               && session.riverOverlay().carvedHeight(p.x(),p.z(),p.naturalGroundY()+1)==p.naturalGroundY()+1).toList();
            WorldSession.requireActive(session);
            return writeRoute(world, route) | writeCamp(world, camp);
         } catch (Exception failed) {
            throw new IllegalStateException("Mountain pass/camp generation failed for " + key, failed);
         }
      } else {
         throw new IllegalStateException("Pass/camp world session is not fully bound");
      }
   }

   private static boolean waterChangesCamp(AbandonedCampPlanner.CampPlan camp,WorldSession session,TerrainTile tile,int scale,int top) {
      var water=session.riverOverlay();
      if(water==null) return false;
      java.util.Set<Long> columns=new java.util.HashSet<>();
      for(var voxel:camp.voxels()) {
         long key=(long)voxel.x()<<32 ^ voxel.z()&0xffffffffL;
         if(!columns.add(key)) continue;
         if(water.waterY(voxel.x(),voxel.z())!=Integer.MIN_VALUE) return true;
         int raw=MountainHeightField.sampleForWorld(session.worldSeed(),voxel.x(),voxel.z(),
            tile.heightAt(tile.key().localX(voxel.x()),tile.key().localZ(voxel.z())),scale,WorldHeightProfile.surfaceCapY(top)).surfaceY();
         if(water.carvedHeight(voxel.x(),voxel.z(),raw)!=raw) return true;
      }
      return false;
   }

   private static boolean writeRoute(StructureWorldAccess world, List<MountainPassCampPlanning.RouteColumn> route) {
      boolean changed = false;

      for (MountainPassCampPlanning.RouteColumn placement : route) {
         if (placement.naturalGroundY() >= world.getBottomY() && placement.groundY() < world.getTopYInclusive()) {
            BlockPos naturalPos = new BlockPos(placement.x(), placement.naturalGroundY(), placement.z());
            BlockState natural = world.getBlockState(naturalPos);
            if (natural.getFluidState().isEmpty() && naturalGround(natural)) {
               boolean clear = true;

               for (int y = placement.naturalGroundY() + 1; y <= placement.groundY() + 1; y++) {
                  BlockState existing = world.getBlockState(new BlockPos(placement.x(), y, placement.z()));
                  if (!existing.getFluidState().isEmpty() || !existing.isAir() && !existing.isOf(Blocks.SNOW)) {
                     clear = false;
                     break;
                  }
               }

               if (clear) {
                  BlockState next = natural.isOf(Blocks.SNOW_BLOCK)
                     ? Blocks.SNOW_BLOCK.getDefaultState()
                     : (
                        natural.isOf(Blocks.GRAVEL)
                           ? Blocks.GRAVEL.getDefaultState()
                           : (
                              !natural.isOf(Blocks.STONE)
                                    && !natural.isOf(Blocks.ANDESITE)
                                    && !natural.isOf(Blocks.DIORITE)
                                    && !natural.isOf(Blocks.GRANITE)
                                    && !natural.isOf(Blocks.CALCITE)
                                    && !natural.isOf(Blocks.TUFF)
                                 ? Blocks.STONE.getDefaultState()
                                 : natural
                           )
                     );

                  for (int y = placement.naturalGroundY() + 1; y < placement.groundY(); y++) {
                     changed |= world.setBlockState(new BlockPos(placement.x(), y, placement.z()), Blocks.STONE.getDefaultState(), 2);
                  }

                  BlockPos groundPos = new BlockPos(placement.x(), placement.groundY(), placement.z());
                  if (!world.getBlockState(groundPos).equals(next)) {
                     changed |= world.setBlockState(groundPos, next, 2);
                  }

                  BlockPos abovePos = groundPos.up();
                  BlockState above = world.getBlockState(abovePos);
                  if (above.isOf(Blocks.SNOW)) {
                     changed |= world.setBlockState(abovePos, Blocks.AIR.getDefaultState(), 2);
                  }
               }
            }
         }
      }

      return changed;
   }

   private static boolean writeCamp(StructureWorldAccess world, List<AbandonedCampPlanner.Voxel> voxels) {
      boolean changed = false;

      for (AbandonedCampPlanner.Voxel voxel : voxels) {
         if (voxel.y() >= world.getBottomY() && voxel.y() <= world.getTopYInclusive()) {
            BlockPos pos = new BlockPos(voxel.x(), voxel.y(), voxel.z());
            BlockState old = world.getBlockState(pos);
            BlockState next = campState(voxel.material());
            if (old.equals(next)) {
               continue;
            }

            if (old.getFluidState().isEmpty() && !old.hasBlockEntity()) {
               if (!old.isAir() && !old.isOf(Blocks.SNOW) && !old.isIn(BlockTags.REPLACEABLE_BY_TREES) && !old.isIn(BlockTags.LEAVES) && !naturalGround(old)) {
                  return false;
               }
               continue;
            }

            return false;
         }

         return false;
      }

      for (AbandonedCampPlanner.Voxel voxel : voxels) {
         BlockPos pos = new BlockPos(voxel.x(), voxel.y(), voxel.z());
         BlockState old = world.getBlockState(pos);
         BlockState next = campState(voxel.material());
         if (!old.equals(next)) {
            if (!world.setBlockState(pos, next, 2)) {
               throw new IllegalStateException("Accepted camp block write failed at " + pos);
            }

            changed = true;
         }
      }

      return changed;
   }

   private static BlockState campState(AbandonedCampPlanner.Material material) {
      return switch (material) {
         case SPRUCE_LOG -> Blocks.SPRUCE_LOG.getDefaultState();
         case SPRUCE_FENCE -> Blocks.SPRUCE_FENCE.getDefaultState();
         case SPRUCE_PLANKS -> Blocks.SPRUCE_PLANKS.getDefaultState();
         case GRAY_WOOL -> Blocks.GRAY_WOOL.getDefaultState();
         case BROWN_WOOL -> Blocks.BROWN_WOOL.getDefaultState();
         case WHITE_WOOL -> Blocks.WHITE_WOOL.getDefaultState();
         case SNOW -> Blocks.SNOW.getDefaultState();
         case BARREL -> Blocks.BARREL.getDefaultState();
         case CAMPFIRE_UNLIT -> (BlockState)Blocks.CAMPFIRE.getDefaultState().with(CampfireBlock.LIT, false);
      };
   }

   private static boolean naturalGround(BlockState state) {
      return state.isOf(Blocks.GRASS_BLOCK)
         || state.isOf(Blocks.DIRT)
         || state.isOf(Blocks.COARSE_DIRT)
         || state.isOf(Blocks.PODZOL)
         || state.isOf(Blocks.STONE)
         || state.isOf(Blocks.ANDESITE)
         || state.isOf(Blocks.DIORITE)
         || state.isOf(Blocks.GRANITE)
         || state.isOf(Blocks.CALCITE)
         || state.isOf(Blocks.TUFF)
         || state.isOf(Blocks.GRAVEL)
         || state.isOf(Blocks.SNOW_BLOCK);
   }
}

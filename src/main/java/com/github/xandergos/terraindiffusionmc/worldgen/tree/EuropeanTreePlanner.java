package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.BiPredicate;

final class EuropeanTreePlanner {
   private static final Comparator<EuropeanTreePlanner.PlannedVoxel> VOXEL_ORDER = Comparator.comparingInt(EuropeanTreePlanner.PlannedVoxel::y)
      .thenComparingInt(EuropeanTreePlanner.PlannedVoxel::x)
      .thenComparingInt(EuropeanTreePlanner.PlannedVoxel::z);

   private EuropeanTreePlanner() {
   }

   static EuropeanTreePlanner.ChunkPlan planChunk(
      List<EuropeanTreePlanner.PreparedTree> trees, int chunkX, int chunkZ, Predicate<EuropeanTreePlanner.PlannedVoxel> replaceable
   ) {
      Objects.requireNonNull(replaceable,"replaceable");
      return planChunk(trees,chunkX,chunkZ,(tree,voxel)->replaceable.test(voxel));
   }
   static EuropeanTreePlanner.ChunkPlan planChunk(
      List<EuropeanTreePlanner.PreparedTree> trees,int chunkX,int chunkZ,
      BiPredicate<EuropeanTreePlanner.PreparedTree,EuropeanTreePlanner.PlannedVoxel> replaceable
   ) {
      return plan(trees,chunkX,chunkZ,false,replaceable);
   }

   /** The root chunk writes the entire tree, including its eight-block canopy halo. */
   static ChunkPlan planRootChunk(List<PreparedTree> trees,int chunkX,int chunkZ,
                                  BiPredicate<PreparedTree,PlannedVoxel> replaceable) {
      return plan(trees,chunkX,chunkZ,true,replaceable);
   }

   private static ChunkPlan plan(List<PreparedTree> trees,int chunkX,int chunkZ,boolean wholeTree,
                                BiPredicate<PreparedTree,PlannedVoxel> replaceable) {
      Objects.requireNonNull(trees, "trees");
      Objects.requireNonNull(replaceable, "replaceable");
      List<List<EuropeanTreePlanner.PlannedVoxel>> acceptedParts = new ArrayList<>();

      for (EuropeanTreePlanner.PreparedTree tree : trees) {
         Objects.requireNonNull(tree, "tree");
         if(wholeTree && (Math.floorDiv(tree.candidate().x(),16)!=chunkX || Math.floorDiv(tree.candidate().z(),16)!=chunkZ)) continue;
         List<EuropeanTreePlanner.PlannedVoxel> part = wholeTree ? whole(tree) : part(tree, chunkX, chunkZ);
         if (!part.isEmpty()) {
            boolean clear = true;

            // A collision in the trunk's chunk must also reject its canopy in
            // the neighbour. Validate the whole tree before taking a chunk part.
            for (var voxel : tree.shape().voxels()) {
               var whole=new PlannedVoxel(Math.addExact(tree.candidate().x(),voxel.x()),Math.addExact(tree.baseY(),voxel.y()),
                  Math.addExact(tree.candidate().z(),voxel.z()),voxel.log(),voxel.leafDistance(),tree.species(),
                  tree.candidate().priority(),tree.candidate().identity());
               if (!replaceable.test(tree,whole)) {
                  clear = false;
                  break;
               }
            }

            if (clear) {
               acceptedParts.add(part);
            }
         }
      }

      Map<EuropeanTreePlanner.Position, EuropeanTreePlanner.PlannedVoxel> merged = new HashMap<>();

      for (List<EuropeanTreePlanner.PlannedVoxel> part : acceptedParts) {
         for (EuropeanTreePlanner.PlannedVoxel voxel : part) {
            merged.merge(new EuropeanTreePlanner.Position(voxel.x(), voxel.y(), voxel.z()), voxel, EuropeanTreePlanner::winner);
         }
      }

      ArrayList<EuropeanTreePlanner.PlannedVoxel> voxels = new ArrayList<>(merged.values());
      voxels.sort(VOXEL_ORDER);
      HashSet<Long> represented = new HashSet<>();

      for (EuropeanTreePlanner.PlannedVoxel voxel : voxels) {
         represented.add(voxel.candidateIdentity());
      }

      return new EuropeanTreePlanner.ChunkPlan(voxels, represented);
   }

   private static List<PlannedVoxel> whole(PreparedTree tree) {
      List<PlannedVoxel> result=new ArrayList<>(tree.shape().voxels().size());
      for(var v:tree.shape().voxels()) result.add(new PlannedVoxel(
         Math.addExact(tree.candidate().x(),v.x()),Math.addExact(tree.baseY(),v.y()),Math.addExact(tree.candidate().z(),v.z()),
         v.log(),v.leafDistance(),tree.species(),tree.candidate().priority(),tree.candidate().identity()));
      return result;
   }

   static int place(EuropeanTreePlanner.ChunkPlan plan, Predicate<EuropeanTreePlanner.PlannedVoxel> writer) {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(writer, "writer");
      int placed = 0;

      for (EuropeanTreePlanner.PlannedVoxel voxel : plan.voxels()) {
         if (writer.test(voxel)) {
            placed++;
         }
      }

      return placed;
   }

   private static List<EuropeanTreePlanner.PlannedVoxel> part(EuropeanTreePlanner.PreparedTree tree, int chunkX, int chunkZ) {
      TreeCandidates.Candidate candidate = tree.candidate();
      List<TreePlacement.PlacedVoxel> placed = TreePlacement.parts(tree.shape(), candidate.x(), tree.baseY(), candidate.z(), chunkX, chunkZ);
      ArrayList<EuropeanTreePlanner.PlannedVoxel> result = new ArrayList<>(placed.size());

      for (TreePlacement.PlacedVoxel voxel : placed) {
         if (Math.floorDiv(voxel.x(), 16) != chunkX || Math.floorDiv(voxel.z(), 16) != chunkZ) {
            throw new IllegalArgumentException("tree part escaped target chunk");
         }

         result.add(
            new EuropeanTreePlanner.PlannedVoxel(
               voxel.x(), voxel.y(), voxel.z(), voxel.log(), voxel.leafDistance(), tree.species(), candidate.priority(), candidate.identity()
            )
         );
      }

      return List.copyOf(result);
   }

   private static EuropeanTreePlanner.PlannedVoxel winner(EuropeanTreePlanner.PlannedVoxel first, EuropeanTreePlanner.PlannedVoxel second) {
      if (first.log() != second.log()) {
         return first.log() ? first : second;
      } else {
         int priority = Long.compareUnsigned(first.candidatePriority(), second.candidatePriority());
         if (priority != 0) {
            return priority > 0 ? first : second;
         } else {
            int identity = Long.compareUnsigned(first.candidateIdentity(), second.candidateIdentity());
            if (identity != 0) {
               return identity > 0 ? first : second;
            } else {
               int species = Integer.compare(first.species().ordinal(), second.species().ordinal());
               if (species != 0) {
                  return species > 0 ? first : second;
               } else {
                  return first.leafDistance() >= second.leafDistance() ? first : second;
               }
            }
         }
      }
   }

   record ChunkPlan(List<EuropeanTreePlanner.PlannedVoxel> voxels, Set<Long> representedTrees) {
      ChunkPlan {
         voxels = List.copyOf(voxels);
         representedTrees = Set.copyOf(representedTrees);
      }
   }

   record PlannedVoxel(int x, int y, int z, boolean log, int leafDistance, TreeSpecies species, long candidatePriority, long candidateIdentity) {
      PlannedVoxel {
         Objects.requireNonNull(species, "species");
      }
   }

   private record Position(int x, int y, int z) {
   }

   record PreparedTree(TreeCandidates.Candidate candidate, TreeSpecies species, TreeShapeGenerator.Shape shape, int baseY) {
      PreparedTree {
         Objects.requireNonNull(candidate, "candidate");
         Objects.requireNonNull(species, "species");
         Objects.requireNonNull(shape, "shape");
      }
   }
}

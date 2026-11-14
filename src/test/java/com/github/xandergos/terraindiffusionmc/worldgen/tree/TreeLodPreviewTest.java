package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeLodPreviewTest {
    private record Voxel(int x,int y,int z,TreeSpecies species,boolean log){}
    private static TreeLodPreview.Field flat(boolean wet) {return new TreeLodPreview.Field(){
        public TreeLodPreview.Climate climate(int x,int z){return new TreeLodPreview.Climate((short)215,(short)300,12,900);}
        public int firstAir(int x,int z){return 101;}
        public int waterY(int x,int z){return wet?105:Integer.MIN_VALUE;}
    };}
    private Set<Voxel> paint(int x,int z,int side,TreeLodPreview.Field field) throws Exception {
        Set<Voxel> out=new HashSet<>();TreeLodPreview.paint(12345,x,z,side,-64,319,field,(wx,y,wz,species,log)->out.add(new Voxel(wx,y,wz,species,log)));return out;
    }
    @Test void forestHasTrunksAndCrownsAndWetRootsCannotSpawnFloatingTrees() throws Exception {
        var forest=paint(0,0,32,flat(false));assertTrue(forest.size()>100);assertTrue(forest.stream().anyMatch(Voxel::log));assertTrue(forest.stream().anyMatch(v->!v.log));
        assertTrue(forest.stream().allMatch(v->v.x>=0&&v.x<32&&v.z>=0&&v.z<32&&v.y>=101));assertTrue(paint(0,0,32,flat(true)).isEmpty());
    }
    @Test void croppedCanopiesMatchAcrossNegativeChunkAndCellBoundaries() throws Exception {
        var field=flat(false);Set<Voxel> large=paint(-32,-32,64,field),parts=new HashSet<>();
        for(int z=-32;z<32;z+=32)for(int x=-32;x<32;x+=32)parts.addAll(paint(x,z,32,field));
        assertEquals(large,parts);assertEquals(parts,paint(-32,-32,64,field));
    }
    @Test void cheapDimensionsReuseExactSeededHeightAndBaseSelection() {
        for(var species:TreeSpecies.values())for(var age:TreeAge.values())for(int seed=0;seed<12;seed++) {
            var full=TreeShapeGenerator.generate(species,age,seed);var preview=TreeShapeGenerator.preview(species,age,seed);
            assertEquals(full.height(),preview.height());assertEquals(full.base(),TrunkProfile.previewFootprint(species,age,seed));
            assertTrue(preview.radius()<=8);
        }
    }
    @Test void isolatedBirchPreviewUsesRealBranchTrunkAndLeafVoxelsInsteadOfAnEllipsoid() throws Exception {
        for(long seed=0;seed<100;seed++)for(var candidate:TreeCandidates.rawAdultRootsForChunk(seed,0,0)) {
            if(candidate.x()<8||candidate.x()>23||candidate.z()<8||candidate.z()>23)continue;
            var ecology=com.github.xandergos.terraindiffusionmc.worldgen.ecology.EuropeanEcology.evaluate(seed,12,900,300,0,Double.POSITIVE_INFINITY,candidate.x(),candidate.z());
            if(!VegetationDistribution.acceptsAdult(candidate,seed,ecology)||ForestComposition.at(seed,candidate.x(),candidate.z(),ecology).choose(candidate.speciesRoll())!=TreeSpecies.BIRCH)continue;
            var full=TreeShapeGenerator.generate(TreeSpecies.BIRCH,candidate.age(),candidate.seed());Set<Voxel> expected=new HashSet<>(),actual=new HashSet<>();
            for(var voxel:full.voxels())if(voxel.y()>0)expected.add(new Voxel(candidate.x()+voxel.x(),100+voxel.y(),candidate.z()+voxel.z(),TreeSpecies.BIRCH,voxel.log()));
            var field=new TreeLodPreview.Field(){
                public TreeLodPreview.Climate climate(int x,int z){return x==candidate.x()&&z==candidate.z()?new TreeLodPreview.Climate((short)208,(short)300,12,900):null;}
                public int firstAir(int x,int z){return 101;}public int waterY(int x,int z){return Integer.MIN_VALUE;}
            };
            assertEquals(1,TreeLodPreview.paint(seed,0,0,32,-64,319,field,(x,y,z,species,log)->actual.add(new Voxel(x,y,z,species,log))));
            assertEquals(expected,actual);return;
        }
        fail("No suitable deterministic birch fixture");
    }
}

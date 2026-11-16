package com.github.xandergos.terraindiffusionmc.worldgen.tree;

import java.util.ArrayList;
import java.util.List;

public final class TreePlacement {
    private TreePlacement() {}

    public static List<TreePlacement.PlacedVoxel> parts(
            TreeShapeGenerator.Shape shape, int originX, int originY, int originZ, int chunkX, int chunkZ) {
        ArrayList<TreePlacement.PlacedVoxel> result = new ArrayList<>();

        for (TreeShapeGenerator.Voxel v : shape.voxels()) {
            int x = Math.addExact(originX, v.x());
            int z = Math.addExact(originZ, v.z());
            if (Math.floorDiv(x, 16) == chunkX && Math.floorDiv(z, 16) == chunkZ) {
                result.add(
                        new TreePlacement.PlacedVoxel(x, Math.addExact(originY, v.y()), z, v.log(), v.leafDistance()));
            }
        }

        return List.copyOf(result);
    }

    public record PlacedVoxel(int x, int y, int z, boolean log, int leafDistance) {}
}

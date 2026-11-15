package com.github.xandergos.terraindiffusionmc.compat.voxy;

/** Classifications are local to one mapper's palette. */
@FunctionalInterface
public interface GrassMipMapping {
    int AIR = 0, OTHER = 1, GROUND = 2, SHORT = 3, TALL = 4;

    int ewh$grassKind(int blockId);

    default boolean ewh$isGrass(int id) {
        return ewh$grassKind(id) >= SHORT;
    }
}

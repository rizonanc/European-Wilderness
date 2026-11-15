package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public final class WorldSessionBinding {
    private static final Identifier OVERWORLD_ID = Identifier.of("minecraft", "overworld");

    private WorldSessionBinding() {}

    public static boolean ownsSession(RegistryKey<World> dimension) {
        return dimension != null && OVERWORLD_ID.equals(dimension.getValue());
    }
}

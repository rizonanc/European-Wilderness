package com.github.xandergos.terraindiffusionmc.mixin.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.gui.screen.world.WorldCreator.WorldType;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryOwner;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.WorldPreset;
import org.junit.jupiter.api.Test;

class CreateWorldScreenWorldTabMixinTest {
    @Test
    void customizeUsesTheRegisteredPresetKey() throws ReflectiveOperationException {
        var check = CreateWorldScreenWorldTabMixin.class.getDeclaredMethod(
                "isTerrainDiffusionWorldTypeSelected", WorldType.class);
        check.setAccessible(true);
        assertTrue((boolean) check.invoke(null, worldType("terrain-diffusion-mc", "terrain_diffusion")));
        assertFalse((boolean) check.invoke(null, worldType("minecraft", "normal")));
        assertFalse((boolean) check.invoke(null, new WorldType(null)));
    }

    private static WorldType worldType(String namespace, String path) {
        RegistryEntryOwner<WorldPreset> owner = new RegistryEntryOwner<>() {};
        var key = RegistryKey.of(RegistryKeys.WORLD_PRESET, Identifier.of(namespace, path));
        return new WorldType(RegistryEntry.Reference.standAlone(owner, key));
    }
}

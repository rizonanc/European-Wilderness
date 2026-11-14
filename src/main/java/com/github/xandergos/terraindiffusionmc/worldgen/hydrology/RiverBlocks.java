package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.*;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/** Normal waterloggable slabs with natural textures, no falling-sand behaviour. */
public final class RiverBlocks {
    public static SlabBlock SAND_SLAB;
    public static SlabBlock TURF_SLAB;
    public static SlabBlock DIRT_SLAB;
    private RiverBlocks() {}
    public static void register() {
        SAND_SLAB=register("river_sand_slab",BlockSoundGroup.SAND);
        TURF_SLAB=register("river_turf_slab",BlockSoundGroup.GRASS);
        DIRT_SLAB=register("river_dirt_slab",BlockSoundGroup.GRAVEL);
    }
    private static SlabBlock register(String name,BlockSoundGroup sound) {
        Identifier id=Identifier.of("terrain-diffusion-mc",name);
        var block=new SlabBlock(AbstractBlock.Settings.create().strength(.5F).sounds(sound)
            .registryKey(RegistryKey.of(RegistryKeys.BLOCK,id)));
        Registry.register(Registries.BLOCK,id,block);
        Items.register(RegistryKey.of(RegistryKeys.ITEM,id),settings->new BlockItem(block,settings),
            new Item.Settings().useBlockPrefixedTranslationKey());
        return block;
    }
}

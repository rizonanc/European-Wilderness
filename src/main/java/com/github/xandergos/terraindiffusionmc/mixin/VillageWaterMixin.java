package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldSessionBinding;
import com.github.xandergos.terraindiffusionmc.worldgen.hydrology.VillageWaterGuard;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.function.Predicate;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.structure.StructureStart;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Structure.class)
public abstract class VillageWaterMixin {
    @Inject(method = "createStructureStart", at = @At("RETURN"), cancellable = true)
    private void td$avoidWater(
            RegistryEntry<Structure> entry,
            RegistryKey<World> dimension,
            DynamicRegistryManager registries,
            ChunkGenerator generator,
            BiomeSource biomes,
            NoiseConfig noise,
            StructureTemplateManager templates,
            long seed,
            ChunkPos chunk,
            int references,
            HeightLimitView world,
            Predicate<RegistryEntry<Biome>> biomePredicate,
            CallbackInfoReturnable<StructureStart> result) {
        var start = result.getReturnValue();
        var session = WorldSession.current();
        if (!start.hasChildren()
                || !entry.isIn(StructureTags.VILLAGE)
                || !WorldSessionBinding.ownsSession(dimension)
                || !(biomes instanceof TerrainDiffusionBiomeSource)
                || session == null
                || !session.isActive()
                || session.worldSeed() != seed) return;
        var overlay = session.riverOverlay();
        if (overlay == null) return;
        for (var piece : start.getChildren()) {
            if (VillageWaterGuard.intersects(overlay, piece.getBoundingBox())) {
                result.setReturnValue(StructureStart.DEFAULT);
                return;
            }
        }
    }
}

package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

public final class WorldScaleSettingsState extends PersistentState {
    private static final Codec<WorldScaleSettingsState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.optionalFieldOf("scale", 2).forGetter(WorldScaleSettingsState::getScale),
                    Codec.BOOL
                            .optionalFieldOf("explicit_scale", false)
                            .forGetter(WorldScaleSettingsState::hasExplicitScale))
            .apply(instance, WorldScaleSettingsState::new));
    private int scale;
    private boolean explicitScale;
    public static final PersistentStateType<WorldScaleSettingsState> TYPE = new PersistentStateType<>(
            "terrain_diffusion_world_settings", WorldScaleSettingsState::createDefault, CODEC, null);

    private WorldScaleSettingsState(int configuredScale, boolean hasExplicitScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = hasExplicitScale;
    }

    public static WorldScaleSettingsState createDefault() {
        return new WorldScaleSettingsState(2, false);
    }

    public int getScale() {
        return this.scale;
    }

    public boolean hasExplicitScale() {
        return this.explicitScale;
    }

    public void setScale(int configuredScale) {
        this.scale = WorldScaleManager.clampScale(configuredScale);
        this.explicitScale = true;
        this.markDirty();
    }
}

package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.nio.file.Path;

/** Small actual water controller for cross-package density integration tests. */
public final class DensityWaterFixture {
    private DensityWaterFixture() {}

    public static WorldWaterController create(WorldSession session, Path directory) {
        return new WorldWaterController(session, directory, 300, new WorldWaterGrid(64, 32, 128), 4, 4);
    }
}

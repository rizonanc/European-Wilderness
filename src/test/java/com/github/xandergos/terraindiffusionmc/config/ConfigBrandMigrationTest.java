package com.github.xandergos.terraindiffusionmc.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ConfigBrandMigrationTest {
    @Test
    void copiesOldSettingsOnceAndKeepsBothFiles(@TempDir Path directory) throws Exception {
        String oldName = "terrain-diffusion-menu.json";
        String newName = "european-wilderness-menu.json";
        Path original = directory.resolve(oldName);
        Path migrated = directory.resolve(newName);
        assertEquals(migrated, TerrainDiffusionConfig.migrateConfig(directory, newName, oldName));
        assertFalse(Files.exists(migrated));
        Files.writeString(original, "old profiles");
        assertEquals(migrated, TerrainDiffusionConfig.migrateConfig(directory, newName, oldName));
        assertEquals("old profiles", Files.readString(migrated));
        Files.writeString(migrated, "edited profiles");
        assertEquals(migrated, TerrainDiffusionConfig.migrateConfig(directory, newName, oldName));
        assertEquals("edited profiles", Files.readString(migrated));
        assertEquals("old profiles", Files.readString(original));
    }
}

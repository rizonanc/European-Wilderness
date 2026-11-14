package com.github.xandergos.terraindiffusionmc.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsAnimationTest {
   @Test
   void transitionClampsAndEasesOut() {
      assertEquals(1f, TerrainDiffusionSettingsScreen.transitionAmount(-20, 180));
      assertEquals(1f, TerrainDiffusionSettingsScreen.transitionAmount(0, 180));
      assertTrue(TerrainDiffusionSettingsScreen.transitionAmount(90, 180) < 0.5f);
      assertTrue(TerrainDiffusionSettingsScreen.transitionAmount(90, 180) > 0f);
      assertEquals(0f, TerrainDiffusionSettingsScreen.transitionAmount(180, 180));
      assertEquals(0f, TerrainDiffusionSettingsScreen.transitionAmount(300, 180));
   }
}

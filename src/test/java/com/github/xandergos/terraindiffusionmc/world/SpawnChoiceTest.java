package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnChoiceTest {
    @Test
    void choiceKeepsCoordinatesAndFullWidthSeed() {
        var choice = new SpawnChoice(-801, 550, "valley", -8793323193946896336L);
        var encoded = SpawnChoice.CODEC.encodeStart(JsonOps.INSTANCE, choice).getOrThrow();
        assertEquals(choice, SpawnChoice.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertFalse(choice.matches(80, 92, 77, 80, 80, (short) 229));
        assertTrue(choice.matches(80, 92, 87, 80, 80, (short) 229));
        assertThrows(IllegalArgumentException.class, () -> new SpawnChoice(30_000_000, 0, "coast", 1));
    }

    @Test
    void environmentsAreCheckedAgainstTerrain() {
        assertTrue(new SpawnChoice(0, 0, "coast", 1).matches(70, 62, 71, 73, 70, (short) 18));
        assertFalse(new SpawnChoice(0, 0, "coast", 1).matches(70, 65, 71, 73, 70, (short) 18));
        assertTrue(new SpawnChoice(0, 0, "forest", 1).matches(82, 85, 86, 85, 86, (short) 208));
        assertTrue(new SpawnChoice(0, 0, "foothills", 1).matches(110, 120, 101, 110, 110, (short) 229));
    }
}

package com.github.xandergos.terraindiffusionmc.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainWeatherLinkageTest {
    @Test
    void injectedFogAndWeatherCanCallTheirSharedHelper() throws Exception {
        String mixinPackage = "com/github/xandergos/terraindiffusionmc/mixin/";
        for (String hook : List.of("TerrainFogMixin", "MountainWeatherMixin")) {
            var node = new ClassNode();
            try (var bytes = getClass().getResourceAsStream("/" + mixinPackage + "client/" + hook + ".class")) {
                assertNotNull(bytes);
                new ClassReader(bytes).accept(node, 0);
            }
            var calls = new HashSet<String>();
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.owner.endsWith("/TerrainWeather")) {
                        assertFalse(call.owner.startsWith(mixinPackage), call.owner);
                        Class<?> helper = Class.forName(call.owner.replace('/', '.'));
                        assertTrue(Modifier.isPublic(helper.getModifiers()));
                        calls.add(call.name);
                    }
                }
            }
            assertEquals(hook.equals("TerrainFogMixin") ? java.util.Set.of("applies") : java.util.Set.of("applies", "snow"), calls);
        }
    }
}

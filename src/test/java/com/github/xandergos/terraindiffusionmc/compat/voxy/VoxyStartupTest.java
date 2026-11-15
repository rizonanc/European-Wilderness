package com.github.xandergos.terraindiffusionmc.compat.voxy;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxyStartupTest {
    private ClassNode code(String name) throws Exception {
        try (var input =
                getClass().getResourceAsStream("/com/github/xandergos/terraindiffusionmc/" + name + ".class")) {
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    @Test
    void modInitializerCannotConstructVoxyConfigDependentApiBeforeGraphicsStartup() throws Exception {
        var node = code("compat/voxy/VoxyCompat");
        boolean gate = false, constructor = false;
        for (var method : node.methods)
            for (var insn : method.instructions) {
                if (insn instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && type.desc.endsWith("/Voxy016Api")) {
                    assertEquals("tick", method.name);
                    assertTrue(gate, "Voxy GPU readiness must be checked first");
                    constructor = true;
                }
                if (method.name.equals("tick")
                        && insn instanceof MethodInsnNode call
                        && call.name.equals("runtimeReady")) gate = true;
            }
        assertTrue(constructor);
        assertTrue(gate);
        var api = code("compat/voxy/Voxy016Api");
        var ready = api.methods.stream().filter(m -> m.name.equals("runtimeReady")).findFirst().orElseThrow();
        for (var instruction : ready.instructions)
            if (instruction instanceof LdcInsnNode literal)
                assertFalse(String.valueOf(literal.cst).contains("VoxyConfig"));
    }

    @Test
    void retiredCoarseMixinsCannotReactivateFromLegacyTrueSetting() throws Exception {
        var plugin = new com.github.xandergos.terraindiffusionmc.mixin.compat.CoarseVoxyMixinPlugin();
        assertFalse(plugin.shouldApplyMixin("anything", "anything"));
        var config = code("config/TerrainDiffusionConfig");
        var coarse = config.methods.stream().filter(m -> m.name.equals("voxyCoarse")).findFirst().orElseThrow();
        assertTrue(
                java.util.stream.StreamSupport.stream(coarse.instructions.spliterator(), false)
                        .anyMatch(i -> i.getOpcode() == Opcodes.ICONST_0));
    }
}

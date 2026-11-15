package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;

/** Standalone ASM/Mixin probe: transforms the actual pinned Voxy classes without
 * opening Minecraft/GL or replacing anything in the user's profile. */
public final class CoarseMixinProbe {
    public static class Service extends MixinServiceAbstract
            implements IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {
        public String getName() {
            return "CoarseProbe";
        }

        public boolean isValid() {
            return true;
        }

        public MixinEnvironment.Phase getInitialPhase() {
            return MixinEnvironment.Phase.DEFAULT;
        }

        public IClassProvider getClassProvider() {
            return this;
        }

        public IClassBytecodeProvider getBytecodeProvider() {
            return this;
        }

        public ITransformerProvider getTransformerProvider() {
            return this;
        }

        public IClassTracker getClassTracker() {
            return this;
        }

        public IMixinAuditTrail getAuditTrail() {
            return null;
        }

        public Collection<String> getPlatformAgents() {
            return List.of();
        }

        public IContainerHandle getPrimaryContainer() {
            return new ContainerHandleVirtual("coarse-probe");
        }

        public InputStream getResourceAsStream(String name) {
            return CoarseMixinProbe.class.getClassLoader().getResourceAsStream(name);
        }

        public URL[] getClassPath() {
            return new URL[0];
        }

        public Class<?> findClass(String name) throws ClassNotFoundException {
            return findClass(name, false);
        }

        public Class<?> findClass(String name, boolean init) throws ClassNotFoundException {
            return Class.forName(name, init, CoarseMixinProbe.class.getClassLoader());
        }

        public Class<?> findAgentClass(String name, boolean init) throws ClassNotFoundException {
            return findClass(name, init);
        }

        public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
            return getClassNode(name, false, 0);
        }

        public ClassNode getClassNode(String name, boolean transform) throws ClassNotFoundException, IOException {
            return getClassNode(name, transform, 0);
        }

        public ClassNode getClassNode(String name, boolean transform, int flags)
                throws ClassNotFoundException, IOException {
            try (var in = getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) throw new ClassNotFoundException(name);
                var node = new ClassNode();
                new ClassReader(in).accept(node, flags);
                return node;
            }
        }

        public Collection<ITransformer> getTransformers() {
            return List.of();
        }

        public Collection<ITransformer> getDelegatedTransformers() {
            return List.of();
        }

        public void addTransformerExclusion(String name) {}

        public void registerInvalidClass(String name) {}

        public boolean isClassLoaded(String name) {
            return false;
        }

        public String getClassRestrictions(String name) {
            return "";
        }

        public IMixinTransformer transformer() {
            return getInternal(IMixinTransformerFactory.class).createTransformer();
        }
    }

    public static final class Props implements IGlobalPropertyService {
        private record Key(String name) implements IPropertyKey {}

        private final Map<IPropertyKey, Object> values = new HashMap<>();

        public IPropertyKey resolveKey(String name) {
            return new Key(name);
        }

        @SuppressWarnings("unchecked")
        public <T> T getProperty(IPropertyKey key) {
            return (T) values.get(key);
        }

        public void setProperty(IPropertyKey key, Object value) {
            values.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T getProperty(IPropertyKey key, T fallback) {
            return (T) values.getOrDefault(key, fallback);
        }

        public String getPropertyString(IPropertyKey key, String fallback) {
            return values.getOrDefault(key, fallback).toString();
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("mixin.service", Service.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("coarse-probe.mixins.json");
        var service = (Service) MixinService.getService();
        var transformer = service.transformer();
        String base = "me.cortex.voxy.client.core.rendering.";
        Map<String, Integer> calls = new LinkedHashMap<>();
        for (String target :
                List.of(
                        base + "building.RenderGenerationService",
                        base + "building.RenderDataFactory",
                        base + "hierachical.AsyncNodeManager")) {
            var node = service.getClassNode(target);
            var writer = new ClassWriter(0);
            node.accept(writer);
            byte[] output = transformer.transformClassBytes(target, target, writer.toByteArray());
            var after = new ClassNode();
            new ClassReader(output).accept(after, 0);
            int count = 0;
            for (var method : after.methods)
                for (var insn : method.instructions)
                    if (insn instanceof MethodInsnNode call
                            && call.owner.equals(
                                    "com/github/xandergos/terraindiffusionmc/compat/voxy/CoarseRenderHooks")) count++;
            if (count == 0) throw new AssertionError("Mixin did not apply: " + target);
            if (target.endsWith("RenderGenerationService")
                    && !after.interfaces.contains(
                            "com/github/xandergos/terraindiffusionmc/compat/voxy/VoxyRenderAccess"))
                throw new AssertionError("Missing engine accessor");
            if (target.endsWith("RenderGenerationService")) {
                boolean observe = false;
                for (var method : after.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof MethodInsnNode call
                                && call.owner.equals(
                                        "com/github/xandergos/terraindiffusionmc/compat/voxy/CoarseRenderHooks")
                                && call.name.equals("observeService")) observe = true;
                if (!observe) throw new AssertionError("Missing live mesh queue observation");
                int revisionChecks = 0;
                for (var method : after.methods)
                    for (var insn : method.instructions)
                        if (insn instanceof MethodInsnNode call
                                && call.owner.equals(
                                        "com/github/xandergos/terraindiffusionmc/compat/voxy/CoarseRenderHooks")
                                && call.name.equals("meshCurrent")) revisionChecks++;
                if (revisionChecks != 3)
                    throw new AssertionError(
                            "Expected stale guards before/after generateMesh and result delivery, got "
                                    + revisionChecks);
                if (after.methods.stream()
                        .noneMatch(m ->
                                m.name.equals("getTaskCount")
                                        && m.desc.equals("()I")
                                        && (m.access & Opcodes.ACC_PUBLIC) != 0))
                    throw new AssertionError("Public Voxy queue ABI missing");
            }
            if (target.endsWith("RenderDataFactory")) {
                long redirects =
                        after.methods.stream()
                                .filter(m -> m.name.equals("acquireNeighborData"))
                                .flatMap(m -> {
                                    List<MethodInsnNode> list = new ArrayList<>();
                                    for (var i : m.instructions)
                                        if (i instanceof MethodInsnNode call && call.name.contains("ewh$neighbor"))
                                            list.add(call);
                                    return list.stream();
                                })
                                .count();
                if (redirects != 6) throw new AssertionError("Expected six neighbor redirects, got " + redirects);
            }
            calls.put(target, count);
        }
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(calls);
        if (args.length > 0) Files.writeString(Path.of(args[0]), json + "\n");
        System.out.println(json);
    }
}

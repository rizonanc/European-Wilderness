package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.GenerationProfile;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.io.IOException;
import java.util.Map;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;

/** Actual pinned queue record and Mixin transformer; only its Minecraft environment is stubbed. */
public final class IngestSnapshotMixinProbe {
    private static final String TARGET = "me.cortex.voxy.common.world.service.VoxelIngestService$IngestSection";
    private static final String SECTION = "net.minecraft.world.chunk.ChunkSection";
    private static final String LIGHT = "net.minecraft.world.chunk.ChunkNibbleArray";
    private static final String ENGINE = "me.cortex.voxy.common.world.WorldEngine";

    public static final class Service extends CoarseMixinProbe.Service {
        @Override
        public ClassNode getClassNode(String name, boolean transform, int flags)
                throws ClassNotFoundException, IOException {
            ClassNode node = super.getClassNode(name, transform, flags);
            if (!name.equals(TARGET)) return node;
            var mapped = new ClassNode();
            node.accept(
                    new ClassRemapper(
                            mapped,
                            new SimpleRemapper(
                                    Map.of(
                                            "net/minecraft/class_2826", SECTION.replace('.', '/'),
                                            "net/minecraft/class_2804", LIGHT.replace('.', '/')))));
            return mapped;
        }
    }

    private static byte[] bytes(ClassNode node) {
        var w = new ClassWriter(0);
        node.accept(w);
        return w.toByteArray();
    }

    private static byte[] sectionStub() {
        String name = SECTION.replace('.', '/');
        var w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PUBLIC, "value", "I", null, null).visitEnd();
        var m = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitFieldInsn(Opcodes.PUTFIELD, name, "value", "I");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC, "copy", "()L" + name + ";", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, name);
        m.visitInsn(Opcodes.DUP);
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, name, "value", "I");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "(I)V", false);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(Opcodes.ACC_PUBLIC, "hasAny", "(Ljava/util/function/Predicate;)Z", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitFieldInsn(Opcodes.GETFIELD, name, "value", "I");
        m.visitIntInsn(Opcodes.BIPUSH, 7);
        var dry = new Label();
        m.visitJumpInsn(Opcodes.IF_ICMPNE, dry);
        m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IRETURN);
        m.visitLabel(dry);
        m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        m.visitInsn(Opcodes.ICONST_0);
        m.visitInsn(Opcodes.IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] empty(String name) {
        var w = new ClassWriter(0);
        w.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
        w.visitEnd();
        return w.toByteArray();
    }

    private static final class Fixture extends ClassLoader {
        final Map<String, byte[]> definitions;

        Fixture(byte[] record) {
            super(IngestSnapshotMixinProbe.class.getClassLoader());
            definitions = Map.of(TARGET, record, SECTION, sectionStub(), LIGHT, empty(LIGHT), ENGINE, empty(ENGINE));
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!definitions.containsKey(name)) return super.loadClass(name, resolve);
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                byte[] b = definitions.get(name);
                c = defineClass(name, b, 0, b.length);
            }
            if (resolve) resolveClass(c);
            return c;
        }

        Object[] enqueue() throws Exception {
            return enqueue(7);
        }

        Object[] enqueue(int value) throws Exception {
            Class<?> record = loadClass(TARGET), section = loadClass(SECTION), light = loadClass(LIGHT);
            var ctor =
                    record.getDeclaredConstructor(
                            int.class, int.class, int.class, loadClass(ENGINE), section, light, light);
            ctor.setAccessible(true);
            Object source = section.getConstructor(int.class).newInstance(value);
            Object task = ctor.newInstance(-33, 13, -420, null, source, null, null);
            var accessor = record.getDeclaredMethod("section");
            accessor.setAccessible(true);
            return new Object[] {source, accessor.invoke(task)};
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("mixin.service", Service.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        var service = (Service) MixinService.getService();
        byte[] original = bytes(service.getClassNode(TARGET));
        Object[] old = new Fixture(original).enqueue();
        old[0].getClass().getField("value").setInt(old[0], 171);
        if (old[0] != old[1] || old[1].getClass().getField("value").getInt(old[1]) != 171)
            throw new AssertionError("Did not reproduce shared mutable input");
        Mixins.addConfiguration("ingest-snapshot-probe.mixins.json");
        byte[] transformed = service.transformer().transformClassBytes(TARGET, TARGET, original);
        Object[] inactive = new Fixture(transformed).enqueue();
        if (inactive[0] != inactive[1]) throw new AssertionError("Changed ingest outside an active TD session");
        var ticket = WorldSession.activate(GenerationProfile.defaultsFor(2, "snapshot-probe", "cpu", "cpu"), 7);
        try {
            Object[] dry = new Fixture(transformed).enqueue(171);
            if (dry[0] != dry[1]) throw new AssertionError("Dry section was copied");
            Object[] active = new Fixture(transformed).enqueue();
            if (active[0] == active[1]) throw new AssertionError("Worker still owns mutable source");
            active[0].getClass().getField("value").setInt(active[0], 171);
            if (active[1].getClass().getField("value").getInt(active[1]) != 7)
                throw new AssertionError("Queued water changed after enqueue");
            active[1].getClass().getField("value").setInt(active[1], 0);
            if (active[0].getClass().getField("value").getInt(active[0]) != 171)
                throw new AssertionError("Snapshot changed real source");
        } finally {
            WorldSession.deactivate(ticket);
        }
        System.out.println(
                "PASS: pinned record reproduces shared input; transformed water queue isolates later source/worker mutations; dry sections and inactive sessions unchanged.");
    }
}

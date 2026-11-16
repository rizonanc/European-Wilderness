package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;

/** Actual Mixin transformation and execution of the pinned section mipper, no game/GL. */
public final class WaterMipMixinProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("mixin.service", CoarseMixinProbe.Service.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("water-mip-probe.mixins.json");
        var service = (CoarseMixinProbe.Service) MixinService.getService();
        var transformer = service.transformer();
        Map<String, byte[]> transformed = new HashMap<>();
        for (String target : List.of(
                "me.cortex.voxy.common.world.other.Mapper",
                "me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper")) {
            var before = service.getClassNode(target);
            var w = new ClassWriter(0);
            before.accept(w);
            byte[] bytes = transformer.transformClassBytes(target, target, w.toByteArray());
            transformed.put(target, bytes);
            var after = new ClassNode();
            new ClassReader(bytes).accept(after, 0);
            if (target.endsWith("Mapper")) {
                if (!after.interfaces.contains(WaterMipMapping.class.getName().replace('.', '/')))
                    throw new AssertionError("Mapper classification interface absent");
            } else {
                long redirects = after.methods.stream()
                        .flatMap(m -> Arrays.stream(m.instructions.toArray()))
                        .filter(i -> i instanceof MethodInsnNode call
                                && call.owner.equals(
                                        WaterSurfaceMip.class.getName().replace('.', '/'))
                                && call.name.equals("correct"))
                        .count();
                if (redirects != 1) throw new AssertionError("Expected one fine-surface correction: " + redirects);
            }
        }
        var fixture = new WaterMipPolicyTest.Fixture(transformed);
        Class<?> voxel = fixture.loadClass("me.cortex.voxy.common.voxelization.VoxelizedSection");
        Class<?> mipper = fixture.loadClass("me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper");
        var run = mipper.getMethod("mipSection", voxel, fixture.mapper.getClass());
        Object input = voxel.getMethod("createEmpty").invoke(null);
        long[] data = (long[]) voxel.getField("section").get(input);
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) data[y * 256 + z * 16 + x] = (long) (y < 14 ? 171 : y == 14 ? 7 : 0) << 27;
        long[] base = Arrays.copyOf(data, 4096);
        run.invoke(null, input, fixture.mapper);
        if (WaterMipPolicy.blockId(data[4680]) != 171)
            throw new AssertionError("Inactive session must preserve stock result");
        var ticket = WorldSession.activate(GenerationProfile.defaultsFor(2, "water-mip-probe", "cpu", "cpu"), 7);
        try {
            run.invoke(null, input, fixture.mapper);
            if (WaterMipPolicy.blockId(data[4680]) != 7) throw new AssertionError("Applied mixin lost shallow water");
            if (!Arrays.equals(base, Arrays.copyOf(data, 4096))) throw new AssertionError("Base voxels modified");
            for (int z = 0; z < 2; z++)
                for (int x = 0; x < 2; x++) {
                    data[13 * 256 + z * 16 + x] = 180L << 27;
                    data[14 * 256 + z * 16 + x] = 181L << 27;
                }
            long[] reefBase = Arrays.copyOf(data, 4096);
            run.invoke(null, input, fixture.mapper);
            if (WaterMipPolicy.blockId(data[4680]) != 7)
                throw new AssertionError("Applied mixin raised minority reef through water");
            if (!Arrays.equals(reefBase, Arrays.copyOf(data, 4096)))
                throw new AssertionError("Native reef voxels modified");
        } finally {
            WorldSession.deactivate(ticket);
        }
        String result =
                "PASS: mapper interface merged; one fine-surface post-pass applied; transformed pinned mipper preserves water and waterlogged reef through L1-L4; inactive session keeps stock result; base voxels unchanged.\n";
        if (args.length > 0) Files.writeString(Path.of(args[0]), result);
        System.out.print(result);
    }
}

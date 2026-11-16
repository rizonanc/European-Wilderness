package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;

public final class GrassMipMixinProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("mixin.service", CoarseMixinProbe.Service.class.getName());
        MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration(args.length > 1 ? args[1] : "grass-mip-probe.mixins.json");
        var service = (CoarseMixinProbe.Service) MixinService.getService();
        var transformer = service.transformer();
        Map<String, byte[]> defs = new HashMap<>();
        for (String target : List.of(
                "me.cortex.voxy.common.world.other.Mapper",
                "me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper")) {
            var w = new ClassWriter(0);
            service.getClassNode(target).accept(w);
            byte[] b = transformer.transformClassBytes(target, target, w.toByteArray());
            defs.put(target, b);
            var n = new ClassNode();
            new ClassReader(b).accept(n, 0);
            if (target.endsWith("Mapper")
                    && !n.interfaces.contains(GrassMipMapping.class.getName().replace('.', '/')))
                throw new AssertionError("Missing grass palette interface");
        }
        var f = new WaterMipPolicyTest.Fixture(defs);
        Class<?> voxel = f.loadClass("me.cortex.voxy.common.voxelization.VoxelizedSection"),
                mip = f.loadClass("me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper");
        Object input = voxel.getMethod("createEmpty").invoke(null);
        long[] d = (long[]) voxel.getField("section").get(input);
        for (int y = 0; y <= 2; y++) Arrays.fill(d, y * 256, (y + 1) * 256, 171L << 27);
        Arrays.fill(d, 3 * 256, 4 * 256, 31L << 27);
        var run = mip.getMethod("mipSection", voxel, f.mapper.getClass());
        run.invoke(null, input, f.mapper);
        long[] original = d.clone();
        if (WaterMipPolicy.blockId(d[4096 + 2 * 64]) != 0)
            throw new AssertionError("Inactive session changed stock cap");
        var ticket = WorldSession.activate(GenerationProfile.defaultsFor(2, "grass-mip-probe", "cpu", "cpu"), 7);
        try {
            run.invoke(null, input, f.mapper);
            if (WaterMipPolicy.blockId(d[4096 + 2 * 64]) != 31 || WaterMipPolicy.blockId(d[4608 + 16]) != 31)
                throw new AssertionError("Grass did not survive above soil");
            if (!Arrays.equals(Arrays.copyOf(original, 4096), Arrays.copyOf(d, 4096)))
                throw new AssertionError("L0 changed");
            if (!Arrays.equals(Arrays.copyOfRange(original, 4672, 4681), Arrays.copyOfRange(d, 4672, 4681)))
                throw new AssertionError("Far mips changed");
            if (args.length > 1) {
                Arrays.fill(d, 0);
                for (int y = 0; y < 14; y++) Arrays.fill(d, y * 256, (y + 1) * 256, 171L << 27);
                Arrays.fill(d, 14 * 256, 15 * 256, 7L << 27);
                long[] fine = Arrays.copyOf(d, 4096);
                run.invoke(null, input, f.mapper);
                if (WaterMipPolicy.blockId(d[4680]) != 7) throw new AssertionError("Combined hooks regressed water");
                if (!Arrays.equals(fine, Arrays.copyOf(d, 4096)))
                    throw new AssertionError("Combined hooks changed source water");
            }
        } finally {
            WorldSession.deactivate(ticket);
        }
        String result =
                "PASS: pinned mapper/mipper transformed; active session retains grass caps on L1/L2 above unchanged soil; L0 and L3/L4 unchanged; inactive session stock."
                        + (args.length > 1 ? " Combined water preservation also passed." : "")
                        + "\n";
        Files.writeString(Path.of(args[0]), result);
        System.out.print(result);
    }
}

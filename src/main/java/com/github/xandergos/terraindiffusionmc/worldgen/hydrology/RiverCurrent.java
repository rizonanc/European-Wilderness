package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldSessionBinding;
import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class RiverCurrent {
    private static final boolean ENABLED = TerrainDiffusionConfig.enabled("rivers.current");
    private static final boolean BOATS = TerrainDiffusionConfig.enabled("rivers.boats");
    private static final boolean ITEMS = TerrainDiffusionConfig.enabled("rivers.items");
    private static final int STRENGTH = TerrainDiffusionConfig.percent("rivers.strength");

    private RiverCurrent() {}

    public static short atEntity(Entity entity) {
        if (!ENABLED
                || STRENGTH == 0
                || (entity instanceof AbstractBoatEntity ? !BOATS : !ITEMS)
                || !(entity.getEntityWorld() instanceof ServerWorld world)
                || !WorldSessionBinding.ownsSession(world.getRegistryKey())
                || !(world.getChunkManager().getChunkGenerator().getBiomeSource()
                        instanceof TerrainDiffusionBiomeSource)) return 0;
        var session = WorldSession.current();
        if (session == null || !session.isActive()) return 0;
        var overlay = session.riverOverlay();
        if (overlay == null) return 0;
        BlockPos pos = BlockPos.ofFloored(entity.getX(), entity.getBoundingBox().minY - 0.01, entity.getZ());
        if (!world.getFluidState(pos).isIn(FluidTags.WATER)) return 0;
        return scale(overlay.currentAt(pos.getX(), pos.getY(), pos.getZ()), STRENGTH);
    }

    static short scale(short current, int percent) {
        if (current == 0 || percent == 100) return current;
        int x = (byte) (current >> 8), z = (byte) current;
        double factor = Math.min(percent / 100.0, 127.0 / Math.max(Math.abs(x), Math.abs(z)));
        return components(x * factor, z * factor);
    }

    // Two signed components keep a whole core's flow field at two bytes per column.
    static short pack(double dx, double dz, double slope, long accumulation, double outletFactor) {
        double length = Math.hypot(dx, dz);
        if (length < 1e-9) return 0;
        double strength = Math.min(1, 0.24 + 0.10 * Math.log1p(accumulation / 96.0) + 0.30 * Math.max(0, slope));
        double scale = 127 * strength * outletFactor / length;
        return components(dx * scale, dz * scale);
    }

    private static short components(double x, double z) {
        return (short) (((int) Math.round(x) << 8) | ((int) Math.round(z) & 255));
    }

    public static Vec3d accelerate(Vec3d velocity, short current, boolean boat) {
        if (current == 0) return velocity;
        double x = (byte) (current >> 8) / 127.0, z = (byte) current / 127.0;
        double strength = Math.hypot(x, z);
        double dx = x / strength, dz = z / strength;
        double along = velocity.x * dx + velocity.z * dz;
        double speedLimit = (boat ? 0.14 : 0.23) * strength;
        double push = Math.clamp((speedLimit - along) * 0.28, 0, boat ? 0.022 : 0.038);
        return velocity.add(dx * push, 0, dz * push);
    }

    static short[] freeze(short[] currents, int[] water) {
        if (currents == null) return null;
        if (currents.length != water.length) throw new IllegalArgumentException("Invalid river current field");
        for (int i = 0; i < currents.length; i++)
            if (currents[i] != 0 && water[i] <= 62) throw new IllegalArgumentException("Current outside river water");
        return currents.clone();
    }

    static void write(DataOutput out, short[] currents) throws IOException {
        out.writeBoolean(currents != null);
        if (currents != null) for (short current : currents) out.writeShort(current);
    }

    static short[] read(DataInput in, int count) throws IOException {
        if (!in.readBoolean()) return null;
        short[] currents = new short[count];
        for (int i = 0; i < count; i++) currents[i] = in.readShort();
        return currents;
    }
}

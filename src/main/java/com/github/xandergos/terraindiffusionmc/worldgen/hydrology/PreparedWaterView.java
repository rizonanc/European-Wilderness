package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.settings.WorldSession;
import java.util.List;

/** Detached immutable snapshot for optional renderers; contains no generator callbacks. */
public final class PreparedWaterView implements WorldSession.RiverOverlay {
    private final int x, z, side;
    private final int[] air, water;
    private final List<Waterfall> falls;

    PreparedWaterView(int x, int z, int side, int[] air, int[] water, List<Waterfall> falls) {
        this(x, z, side, air, water, falls, false);
    }

    private PreparedWaterView(int x, int z, int side, int[] air, int[] water, List<Waterfall> falls, boolean trusted) {
        this.x = x;
        this.z = z;
        this.side = side;
        this.air = trusted ? air : air.clone();
        this.water = trusted ? water : water.clone();
        this.falls = List.copyOf(falls);
    }

    static PreparedWaterView fromFrozenTile(WorldWaterTile tile) {
        return new PreparedWaterView(tile.x, tile.z, tile.side, tile.air, tile.water, tile.falls, true);
    }

    @Override
    public boolean isReady(int wx, int wz) {
        return (long) wx - x >= 0 && (long) wz - z >= 0 && (long) wx - x < side && (long) wz - z < side;
    }

    @Override
    public int carvedHeight(int wx, int wz, int raw) {
        return isReady(wx, wz) ? air[(wz - z) * side + wx - x] : raw;
    }

    @Override
    public int waterY(int wx, int wz) {
        return isReady(wx, wz) ? water[(wz - z) * side + wx - x] : PreparedWaterArea.NONE;
    }

    @Override
    public int bedY(int wx, int wz, int raw) {
        return carvedHeight(wx, wz, raw) - 1;
    }

    @Override
    public List<Waterfall> waterfalls() {
        return falls;
    }

    @Override
    public Waterfall waterfallAt(int wx, int wz) {
        for (var f : falls) if (f.contains(wx, wz)) return f;
        return null;
    }
}

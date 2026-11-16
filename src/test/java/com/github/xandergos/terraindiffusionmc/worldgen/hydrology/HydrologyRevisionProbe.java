package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Compare new plans on an existing captured neural height map, without GPU or live-world writes. */
public final class HydrologyRevisionProbe {
    public static void main(String[] args) throws Exception {
        var store = new DiskTerrainStore(Path.of(args[0]));
        var out = Path.of(args[1]);
        Files.createDirectories(out);
        String fp = "7d73c789d6b92225ca49fc0b8087c3353eecad582fcc0c92f4b6c0062eeee577";
        int side = 1024, origin = 3584;
        int[][] raw = new int[side][side];
        for (int tz = 14; tz < 18; tz++)
            for (int tx = 14; tx < 18; tx++) {
                var tile = store.load(TerrainTileKey.ofTile(12345, tx, tz, 256, fp))
                        .orElseThrow();
                for (int z = 0; z < 256; z++)
                    for (int x = 0; x < 256; x++)
                        raw[(tz - 14) * 256 + z][(tx - 14) * 256 + x] = MountainHeightField.sample(
                                        12345, tx * 256 + x, tz * 256 + z, tile.heightAt(x, z), 2, 1871)
                                .surfaceY();
            }
        long[] counts = new long[2], nanos = new long[2];
        for (int revision = 3; revision <= 4; revision++) {
            long start = System.nanoTime();
            var result = PreparedWaterArea.calculate(12345, origin, origin, raw, null, revision);
            nanos[revision - 3] = System.nanoTime() - start;
            counts[revision - 3] = Arrays.stream(result.water())
                    .filter(w -> w != PreparedWaterArea.NONE)
                    .count();
            try (var stream = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(out.resolve("revision-" + revision + ".bin"))))) {
                stream.writeInt(side);
                for (var row : raw) for (int value : row) stream.writeInt(value);
                for (int value : result.water()) stream.writeInt(value);
            }
        }
        String result = "{\"seed\":12345,\"origin\":3584,\"side\":1024,\"wetColumns\":"
                + Arrays.toString(counts)
                + ",\"nanoseconds\":"
                + Arrays.toString(nanos)
                + ",\"scope\":\"single captured heightmap, finite unowned catchment; not game FPS or default owned layout\"}\n";
        Files.writeString(out.resolve("hydrology-probe.json"), result);
        System.out.print(result);
    }
}

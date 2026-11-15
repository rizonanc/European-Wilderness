package com.github.xandergos.terraindiffusionmc.compat.voxy;

import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.worldgen.cache.DiskTerrainStore;
import com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTileKey;
import com.github.xandergos.terraindiffusionmc.worldgen.mountain.MountainHeightField;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Approximation quality on one captured 1024-square, not a game benchmark. */
public final class CoarseAccuracyProbe {
    public static void main(String[] args) throws Exception {
        int g = CoarseLodPatch.GRID;
        var tensor = new FloatTensor(new int[] {7, g, g});
        var bytes = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0]))).order(ByteOrder.LITTLE_ENDIAN);
        for (int n = 0; n < tensor.data.length; n++) tensor.data[n] = bytes.getFloat();
        var patch = new CoarseLodPatch(new CoarseLodPatch.Key(0, 0), 2, 8, 12345, 1871, tensor);
        var store = new DiskTerrainStore(Path.of(args[1]));
        String fp = "7d73c789d6b92225ca49fc0b8087c3353eecad582fcc0c92f4b6c0062eeee577";
        List<Integer> errors = new ArrayList<>();
        long total = 0;
        int exactMin = 9999, exactMax = -9999, coarseMin = 9999, coarseMax = -9999;
        var tiles = new com.github.xandergos.terraindiffusionmc.worldgen.terrain.TerrainTile[4][4];
        for (int tz = 14; tz < 18; tz++)
            for (int tx = 14; tx < 18; tx++) {
                var tile = store.load(TerrainTileKey.ofTile(12345, tx, tz, 256, fp)).orElseThrow();
                tiles[tz - 14][tx - 14] = tile;
                for (int z = 0; z < 256; z += 8)
                    for (int x = 0; x < 256; x += 8) {
                        int wx = tx * 256 + x, wz = tz * 256 + z;
                        int exact = MountainHeightField.sample(12345, wx, wz, tile.heightAt(x, z), 2, 1871).surfaceY();
                        int coarse = patch.surface(wx, wz).firstAir(), error = Math.abs(exact - coarse);
                        errors.add(error);
                        total += error;
                        exactMin = Math.min(exactMin, exact);
                        exactMax = Math.max(exactMax, exact);
                        coarseMin = Math.min(coarseMin, coarse);
                        coarseMax = Math.max(coarseMax, coarse);
                    }
            }
        Collections.sort(errors);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("seed", 12345);
        report.put("origin", 3584);
        report.put("side", 1024);
        report.put("sampleStride", 8);
        report.put("samples", errors.size());
        report.put("meanAbsoluteHeightError", total / (double) errors.size());
        report.put("p95HeightError", errors.get((int) (errors.size() * .95)));
        report.put("maxHeightError", errors.getLast());
        report.put("exactHeightRange", List.of(exactMin, exactMax));
        report.put("coarseHeightRange", List.of(coarseMin, coarseMax));
        java.util.function.IntBinaryOperator exact =
                (wx, wz) ->
                        MountainHeightField.sample(
                                        12345,
                                        wx,
                                        wz,
                                        tiles[Math.floorDiv(wz, 256) - 14][Math.floorDiv(wx, 256) - 14].heightAt(
                                                Math.floorMod(wx, 256), Math.floorMod(wz, 256)),
                                        2,
                                        1871)
                                .surfaceY();
        var corrections = new CoarseLodCorrections(262144);
        var anchors = new ArrayList<CoarseLodCorrections.Anchor>();
        int boundary = 3584 + 512;
        for (int z = 3584; z < 3584 + 1024; z += 16)
            for (int x : new int[] {boundary - 32, boundary - 16, boundary - 1})
                anchors.add(
                        new CoarseLodCorrections.Anchor(x, z, exact.applyAsInt(x, z) - patch.surface(x, z).firstAir()));
        corrections.add(anchors);
        double oldError = 0, newError = 0;
        int boundarySamples = 0;
        for (int z = 3584; z < 3584 + 1024; z += 8)
            for (int x = boundary; x < boundary + 32; x += 4) {
                int actual = exact.applyAsInt(x, z), coarse = patch.surface(x, z).firstAir();
                oldError += Math.abs(actual - coarse);
                newError += Math.abs(actual - (coarse + corrections.adjustment(x, z)));
                boundarySamples++;
            }
        report.put(
                "syntheticExactBoundary",
                Map.of(
                        "samples",
                        boundarySamples,
                        "originalMeanAbsoluteError",
                        oldError / boundarySamples,
                        "correctedMeanAbsoluteError",
                        newError / boundarySamples,
                        "transitionRadius",
                        CoarseLodCorrections.RADIUS));
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report);
        Files.writeString(Path.of(args[2]), json + "\n");
        System.out.println(json);
    }
}

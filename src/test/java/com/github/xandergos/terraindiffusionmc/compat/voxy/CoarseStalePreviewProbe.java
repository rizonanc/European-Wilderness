package com.github.xandergos.terraindiffusionmc.compat.voxy;

import java.nio.file.*;
import java.util.*;

/** Runs unchanged against the previous and candidate released JARs. No game/GL/save writes. */
public final class CoarseStalePreviewProbe {
    public static void main(String[] args) throws Exception {
        long[] palette = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        var results = new ArrayList<Map<String, Object>>();
        for (int level = 0; level <= 4; level++) {
            int unit = 1 << level, side = 32 * unit;
            var field = new CoarseLodPatch.Surface[1024];
            Arrays.fill(field, new CoarseLodPatch.Surface(64 + 8 * unit, (short) 229, 0));
            long[] source = new long[32768], target = new long[32768];
            Arrays.fill(source, 24 * 1024, 25 * 1024, 99);
            var result =
                    CoarseSectionComposer.fill(
                            level,
                            -1,
                            64 / side,
                            -1,
                            -64,
                            1871,
                            field,
                            source,
                            target,
                            (x, z) -> false,
                            b -> palette,
                            v -> v == 0);
            results.add(
                    Map.of(
                            "level",
                            level,
                            "staleSlabValue",
                            target[24 * 1024],
                            "groundValue",
                            target[0],
                            "originalSlabValue",
                            source[24 * 1024],
                            "children",
                            Byte.toUnsignedInt(result.children())));
        }
        String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(results);
        Files.writeString(Path.of(args[0]), json + "\n");
        System.out.println(json);
    }
}

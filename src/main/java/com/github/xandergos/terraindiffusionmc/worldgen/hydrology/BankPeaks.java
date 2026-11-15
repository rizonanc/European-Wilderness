package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

/** Lower isolated one-to-four-column soil caps; keep continuous banks and water barriers. */
final class BankPeaks {
    static void lower(int side, int[] air, int[] water) {
        int[] original = air.clone(), group = new int[4];
        int[] offsets = {-1, 1, -side, side};
        for (int z = 8; z < side - 8; z++)
            for (int x = 8; x < side - 8; x++) {
                int p = z * side + x, height = original[p];
                if (water[p] != PreparedWaterArea.NONE) continue;
                int shore = PreparedWaterArea.NONE;
                for (int dz = -3; dz <= 3; dz++)
                    for (int dx = -3; dx <= 3; dx++)
                        if (Math.abs(dx) + Math.abs(dz) <= 3) shore = Math.max(shore, water[p + dz * side + dx]);
                if (shore == PreparedWaterArea.NONE || height <= shore + 1 || height > shore + 4) continue;
                int count = 1, read = 0, boundary = Integer.MIN_VALUE;
                boolean isolated = true;
                group[0] = p;
                while (read < count && isolated) {
                    int current = group[read++], cx = current % side, cz = current / side;
                    if (cx <= 8 || cz <= 8 || cx >= side - 9 || cz >= side - 9) {
                        isolated = false;
                        break;
                    }
                    for (int dz = -3; dz <= 3; dz++)
                        for (int dx = -3; dx <= 3; dx++)
                            if (Math.abs(dx) + Math.abs(dz) <= 3)
                                shore = Math.max(shore, water[current + dz * side + dx]);
                    for (int offset : offsets) {
                        int q = current + offset;
                        if (original[q] > height) {
                            isolated = false;
                            break;
                        }
                        if (original[q] != height) {
                            boundary = Math.max(boundary, original[q]);
                            continue;
                        }
                        if (water[q] != PreparedWaterArea.NONE) {
                            isolated = false;
                            break;
                        }
                        boolean found = false;
                        for (int i = 0; i < count; i++) if (group[i] == q) found = true;
                        if (!found) {
                            if (count == group.length) {
                                isolated = false;
                                break;
                            }
                            group[count++] = q;
                        }
                    }
                }
                // Only remove a one-block nub, never cut a cliff or open a dry wall below water.
                if (isolated && boundary == height - 1) {
                    int target = Math.max(shore + 1, height - 1);
                    for (int i = 0; i < count; i++) air[group[i]] = Math.min(air[group[i]], target);
                }
            }
    }
}

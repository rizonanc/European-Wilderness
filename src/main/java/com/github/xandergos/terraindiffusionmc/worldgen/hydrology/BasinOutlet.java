package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

public record BasinOutlet(
        long canonId,
        int worldX,
        int worldZ,
        int waterY,
        int bedY,
        long accumulation,
        int channelWidth,
        int depth,
        int bankWidth) {
    public BasinOutlet {
        if (waterY < 63) {
            throw new IllegalArgumentException("waterY " + waterY + " below sea level 63");
        }

        if (accumulation < 1L) {
            throw new IllegalArgumentException("accumulation must be >= 1, got " + accumulation);
        }

        if (channelWidth != DrainagePlanner.channelWidthBlocks(accumulation)) {
            throw new IllegalArgumentException(
                    "channelWidth " + channelWidth + " must equal the G1a budget for accumulation " + accumulation);
        }

        if (depth != DrainagePlanner.channelDepthBlocks(accumulation)) {
            throw new IllegalArgumentException(
                    "depth " + depth + " must equal the G1a budget for accumulation " + accumulation);
        }

        if (bankWidth != RiverRasterizer.bankWidthBlocks(accumulation)) {
            throw new IllegalArgumentException(
                    "bankWidth " + bankWidth + " must equal the G1b budget for accumulation " + accumulation);
        }

        if (bedY >= waterY) {
            throw new IllegalArgumentException("bedY " + bedY + " must be below waterY " + waterY);
        }

        if (depth > 0 && bedY > Math.subtractExact(waterY, depth)) {
            throw new IllegalArgumentException("bedY " + bedY + " must be at most waterY - depth for a channel");
        }
    }

    public static BasinOutlet create(
            long seed, int worldX, int worldZ, int filledY, long accumulation, int generatorVersion) {
        if (generatorVersion <= 0) {
            throw new IllegalArgumentException("generatorVersion must be positive, got " + generatorVersion);
        }

        if (accumulation < 1L) {
            throw new IllegalArgumentException("accumulation must be >= 1, got " + accumulation);
        }

        int water = Math.max(filledY, 63);
        int width = DrainagePlanner.channelWidthBlocks(accumulation);
        int depth = DrainagePlanner.channelDepthBlocks(accumulation);
        int bank = RiverRasterizer.bankWidthBlocks(accumulation);
        int bed = Math.min(Math.subtractExact(filledY, 1), Math.subtractExact(water, depth));
        long id = WatershedPlan.basinIdOf(seed, worldX, worldZ, generatorVersion);
        return new BasinOutlet(id, worldX, worldZ, water, bed, accumulation, width, depth, bank);
    }

    public void verifyStepTo(int downstreamWaterY) {
        if (downstreamWaterY > this.waterY) {
            throw new IllegalArgumentException("water must not rise: " + this.waterY + " -> " + downstreamWaterY);
        }

        if (Math.subtractExact(this.waterY, downstreamWaterY) > 2) {
            throw new IllegalArgumentException(
                    "water drop exceeds the fixed rapid step: " + this.waterY + " -> " + downstreamWaterY);
        }
    }

    public static void verifyProfile(int[] waterDownstream, int seaLevelY) {
        if (waterDownstream != null && waterDownstream.length != 0) {
            for (int w : waterDownstream) {
                if (w < seaLevelY) {
                    throw new IllegalArgumentException("water " + w + " below sea level " + seaLevelY);
                }
            }

            for (int i = 1; i < waterDownstream.length; i++) {
                int upstream = waterDownstream[i - 1];
                int downstream = waterDownstream[i];
                BasinOutlet probe = new BasinOutlet(0L, 0, 0, upstream, Math.subtractExact(upstream, 1), 9L, 1, 1, 1);
                probe.verifyStepTo(downstream);
            }
        } else {
            throw new IllegalArgumentException("waterDownstream must be non-empty");
        }
    }

    public boolean terminatesAtSea() {
        return this.waterY == 63;
    }
}

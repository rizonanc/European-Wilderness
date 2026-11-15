package com.github.xandergos.terraindiffusionmc.worldgen.ecology;

import java.util.List;

public final class TropicalLandforms {
    public static final short PALM_ISLAND = 230;
    public static final short ISLAND_BEACH = 18;
    public static final int ISLAND_CELL = 4096;
    public static final int OASIS_CELL = 768;
    public static final int OASIS_REACH = 32;

    private TropicalLandforms() {}

    public enum Form {
        SMALL,
        SANDY,
        ROCKY
    }

    public record Island(
            int x,
            int z,
            int radius,
            int peak,
            long seed,
            Form form,
            double cos,
            double sin,
            double stretch,
            double phase,
            double bay) {
        Island(int x, int z, int radius, int peak, long seed) {
            this(x, z, radius, peak, seed, Form.SMALL, 1, 0, 1, 0, 0);
        }

        private double coastDistance(int dx, int dz) {
            double u = (dx * cos + dz * sin) / stretch;
            double v = (-dx * sin + dz * cos) * stretch;
            double angle = Math.atan2(v, u);
            double lobes = 1 + 0.19 * Math.cos(3 * angle + phase) + 0.09 * Math.sin(5 * angle - phase);
            double inlet = Math.max(0, Math.cos(angle - bay));
            double cut = (form == Form.ROCKY ? 0.48 : 0.28) * Math.pow(inlet, 10);
            return Math.hypot(u, v) / (radius * (lobes - cut));
        }

        private float largeHeight(float seabed, int blockX, int blockZ, int dx, int dz) {
            if (Math.abs(dx) >= radius * 2.5 || Math.abs(dz) >= radius * 2.5) return seabed;
            double distance = coastDistance(dx, dz);
            if (distance >= 1.49) return seabed;
            double coast = EuropeanEcology.valueNoise(seed, blockX, blockZ, 27, 86) - 0.5;
            distance /= 1 + 0.12 * coast;
            double shelf = 1 - EuropeanEcology.smoothstep(1.02, 1.39, distance);
            double hills = EuropeanEcology.valueNoise(seed, blockX, blockZ, 28, form == Form.ROCKY ? 115 : 225);
            double crest = form == Form.ROCKY
                    ? (1 - EuropeanEcology.smoothstep(0.38, 0.89, distance)) * (0.42 + 1.03 * hills)
                    : (1 - EuropeanEcology.smoothstep(0.05, 0.88, distance)) * (0.64 + 0.61 * hills);
            double profile = peak * crest - 48 * Math.min(1, distance);
            double isolation = EuropeanEcology.smoothstep(70, 270, -seabed);
            return (float) (seabed + shelf * isolation * Math.max(0, profile - seabed));
        }

        short biome(float height, float temperature, int scale, int blockX, int blockZ) {
            if (form == Form.ROCKY && height > 80F / scale) {
                double distance = coastDistance(blockX - x, blockZ - z);
                double patch = EuropeanEcology.valueNoise(seed, blockX, blockZ, 29, 76);
                if (distance + patch * 0.24 > 0.62 && distance > 0.42) return 35;
            }
            return islandBiome(height, temperature, scale);
        }

        float height(float seabed, int blockX, int blockZ) {
            if (seabed >= 0) return seabed;
            int dx = blockX - x, dz = blockZ - z;
            if (form != Form.SMALL) return largeHeight(seabed, blockX, blockZ, dx, dz);
            if (Math.abs(dx) >= radius * 1.65 || Math.abs(dz) >= radius * 1.65) return seabed;
            double outline = 0.88 + 0.24 * EuropeanEcology.valueNoise(seed, blockX, blockZ, 21, 64);
            double distance = Math.hypot(dx, dz) / (radius * outline);
            if (distance >= 1.45) return seabed;
            double shelf = 1 - EuropeanEcology.smoothstep(1.02, 1.45, distance);
            double profile = peak * (1 - EuropeanEcology.smoothstep(0.1, 0.94, distance)) - 50 * Math.min(1, distance);
            double isolation = EuropeanEcology.smoothstep(40, 220, -seabed);
            return (float) (seabed + shelf * isolation * Math.max(0, profile - seabed));
        }
    }

    public static List<Island> islands(long seed, int cellX, int cellZ) {
        var random = random(seed, cellX, cellZ, 21);
        if (random.nextDouble() >= 0.18) return List.of();
        int x = cellX * ISLAND_CELL + ISLAND_CELL / 2 + random.nextInt(-550, 551);
        int z = cellZ * ISLAND_CELL + ISLAND_CELL / 2 + random.nextInt(-550, 551);
        int radius = random.nextInt(150, 255);
        var main = new Island(x, z, radius, random.nextInt(170, 300), seed);
        if (random.nextBoolean()) return List.of(main);
        double angle = random.nextDouble() * Math.TAU;
        int separation = radius + random.nextInt(130, 205);
        int dx = (int) (Math.cos(angle) * separation), dz = (int) (Math.sin(angle) * separation);
        return List.of(
                main,
                new Island(x + dx, z + dz, random.nextInt(65, 115), random.nextInt(95, 155), seed),
                new Island(x - dz, z + dx, random.nextInt(55, 95), random.nextInt(85, 145), seed));
    }

    public static List<Island> islands(long seed, int cellX, int cellZ, boolean largeIslands) {
        var small = islands(seed, cellX, cellZ);
        if (!largeIslands || !small.isEmpty()) return small;
        var random = random(seed, cellX, cellZ, 26);
        if (random.nextDouble() >= 0.065) return List.of();

        int x = cellX * ISLAND_CELL + ISLAND_CELL / 2 + random.nextInt(-140, 141);
        int z = cellZ * ISLAND_CELL + ISLAND_CELL / 2 + random.nextInt(-140, 141);
        int radius = random.nextInt(480, 701);
        var form = random.nextBoolean() ? Form.SANDY : Form.ROCKY;
        int peak = form == Form.SANDY ? random.nextInt(300, 560) : random.nextInt(920, 1520);
        double rotation = random.nextDouble() * Math.TAU;
        var main = new Island(
                x,
                z,
                radius,
                peak,
                seed,
                form,
                Math.cos(rotation),
                Math.sin(rotation),
                random.nextDouble(1.12, 1.35),
                random.nextDouble() * Math.TAU,
                random.nextDouble() * Math.TAU);
        int count = random.nextInt(3);
        if (count == 0) return List.of(main);
        var group = new java.util.ArrayList<Island>(count + 1);
        group.add(main);
        for (int i = 0; i < count; i++) {
            double angle = rotation + i * Math.PI + random.nextDouble(-0.42, 0.42);
            int offset = random.nextInt(1200, 1510);
            group.add(new Island(
                    x + (int) (offset * Math.cos(angle)),
                    z + (int) (offset * Math.sin(angle)),
                    random.nextInt(80, 141),
                    random.nextInt(120, 275),
                    seed));
        }
        return List.copyOf(group);
    }

    public record IslandColumn(float height, short biome) {}

    public static IslandColumn islandColumn(
            List<Island> group, float seabed, int x, int z, float temperature, int scale) {
        Island winner = null;
        float height = seabed;
        for (Island island : group) {
            float raised = island.height(seabed, x, z);
            if (raised > height) {
                height = raised;
                winner = island;
            }
        }
        return winner == null ? null : new IslandColumn(height, winner.biome(height, temperature, scale, x, z));
    }

    public static float islandHeight(List<Island> group, float seabed, int x, int z) {
        float height = seabed;
        for (Island island : group) height = Math.max(height, island.height(seabed, x, z));
        return height;
    }

    public static short islandBiome(float height, float temperature, int scale) {
        if (height < 0) return (short) (temperature >= 17 ? 41 : temperature < 5 ? 46 : 44);
        if (height < 120F / scale) return ISLAND_BEACH;
        return temperature >= 17 ? PALM_ISLAND : (short) 229;
    }

    public record Oasis(int x, int z, int radius, long seed) {
        public double distance(int blockX, int blockZ) {
            double outline = 0.87 + 0.26 * EuropeanEcology.valueNoise(seed, blockX, blockZ, 22, 18);
            return Math.hypot(blockX - x, blockZ - z) / (radius * outline);
        }
    }

    public static Oasis oasis(long seed, int cellX, int cellZ) {
        var random = random(seed, cellX, cellZ, 22);
        return new Oasis(
                cellX * OASIS_CELL + random.nextInt(96, OASIS_CELL - 96),
                cellZ * OASIS_CELL + random.nextInt(96, OASIS_CELL - 96),
                random.nextInt(19, 26),
                seed);
    }

    static int oasisOffset(double distance) {
        return (int) Math.round(-5 + 10 * EuropeanEcology.smoothstep(0.18, 1, distance));
    }

    static java.util.SplittableRandom random(long seed, int x, int z, int salt) {
        long bits =
                Double.doubleToRawLongBits(EuropeanEcology.hashNoise01(x ^ (int) seed, z ^ (int) (seed >>> 32), salt));
        return new java.util.SplittableRandom(bits ^ seed);
    }
}

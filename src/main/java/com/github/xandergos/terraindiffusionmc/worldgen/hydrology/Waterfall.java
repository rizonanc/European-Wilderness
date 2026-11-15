package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

/** One cardinal, cliff-backed fall. All coordinates are world blocks. */
public record Waterfall(int x, int z, int dx, int dz, int upperY, int lowerY, int width, int run) {
    public Waterfall {
        if (Math.abs((long) dx) + Math.abs((long) dz) != 1
                || width < 3
                || width > 7
                || width % 2 == 0
                || run < 6
                || run > 16
                || lowerY < 63
                || upperY >= 4096
                || (long) upperY - lowerY < 8
                || (long) upperY - lowerY > 24) throw new IllegalArgumentException("Invalid waterfall geometry");
    }

    public long along(int wx, int wz) {
        return ((long) wx - x) * dx + ((long) wz - z) * dz;
    }

    public long across(int wx, int wz) {
        return -((long) wx - x) * dz + ((long) wz - z) * dx;
    }

    public int radius() {
        return width / 2;
    }

    public int margin() {
        return radius() + 5;
    }

    public int start() {
        return -4;
    }

    public int end() {
        return run + 4;
    }

    public boolean contains(int wx, int wz) {
        long s = along(wx, wz);
        return s >= start() && s <= end() && Math.abs(across(wx, wz)) <= margin();
    }

    public int wetRadius(int s) {
        if (s <= 0) return radius() + (-s) / 2;
        // Open into a rounded impact bowl, then retain a broad outlet.
        if (s == 1) return radius() + 1;
        if (s <= 6) return radius() + 3 - Math.abs(s - 3) / 2;
        return radius() + 2;
    }

    public boolean rockColumn(int wx, int wz) {
        return contains(wx, wz) && (along(wx, wz) <= 0 || Math.abs(across(wx, wz)) > wetRadius((int) along(wx, wz)));
    }

    public boolean curtain(int wx, int wz) {
        return along(wx, wz) == 1 && Math.abs(across(wx, wz)) <= radius();
    }

    public int worldX(int s, int t) {
        return Math.toIntExact((long) x + (long) dx * s - (long) dz * t);
    }

    public int worldZ(int s, int t) {
        return Math.toIntExact((long) z + (long) dz * s + (long) dx * t);
    }

    public boolean inside(int ax, int az, int side) {
        for (int s : new int[] {start(), end()})
            for (int t : new int[] {-margin(), margin()}) {
                long wx = (long) x + (long) dx * s - (long) dz * t, wz = (long) z + (long) dz * s + (long) dx * t;
                if (wx < (long) ax + PreparedWaterArea.COLLAR
                        || wz < (long) az + PreparedWaterArea.COLLAR
                        || wx >= (long) ax + side - PreparedWaterArea.COLLAR
                        || wz >= (long) az + side - PreparedWaterArea.COLLAR) return false;
            }
        return true;
    }
}

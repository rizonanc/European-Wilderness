package com.github.xandergos.terraindiffusionmc.explorer;

public final class ReliefMap {
    private ReliefMap() {}

    public static float[][] getReliefMap(float[] elev, int H, int W, double resolution) {
        float[] elevF = replaceNaN(elev, H, W);
        float[] elevLarge = gaussianBlur(elevF, H, W, 6.0F);
        float[] elevSmall = gaussianBlur(elevF, H, W, 1.2F);
        float[] hsLarge = hillshade(elevLarge, H, W, 315.0, 45.0, resolution);
        float[] hsSmall = hillshade(elevSmall, H, W, 315.0, 45.0, resolution);
        float[] hs = new float[H * W];

        for (int i = 0; i < hs.length; i++) {
            hs[i] = (float) Math.pow(Math.min(1.0F, Math.max(0.0F, 0.75F * hsLarge[i] + 0.25F * hsSmall[i])), 0.85);
        }

        float landMin = 0.0F;
        float landMax = 0.0F;

        for (float v : elevF) {
            float land = Math.max(0.0F, v);
            if (land > landMax) {
                landMax = land;
            }
        }

        float span = landMax - landMin + 1.0E-8F;
        float[] r = new float[H * W];
        float[] g = new float[H * W];
        float[] b = new float[H * W];

        for (int i = 0; i < H * W; i++) {
            float land = Math.max(0.0F, elevF[i]);
            float norm = (land - landMin) / span;
            float normCmap = 0.25F + (float) Math.pow(Math.min(1.0F, Math.max(0.0F, norm)), 0.7) * 0.75F;
            float[] rgb = Colormaps.terrain(normCmap);
            r[i] = rgb[0];
            g[i] = rgb[1];
            b[i] = rgb[2];
        }

        for (int i = 0; i < H * W; i++) {
            float intensity = 0.35F + 0.65F * hs[i];
            r[i] = Math.min(1.0F, Math.max(0.0F, r[i] * intensity));
            g[i] = Math.min(1.0F, Math.max(0.0F, g[i] * intensity));
            b[i] = Math.min(1.0F, Math.max(0.0F, b[i] * intensity));
        }

        for (int i = 0; i < H * W; i++) {
            float e = elevF[i];
            if (e < 0.0F) {
                float depth = -e;
                float t = (float) Math.pow(Math.min(1.0F, depth / 10000.0F), 0.7);
                r[i] = (1.0F - t) * 0.68F + t * 0.0F;
                g[i] = (1.0F - t) * 0.88F + t * 0.1F;
                b[i] = (1.0F - t) * 1.0F + t * 0.45F;
            }
        }

        return new float[][] {r, g, b};
    }

    private static float[] hillshade(
            float[] src, int H, int W, double azimuthDeg, double altitudeDeg, double resolution) {
        double[] dy = new double[H * W];
        double[] dx = new double[H * W];
        double divisor = 15.0 * resolution / 90.0;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                if (r == 0) {
                    dy[idx] = src[(r + 1) * W + c] - src[r * W + c];
                } else if (r == H - 1) {
                    dy[idx] = src[r * W + c] - src[(r - 1) * W + c];
                } else {
                    dy[idx] = (src[(r + 1) * W + c] - src[(r - 1) * W + c]) / 2.0;
                }

                if (c == 0) {
                    dx[idx] = src[r * W + c + 1] - src[r * W + c];
                } else if (c == W - 1) {
                    dx[idx] = src[r * W + c] - src[r * W + (c - 1)];
                } else {
                    dx[idx] = (src[r * W + c + 1] - src[r * W + (c - 1)]) / 2.0;
                }

                dy[idx] /= divisor;
                dx[idx] /= divisor;
            }
        }

        double azRad = Math.toRadians(azimuthDeg);
        double altRad = Math.toRadians(altitudeDeg);
        float[] hs = new float[H * W];

        for (int i = 0; i < H * W; i++) {
            double slopeRad = (Math.PI / 2) - Math.atan(Math.hypot(dx[i], dy[i]));
            double aspectRad = Math.atan2(dy[i], -dx[i]);
            double val =
                    Math.sin(altRad) * Math.sin(slopeRad)
                            + Math.cos(altRad) * Math.cos(slopeRad) * Math.cos(azRad - aspectRad);
            hs[i] = (float) Math.min(1.0, Math.max(0.0, val));
        }

        return hs;
    }

    static float[] gaussianBlur(float[] src, int H, int W, float sigma) {
        int radius = Math.max(1, (int) Math.ceil(3.0F * sigma));
        float[] kernel = makeGaussianKernel(sigma, radius);
        float[] tmp = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float sum = 0.0F;
                float weight = 0.0F;

                for (int k = -radius; k <= radius; k++) {
                    int cc = Math.min(W - 1, Math.max(0, c + k));
                    float w = kernel[k + radius];
                    sum += src[r * W + cc] * w;
                    weight += w;
                }

                tmp[r * W + c] = sum / weight;
            }
        }

        float[] out = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float sum = 0.0F;
                float weight = 0.0F;

                for (int k = -radius; k <= radius; k++) {
                    int rr = Math.min(H - 1, Math.max(0, r + k));
                    float w = kernel[k + radius];
                    sum += tmp[rr * W + c] * w;
                    weight += w;
                }

                out[r * W + c] = sum / weight;
            }
        }

        return out;
    }

    private static float[] makeGaussianKernel(float sigma, int radius) {
        int size = 2 * radius + 1;
        float[] k = new float[size];
        float s2 = 2.0F * sigma * sigma;

        for (int i = 0; i < size; i++) {
            int d = i - radius;
            k[i] = (float) Math.exp(-(d * d) / s2);
        }

        return k;
    }

    private static float[] replaceNaN(float[] src, int H, int W) {
        float[] out = (float[]) src.clone();
        float sum = 0.0F;
        int count = 0;

        for (float v : out) {
            if (!Float.isNaN(v) && Float.isFinite(v)) {
                sum += v;
                count++;
            }
        }

        float median = count > 0 ? sum / count : 0.0F;

        for (int i = 0; i < out.length; i++) {
            if (Float.isNaN(out[i])) {
                out[i] = median;
            }
        }

        return out;
    }
}

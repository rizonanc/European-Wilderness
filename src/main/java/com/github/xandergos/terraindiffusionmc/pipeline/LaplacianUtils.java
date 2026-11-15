package com.github.xandergos.terraindiffusionmc.pipeline;

public final class LaplacianUtils {
    public static float[][] laplacianDecode(float[][] residual, float[][] lowres) {
        int H = residual.length;
        int W = residual[0].length;
        float[][] lowresUp = bilinearResize(lowres, H, W);
        float[][] result = new float[H][W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                result[r][c] = residual[r][c] + lowresUp[r][c];
            }
        }

        return result;
    }

    public static float[][] laplacianDenoise(float[][] residual, float[][] lowres, float sigma) {
        int H = residual.length;
        int W = residual[0].length;
        int lH = lowres.length;
        int lW = lowres[0].length;
        float[][] lowresUpEx = bilinearResizeExtrapolated(lowres, H, W);
        float[][] decoded = new float[H][W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                decoded[r][c] = residual[r][c] + lowresUpEx[r][c];
            }
        }

        float[][] downsampled = bilinearResize(decoded, lH, lW);
        float[][] kernelSigma = gaussianKernel1D(sigma);
        return separableGaussianBlur(downsampled, kernelSigma);
    }

    /** Phase 4: moved from WorldPipeline unchanged so tests can load it without model config. */
    public static float[] nearestUpsample(float[] src, int C, int sH, int sW, int dH, int dW) {
        float[] dst = new float[C * dH * dW];
        nearestUpsampleInto(src, C, sH, sW, dH, dW, dst);
        return dst;
    }

    /** In-place {@link #nearestUpsample}: identical arithmetic into {@code dst}. */
    public static void nearestUpsampleInto(float[] src, int C, int sH, int sW, int dH, int dW, float[] dst) {
        for (int c = 0; c < C; c++) {
            for (int r = 0; r < dH; r++) {
                int sr = r * sH / dH;

                for (int col = 0; col < dW; col++) {
                    dst[c * dH * dW + r * dW + col] = src[c * sH * sW + sr * sW + col * sW / dW];
                }
            }
        }
    }

    public static float[][] bilinearResize(float[][] src, int dstH, int dstW) {
        int srcH = src.length;
        int srcW = src[0].length;
        float[][] dst = new float[dstH][dstW];

        for (int r = 0; r < dstH; r++) {
            float srcR = (r + 0.5F) * srcH / dstH - 0.5F;
            int r0 = (int) Math.floor(srcR);
            int r1 = r0 + 1;
            float wr = srcR - r0;
            r0 = Math.max(0, Math.min(srcH - 1, r0));
            r1 = Math.max(0, Math.min(srcH - 1, r1));

            for (int c = 0; c < dstW; c++) {
                float srcC = (c + 0.5F) * srcW / dstW - 0.5F;
                int c0 = (int) Math.floor(srcC);
                int c1 = c0 + 1;
                float wc = srcC - c0;
                c0 = Math.max(0, Math.min(srcW - 1, c0));
                c1 = Math.max(0, Math.min(srcW - 1, c1));
                dst[r][c] =
                        (1.0F - wr) * (1.0F - wc) * src[r0][c0]
                                + (1.0F - wr) * wc * src[r0][c1]
                                + wr * (1.0F - wc) * src[r1][c0]
                                + wr * wc * src[r1][c1];
            }
        }

        return dst;
    }

    /** Same global resize coordinates and float operation order, materializing only a clamped crop. */
    public static float[] bilinearResizeRoi(
            float[][] src, int dstH, int dstW, int row, int column, int height, int width) {
        if (height < 0 || width < 0) throw new IllegalArgumentException("Negative ROI size");
        var out = new float[Math.multiplyExact(height, width)];
        bilinearResizeRoiInto(src, dstH, dstW, row, column, height, width, out, 0);
        return out;
    }

    public static void bilinearResizeRoiInto(
            float[][] src, int dstH, int dstW, int row, int column, int height, int width, float[] out, int offset) {
        if (src == null
                || src.length == 0
                || src[0] == null
                || src[0].length == 0
                || dstH < 1
                || dstW < 1
                || height < 0
                || width < 0) throw new IllegalArgumentException("Invalid resize ROI dimensions");
        if (out == null || offset < 0 || (long) offset + (long) height * width > out.length)
            throw new IllegalArgumentException("ROI destination bounds");
        int srcH = src.length, srcW = src[0].length;
        for (var values : src)
            if (values == null || values.length != srcW || values == out)
                throw new IllegalArgumentException("Rectangular non-aliased source required");
        int[] left = new int[width], right = new int[width];
        float[] weights = new float[width];
        for (int c = 0; c < width; c++) {
            int global = (int) Math.max(0L, Math.min(dstW - 1L, (long) column + c));
            float srcC = (global + 0.5F) * srcW / dstW - 0.5F;
            int c0 = (int) Math.floor(srcC);
            weights[c] = srcC - c0;
            left[c] = Math.max(0, Math.min(srcW - 1, c0));
            right[c] = Math.max(0, Math.min(srcW - 1, c0 + 1));
        }
        for (int r = 0; r < height; r++) {
            int global = (int) Math.max(0L, Math.min(dstH - 1L, (long) row + r));
            float srcR = (global + 0.5F) * srcH / dstH - 0.5F;
            int r0 = (int) Math.floor(srcR), r1 = r0 + 1;
            float wr = srcR - r0;
            r0 = Math.max(0, Math.min(srcH - 1, r0));
            r1 = Math.max(0, Math.min(srcH - 1, r1));
            for (int c = 0; c < width; c++) {
                float wc = weights[c];
                int c0 = left[c], c1 = right[c];
                out[offset + r * width + c] =
                        (1.0F - wr) * (1.0F - wc) * src[r0][c0]
                                + (1.0F - wr) * wc * src[r0][c1]
                                + wr * (1.0F - wc) * src[r1][c0]
                                + wr * wc * src[r1][c1];
            }
        }
    }

    static float[][] bilinearResizeExtrapolated(float[][] src, int dstH, int dstW) {
        int sH = src.length;
        int sW = src[0].length;
        float[][] padded = new float[sH + 2][sW + 2];

        for (int r = 0; r < sH; r++) {
            for (int c = 0; c < sW; c++) {
                padded[r + 1][c + 1] = src[r][c];
            }
        }

        for (int c = 1; c <= sW; c++) {
            padded[0][c] = sH > 1 ? 2.0F * src[0][c - 1] - src[1][c - 1] : src[0][c - 1];
            padded[sH + 1][c] = sH > 1 ? 2.0F * src[sH - 1][c - 1] - src[sH - 2][c - 1] : src[sH - 1][c - 1];
        }

        for (int r = 0; r < sH + 2; r++) {
            padded[r][0] = sW > 1 ? 2.0F * padded[r][1] - padded[r][2] : padded[r][1];
            padded[r][sW + 1] = sW > 1 ? 2.0F * padded[r][sW] - padded[r][sW - 1] : padded[r][sW];
        }

        int newH = (int) Math.round(dstH + 2.0 * dstH / sH);
        int newW = (int) Math.round(dstW + 2.0 * dstW / sW);
        float[][] resized = bilinearResize(padded, newH, newW);
        int padH = (int) Math.round((double) dstH / sH);
        int padW = (int) Math.round((double) dstW / sW);
        float[][] cropped = new float[dstH][dstW];

        for (int r = 0; r < dstH; r++) {
            for (int c = 0; c < dstW; c++) {
                cropped[r][c] = resized[r + padH][c + padW];
            }
        }

        return cropped;
    }

    public static float[][] gaussianKernel1D(float sigma) {
        int ks = (int) (sigma * 2.0F) / 2 * 2 + 1;
        float[] k = new float[ks];
        float sum = 0.0F;
        int half = ks / 2;

        for (int i = 0; i < ks; i++) {
            float x = i - half;
            k[i] = (float) Math.exp(-0.5 * x * x / (sigma * sigma));
            sum += k[i];
        }

        for (int i = 0; i < ks; i++) {
            k[i] /= sum;
        }

        return new float[][] {k};
    }

    public static float[][] separableGaussianBlur(float[][] src, float[][] kernel1D) {
        float[] k = kernel1D[0];
        int ks = k.length;
        int pad = ks / 2;
        int H = src.length;
        int W = src[0].length;
        float[][] tmp = new float[H][W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float sum = 0.0F;

                for (int ki = 0; ki < ks; ki++) {
                    int cc = Math.max(0, Math.min(W - 1, c + ki - pad));
                    sum += src[r][cc] * k[ki];
                }

                tmp[r][c] = sum;
            }
        }

        float[][] result = new float[H][W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float sum = 0.0F;

                for (int ki = 0; ki < ks; ki++) {
                    int rr = Math.max(0, Math.min(H - 1, r + ki - pad));
                    sum += tmp[rr][c] * k[ki];
                }

                result[r][c] = sum;
            }
        }

        return result;
    }

    public static float[][][] localBaselineTemperature(float[][] T, float[][] e, int win, float fallbackThreshold) {
        int H = T.length;
        int W = T[0].length;
        int outH = H - win + 1;
        int outW = W - win + 1;
        float[][][] result = new float[2][outH][outW];
        float fallbackBeta = -0.0065F;
        float betaMin = -0.012F;
        float betaMax = 0.0F;
        float eps = 1.0E-6F;

        for (int r = 0; r < outH; r++) {
            for (int c = 0; c < outW; c++) {
                double muT = 0.0;
                double muE = 0.0;
                double muE2 = 0.0;
                double muET = 0.0;
                double sumW = 0.0;
                int n = win * win;

                for (int dr = 0; dr < win; dr++) {
                    for (int dc = 0; dc < win; dc++) {
                        float land = e[r + dr][c + dc] > 0.0F ? 1.0F : 0.0F;
                        muT += T[r + dr][c + dc] * land;
                        muE += e[r + dr][c + dc] * land;
                        muE2 += e[r + dr][c + dc] * e[r + dr][c + dc] * land;
                        muET += e[r + dr][c + dc] * T[r + dr][c + dc] * land;
                        sumW += land;
                    }
                }

                double den = sumW + eps;
                muT /= den;
                muE /= den;
                muE2 /= den;
                muET /= den;
                double varE = muE2 - muE * muE;
                double covET = muET - muE * muT;
                double beta = !(varE < 1.0) && !(sumW < fallbackThreshold * n) ? covET / (varE + eps) : fallbackBeta;
                beta = Math.max(betaMin, Math.min(betaMax, beta));
                int pad = (win - 1) / 2;
                float Tc = T[r + pad][c + pad];
                float ec = e[r + pad][c + pad];
                result[0][r][c] = (float) (Tc - beta * ec);
                result[1][r][c] = (float) beta;
            }
        }

        return result;
    }
}

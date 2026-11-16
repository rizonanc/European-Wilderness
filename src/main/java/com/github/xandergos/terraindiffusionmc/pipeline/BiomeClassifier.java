package com.github.xandergos.terraindiffusionmc.pipeline;

public final class BiomeClassifier {
    private static final FastNoiseLite TEMP_NOISE = makeFnl(12345, 0.002F, 3, 2.0F, 0.5F);
    private static final FastNoiseLite TEMP_NOISE_FINE = makeFnl(54321, 0.0078125F, 2, 2.0F, 0.5F);
    private static final FastNoiseLite PRECIP_NOISE = makeFnl(12345, 0.002F, 5, 2.0F, 0.5F);
    private static final FastNoiseLite SNOW_NOISE = makeFnl(12345, 0.002F, 3, 2.0F, 0.5F);
    private static final FastNoiseLite SNOW_NOISE_FINE = makeFnl(54321, 0.0078125F, 2, 2.0F, 0.5F);
    static final short PLAINS = 1;
    static final short SNOWY_PLAINS = 3;
    static final short DESERT = 5;
    static final short SWAMP = 6;
    static final short FOREST = 8;
    static final short TAIGA = 15;
    static final short SNOWY_TAIGA = 16;
    static final short SAVANNA = 17;
    static final short WINDSWEPT_HILLS = 19;
    static final short JUNGLE = 23;
    static final short BADLANDS = 26;
    static final short MEADOW = 29;
    static final short GROVE = 31;
    static final short SNOWY_SLOPES = 32;
    static final short FROZEN_PEAKS = 33;
    static final short STONY_PEAKS = 35;
    static final short WARM_OCEAN = 41;
    static final short OCEAN = 44;
    static final short COLD_OCEAN = 46;
    static final short FROZEN_OCEAN = 48;
    static final short FOREST_SPARSE = 108;
    static final short TAIGA_SPARSE = 115;
    static final short SNOWY_TAIGA_SPARSE = 116;

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static short[] classify(
            float[] elev, float[] climate, int i0, int j0, float[] elevPadded, int H, int W, float pixelSizeM) {
        short[] out = new short[H * W];

        for (int i = 0; i < H * W; i++) {
            out[i] = 1;
        }

        if (climate != null && climate.length >= 4 * H * W) {
            float[] tempNoise = new float[H * W];
            float[] precipNoiseFact = new float[H * W];
            float[] snowNoise = new float[H * W];

            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    float nx = j0 + c;
                    float ny = i0 + r;
                    float tnc = TEMP_NOISE.GetNoise(nx, ny);
                    float tnf = TEMP_NOISE_FINE.GetNoise(nx, ny);
                    tempNoise[idx] = 0.4F * tnc + 0.2F * tnf;
                    float pn = PRECIP_NOISE.GetNoise(nx, ny);
                    precipNoiseFact[idx] = 1.0F + 0.2F * pn;
                    float snc = SNOW_NOISE.GetNoise(nx, ny);
                    float snf = SNOW_NOISE_FINE.GetNoise(nx, ny);
                    snowNoise[idx] = 3.0F * snc + 2.0F * snf;
                }
            }

            float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);

            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    float elevVal = elev[idx];
                    float altM = Math.max(0.0F, elevVal);
                    float slope = slopeRatio[idx];
                    float temp = climate[idx] + tempNoise[idx];
                    float tSeason = climate[H * W + idx];
                    float precip = Math.max(0.0F, climate[2 * H * W + idx]) * precipNoiseFact[idx];
                    float pCV = climate[3 * H * W + idx];
                    float tStd = tSeason / 100.0F;
                    float tEff = Math.max(0.0F, temp + 0.5F * tStd);
                    float pet = Math.max(250.0F, 250.0F + 25.0F * tEff + 0.7F * tEff * tEff);
                    float aridity = precip / Math.max(1.0F, pet);
                    float seasonPenalty = 1.0F - 0.35F * Math.min(1.0F, pCV / 100.0F);
                    float treeMoisture = aridity * seasonPenalty;
                    float amplitude = tStd * 1.414F;
                    float growingSeason;
                    if (amplitude < 0.1F) {
                        growingSeason = temp > 5.0F ? 365.0F : 0.0F;
                    } else {
                        float x = (5.0F - temp) / amplitude;
                        if (x <= -1.0F) {
                            growingSeason = 365.0F;
                        } else if (x >= 1.0F) {
                            growingSeason = 0.0F;
                        } else {
                            growingSeason = 365.0F
                                    * (0.5F - (float) Math.asin(Math.max(-1.0F, Math.min(1.0F, x))) / (float) Math.PI);
                        }
                    }

                    float gsFactor = Math.max(0.0F, Math.min(1.0F, (growingSeason - 60.0F) / 90.0F));
                    float effTreeMoisture = treeMoisture * gsFactor;
                    float moistureFactor = Math.max(0.0F, Math.min(1.0F, (treeMoisture - 0.35F) / 0.45F));
                    float bareThreshold = 0.7F + 0.49000007F * moistureFactor;
                    boolean treesNone = effTreeMoisture < 0.2F;
                    boolean tooArid = treeMoisture < 0.05F;
                    boolean tooCold = growingSeason < 60.0F;
                    boolean barren = tooArid || tooCold;
                    boolean treesSparse = !treesNone && effTreeMoisture < 0.5F;
                    boolean treesForest = !treesNone && effTreeMoisture >= 0.5F && effTreeMoisture < 0.8F;
                    boolean treesDense = !treesNone && effTreeMoisture >= 0.8F && effTreeMoisture < 1.3F;
                    boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3F;
                    boolean slopeMedium = slope >= 0.62F && slope < bareThreshold;
                    boolean slopeBare = slope >= bareThreshold;
                    if (slopeMedium) {
                        if (treesForest || treesDense || treesRainforest) {
                            treesSparse = true;
                        }

                        if (treesForest) {}

                        treesForest = false;
                        treesDense = false;
                        treesRainforest = false;
                    }

                    if (slopeBare) {
                        treesNone = true;
                        treesSparse = false;
                        treesForest = false;
                        treesDense = false;
                        treesRainforest = false;
                    }

                    float snowTemp = temp + snowNoise[idx];
                    boolean isSteep = slope > 0.78F;
                    boolean hasSnow = snowTemp < 0.0F && precip > 150.0F && !isSteep;
                    boolean isOcean = elevVal < 0.0F;
                    boolean mountains = altM > 2500.0F;
                    boolean lowland = altM < 200.0F;
                    boolean frozen = temp < -5.0F;
                    boolean cold = temp >= -5.0F && temp < 5.0F;
                    boolean cool = temp >= 5.0F && temp < 12.0F;
                    boolean temperate = temp >= 12.0F && temp < 20.0F;
                    boolean warm = temp >= 20.0F && temp < 26.0F;
                    boolean hot = temp >= 26.0F;
                    short biome = 1;
                    byte var72;
                    if (isOcean) {
                        if (frozen) {
                            var72 = 48;
                        } else if (cold) {
                            var72 = 46;
                        } else if (!warm && !hot) {
                            var72 = 44;
                        } else {
                            var72 = 41;
                        }
                    } else if (mountains) {
                        if (slopeBare) {
                            var72 = (byte) (hasSnow ? 33 : 35);
                        } else if (hasSnow) {
                            if (treesNone) {
                                var72 = 32;
                            } else if (!treesSparse && !treesForest) {
                                var72 = 16;
                            } else {
                                var72 = 116;
                            }
                        } else if (treesNone) {
                            if (barren) {
                                var72 = 19;
                            } else if (!(treeMoisture < 0.35F) && !(precip < 350.0F)) {
                                var72 = 1;
                            } else {
                                var72 = 31;
                            }
                        } else if (!treesSparse && !treesForest) {
                            var72 = 15;
                        } else {
                            var72 = 115;
                        }
                    } else if (hasSnow && treesNone) {
                        var72 = 3;
                    } else if (hasSnow) {
                        var72 = (byte) (!treesSparse && !treesForest ? 16 : 116);
                    } else if (treesNone) {
                        if (!warm && !hot) {
                            if (!barren || lowland || !cold && !cool && !temperate) {
                                if (!(treeMoisture < 0.35F) && !(precip < 350.0F)) {
                                    var72 = 1;
                                } else {
                                    var72 = 31;
                                }
                            } else {
                                var72 = 31;
                            }
                        } else {
                            var72 = 5;
                        }
                    } else if (!treesSparse && !treesForest) {
                        if (treesDense) {
                            if (hot) {
                                var72 = 23;
                            } else if (warm && lowland) {
                                var72 = 6;
                            } else if (!cool && !cold) {
                                var72 = 8;
                            } else {
                                var72 = 15;
                            }
                        } else if (!hot && (!warm || !(temp >= 18.0F) || !(tStd < 5.0F))) {
                            if (lowland) {
                                var72 = 6;
                            } else if (!cool && !cold) {
                                var72 = 8;
                            } else {
                                var72 = 15;
                            }
                        } else {
                            var72 = 23;
                        }
                    } else if (hot) {
                        var72 = 23;
                    } else if (warm && treesSparse && !slopeMedium) {
                        var72 = 17;
                    } else if (warm && treesForest) {
                        var72 = 108;
                    } else if (temperate) {
                        var72 = 108;
                    } else {
                        var72 = 115;
                    }

                    if (slopeBare && !isOcean && !mountains) {
                        var72 = (byte) (hasSnow ? 33 : 35);
                    }

                    out[idx] = var72;
                }
            }

            return out;
        } else {
            return out;
        }
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        float[] slope = new float[H * W];
        int PW = W + 2;
        float[] sx = new float[] {-1.0F, 0.0F, 1.0F, -2.0F, 0.0F, 2.0F, -1.0F, 0.0F, 1.0F};
        float[] sy = new float[] {-1.0F, -2.0F, -1.0F, 0.0F, 0.0F, 0.0F, 1.0F, 2.0F, 1.0F};

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0.0F;
                float dy = 0.0F;

                for (int kr = 0; kr < 3; kr++) {
                    for (int kc = 0; kc < 3; kc++) {
                        float v = elevPadded[(r + kr) * PW + c + kc];
                        dx += v * sx[kr * 3 + kc];
                        dy += v * sy[kr * 3 + kc];
                    }
                }

                dx /= 8.0F;
                dy /= 8.0F;
                slope[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy) / pixelSizeM;
            }
        }

        return slope;
    }
}

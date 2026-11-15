package com.github.xandergos.terraindiffusionmc.worldgen.settings;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class GenerationProfile {
    public static final int FORMAT_VERSION = 2;
    public static final int GENERATOR_VERSION = 8;
    public static final int MIN_REALISM = 0;
    public static final int MAX_REALISM = 7;
    public static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 6;
    public static final String REGION_EUROPE = "europe";
    private static final Set<String> SUPPORTED_REGIONS = Set.of("europe");
    private final int realismLevel;
    private final int worldScale;
    private final String region;
    private final int formatVersion;
    private final int generatorVersion;
    private final String modelFingerprint;
    private final String inferenceDevice;
    private final String createdBackend;
    private final int mountainRelief;
    private final int biomeVariety;
    private final WaterGenerationOptions water;
    private final boolean largerLandscapes;
    private final BiomeBalance biomeBalance;
    private final boolean tropicalLandforms;
    private final boolean largeIslands;
    private final Optional<Integer> climateShift;
    private final boolean slopeClimate;
    private final boolean newLandforms;

    private GenerationProfile(
            int realismLevel,
            int worldScale,
            String region,
            int formatVersion,
            int generatorVersion,
            String modelFingerprint,
            String inferenceDevice,
            String createdBackend,
            int mountainRelief,
            int biomeVariety,
            WaterGenerationOptions water,
            boolean largerLandscapes,
            BiomeBalance biomeBalance,
            boolean tropicalLandforms,
            boolean largeIslands,
            Optional<Integer> climateShift,
            boolean slopeClimate,
            boolean newLandforms) {
        if (mountainRelief < 0 || mountainRelief > 200 || biomeVariety < 0 || biomeVariety > 100)
            throw new IllegalArgumentException("Invalid terrain preferences");
        this.mountainRelief = mountainRelief;
        this.biomeVariety = biomeVariety;
        this.water = Objects.requireNonNull(water);
        this.largerLandscapes = largerLandscapes;
        this.biomeBalance = Objects.requireNonNull(biomeBalance);
        this.tropicalLandforms = tropicalLandforms;
        this.largeIslands = largeIslands;
        this.climateShift = climateShift;
        this.slopeClimate = slopeClimate;
        this.newLandforms = newLandforms;
        if (realismLevel < 0 || realismLevel > 7) {
            throw new IllegalArgumentException("realismLevel must be 0..7, got " + realismLevel);
        }

        if (worldScale >= 1 && worldScale <= 6) {
            if (region == null || !SUPPORTED_REGIONS.contains(region.toLowerCase())) {
                throw new IllegalArgumentException(
                        "Unsupported region: " + region + " (supported: " + SUPPORTED_REGIONS + ")");
            }

            if (formatVersion != 2) {
                throw new IllegalArgumentException(
                        "Unsupported profile format version: " + formatVersion + " (this build reads 2)");
            }

            if (generatorVersion != 8) {
                throw new IllegalArgumentException(
                        "Unsupported generator version: " + generatorVersion + " (this build runs 8)");
            }

            if (modelFingerprint == null || modelFingerprint.isBlank()) {
                throw new IllegalArgumentException("modelFingerprint must be a non-blank model identity");
            }

            if (inferenceDevice == null || inferenceDevice.isBlank()) {
                throw new IllegalArgumentException("inferenceDevice must be a non-blank device policy");
            }

            if (createdBackend != null && !createdBackend.isBlank()) {
                this.realismLevel = realismLevel;
                this.worldScale = worldScale;
                this.region = region.toLowerCase();
                this.formatVersion = formatVersion;
                this.generatorVersion = generatorVersion;
                this.modelFingerprint = modelFingerprint;
                this.inferenceDevice = inferenceDevice.trim().toLowerCase();
                this.createdBackend = createdBackend;
            } else {
                throw new IllegalArgumentException("createdBackend must be non-blank (use \"unknown\" if unresolved)");
            }
        } else {
            throw new IllegalArgumentException("worldScale must be 1..6, got " + worldScale);
        }
    }

    public static GenerationProfile of(
            int realismLevel,
            int worldScale,
            String region,
            int formatVersion,
            int generatorVersion,
            String modelFingerprint,
            String inferenceDevice,
            String createdBackend) {
        return new GenerationProfile(
                realismLevel,
                worldScale,
                region,
                formatVersion,
                generatorVersion,
                modelFingerprint,
                inferenceDevice,
                createdBackend,
                100,
                0,
                WaterGenerationOptions.DEFAULT,
                false,
                BiomeBalance.DEFAULT,
                false,
                false,
                Optional.empty(),
                false,
                false);
    }

    public static GenerationProfile defaultsFor(
            int worldScale, String modelFingerprint, String inferenceDevice, String createdBackend) {
        return of(7, worldScale, "europe", 2, 8, modelFingerprint, inferenceDevice, createdBackend);
    }

    GenerationProfile withTerrainPreferences(
            int mountainRelief,
            int biomeVariety,
            WaterGenerationOptions water,
            boolean largerLandscapes,
            BiomeBalance biomeBalance,
            boolean tropicalLandforms,
            boolean largeIslands,
            Optional<Integer> climateShift,
            boolean slopeClimate,
            boolean newLandforms) {
        return new GenerationProfile(
                realismLevel,
                worldScale,
                region,
                formatVersion,
                generatorVersion,
                modelFingerprint,
                inferenceDevice,
                createdBackend,
                mountainRelief,
                biomeVariety,
                water,
                largerLandscapes,
                biomeBalance,
                tropicalLandforms,
                largeIslands,
                climateShift,
                slopeClimate,
                newLandforms);
    }

    public boolean slopeClimate() {
        return slopeClimate;
    }

    public boolean newLandforms() {
        return newLandforms;
    }

    public Optional<Integer> climateShift() {
        return climateShift;
    }

    public boolean largeIslands() {
        return largeIslands;
    }

    public boolean tropicalLandforms() {
        return tropicalLandforms;
    }

    public BiomeBalance biomeBalance() {
        return biomeBalance;
    }

    public boolean largerLandscapes() {
        return largerLandscapes;
    }

    public int mountainRelief() {
        return mountainRelief;
    }

    public int biomeVariety() {
        return biomeVariety;
    }

    public WaterGenerationOptions water() {
        return water;
    }

    public int realismLevel() {
        return this.realismLevel;
    }

    public int worldScale() {
        return this.worldScale;
    }

    public String region() {
        return this.region;
    }

    public int formatVersion() {
        return this.formatVersion;
    }

    public int generatorVersion() {
        return this.generatorVersion;
    }

    public String modelFingerprint() {
        return this.modelFingerprint;
    }

    public String inferenceDevice() {
        return this.inferenceDevice;
    }

    public String createdBackend() {
        return this.createdBackend;
    }

    public String fingerprint() {
        return GenerationFingerprint.of(this);
    }

    String canonicalForm() {
        return "ew/v"
                + this.formatVersion
                + "/gen"
                + this.generatorVersion
                + "/r"
                + this.realismLevel
                + "/s"
                + this.worldScale
                + "/"
                + this.region
                + "/model:"
                + this.modelFingerprint
                + "/dev:"
                + this.inferenceDevice
                + "/be:"
                + this.createdBackend
                + (biomeBalance.equals(BiomeBalance.DEFAULT)
                        ? ""
                        : "/biomes:"
                                + biomeBalance.mountains()
                                + ","
                                + biomeBalance.oceans()
                                + ","
                                + biomeBalance.deserts()
                                + ","
                                + biomeBalance.forests()
                                + ","
                                + biomeBalance.fields())
                + (slopeClimate ? "/slope-climate:1" : "")
                + (newLandforms ? "/landforms:1" : "")
                + climateShift.map(shift -> "/regional-terrain:1:" + shift).orElse("")
                + (largeIslands ? "/large-islands:1" : "")
                + (tropicalLandforms ? "/tropical:1" : "")
                + (largerLandscapes ? "/landscapes:large" : "")
                + (mountainRelief == 100 && biomeVariety == 0
                        ? ""
                        : "/relief:" + mountainRelief + "/biomeVariety:" + biomeVariety);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else {
            return !(o instanceof GenerationProfile other)
                    ? false
                    : this.realismLevel == other.realismLevel
                            && this.worldScale == other.worldScale
                            && this.formatVersion == other.formatVersion
                            && this.generatorVersion == other.generatorVersion
                            && this.region.equals(other.region)
                            && this.modelFingerprint.equals(other.modelFingerprint)
                            && this.inferenceDevice.equals(other.inferenceDevice)
                            && this.createdBackend.equals(other.createdBackend)
                            && mountainRelief == other.mountainRelief
                            && biomeVariety == other.biomeVariety
                            && water.equals(other.water)
                            && largerLandscapes == other.largerLandscapes
                            && biomeBalance.equals(other.biomeBalance)
                            && tropicalLandforms == other.tropicalLandforms
                            && largeIslands == other.largeIslands
                            && climateShift.equals(other.climateShift)
                            && slopeClimate == other.slopeClimate
                            && newLandforms == other.newLandforms;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.realismLevel,
                this.worldScale,
                this.region,
                this.formatVersion,
                this.generatorVersion,
                this.modelFingerprint,
                this.inferenceDevice,
                this.createdBackend,
                mountainRelief,
                biomeVariety,
                water,
                largerLandscapes,
                biomeBalance,
                tropicalLandforms,
                largeIslands,
                climateShift,
                slopeClimate,
                newLandforms);
    }

    @Override
    public String toString() {
        return "GenerationProfile[" + this.canonicalForm() + "]";
    }
}

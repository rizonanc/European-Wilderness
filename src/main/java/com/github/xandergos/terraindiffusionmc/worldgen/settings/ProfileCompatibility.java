package com.github.xandergos.terraindiffusionmc.worldgen.settings;

public final class ProfileCompatibility {
    public static final String UNKNOWN = "unknown";

    private ProfileCompatibility() {}

    public static void checkCompatibleOrThrow(
            GenerationProfile persisted, String bundledModel, String currentDevice, String currentBackend) {
        if (persisted == null) {
            throw new IllegalArgumentException("persisted profile must not be null");
        }

        if (!persisted.modelFingerprint().equals(bundledModel)) {
            throw new IllegalStateException(
                    "World was generated with model '"
                            + persisted.modelFingerprint()
                            + "' but this build bundles '"
                            + bundledModel
                            + "'. Continuing would mix foreign terrain into saved chunks. Create a new world (or restore the matching models) instead of generating silently.");
        }

        if (!persisted.inferenceDevice().equals(normalize(currentDevice))) {
            throw new IllegalStateException(
                    "World was created with inference device policy '"
                            + persisted.inferenceDevice()
                            + "' but the current config says '"
                            + currentDevice
                            + "'. A device switch changes generation timing and may change numerics. Create a new world instead of continuing silently.");
        }

        String recorded = normalize(persisted.createdBackend());
        String current = normalize(currentBackend);
        if (!"unknown".equals(recorded) && !"unknown".equals(current) && !recorded.equals(current)) {
            throw new IllegalStateException(
                    "World was created on backend '"
                            + recorded
                            + "' but this session resolved '"
                            + current
                            + "'. Cross-GPU/backend results are not proven bit-identical. Create a new world instead of continuing silently.");
        }
    }

    private static String normalize(String value) {
        return value == null ? "unknown" : value.trim().toLowerCase();
    }

    public static void checkScaleChangeAllowed(GenerationProfile persisted, int requestedScale) {
        if (persisted == null) {
            throw new IllegalArgumentException("persisted profile must not be null");
        }

        if (persisted.worldScale() != requestedScale) {
            throw new IllegalStateException(
                    "Refusing to change world scale from "
                            + persisted.worldScale()
                            + " to "
                            + requestedScale
                            + " in an existing world: saved chunks would no longer match new terrain. Create a new world instead.");
        }
    }
}

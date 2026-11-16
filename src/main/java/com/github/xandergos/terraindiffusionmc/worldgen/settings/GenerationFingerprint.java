package com.github.xandergos.terraindiffusionmc.worldgen.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class GenerationFingerprint {
    private GenerationFingerprint() {}

    public static String of(GenerationProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        } else {
            return sha256Hex(profile.canonicalForm().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            StringBuilder hex = new StringBuilder(hash.length * 2);

            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }

            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

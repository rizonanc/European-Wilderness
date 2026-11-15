package com.github.xandergos.terraindiffusionmc.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SettingsPreferences {
    private static final Gson GSON = new Gson();
    private static final int MAX_PROFILES = 32;
    private static final int MAX_RECENT = 20;
    private final Path path;
    private Data data;

    private record Data(Set<String> favorites, List<String> recent, Map<String, Map<String, String>> profiles) {}

    private SettingsPreferences(Path path, Data data) {
        this.path = path;
        this.data = data;
    }

    public static SettingsPreferences load(Path path) throws IOException {
        if (!Files.exists(path)) return new SettingsPreferences(path, new Data(Set.of(), List.of(), Map.of()));
        if (Files.size(path) > 256 * 1024) throw new IOException("Settings preferences exceed the file size limit");
        try {
            Data saved = GSON.fromJson(Files.readString(path), Data.class);
            if (saved == null
                    || saved.favorites == null
                    || saved.recent == null
                    || saved.profiles == null
                    || saved.profiles.size() > MAX_PROFILES) throw new IOException("Invalid settings preferences");
            var profiles = new LinkedHashMap<String, Map<String, String>>();
            for (var profile : saved.profiles.entrySet()) {
                if (!validName(profile.getKey()) || profile.getValue() == null)
                    throw new IOException("Invalid saved profile");
                var known = new LinkedHashMap<String, String>();
                for (String key : TerrainSettingsCatalog.KEYS) {
                    String value = profile.getValue().get(key);
                    if (value != null) known.put(key, value);
                }
                profiles.put(profile.getKey(), Map.copyOf(known));
            }
            return new SettingsPreferences(
                    path,
                    new Data(
                            Set.copyOf(knownKeys(saved.favorites)),
                            knownKeys(saved.recent).stream().limit(MAX_RECENT).toList(),
                            Collections.unmodifiableMap(profiles)));
        } catch (JsonParseException invalid) {
            throw new IOException("Invalid settings preferences", invalid);
        }
    }

    private static Set<String> knownKeys(Iterable<String> keys) {
        var known = new LinkedHashSet<String>();
        for (String key : keys) if (TerrainSettingsCatalog.KEYS.contains(key)) known.add(key);
        return known;
    }

    public boolean favorite(String key) {
        return data.favorites.contains(key);
    }

    public List<String> recent() {
        return data.recent;
    }

    public Map<String, Map<String, String>> profiles() {
        return data.profiles;
    }

    public void toggleFavorite(String key) throws IOException {
        var updated = new LinkedHashSet<>(data.favorites);
        if (!updated.remove(key)) updated.add(key);
        commit(new Data(Set.copyOf(updated), data.recent, data.profiles));
    }

    public void recordRecent(List<String> changed) throws IOException {
        if (changed.isEmpty()) return;
        var ordered = new LinkedHashSet<>(changed);
        ordered.addAll(data.recent);
        commit(new Data(data.favorites, ordered.stream().limit(MAX_RECENT).toList(), data.profiles));
    }

    public static boolean validName(String name) {
        return name != null
                && !name.isBlank()
                && name.length() <= 32
                && name.chars().noneMatch(Character::isISOControl);
    }

    public void saveProfile(String name, Map<String, String> settings) throws IOException {
        if (!validName(name) || data.profiles.containsKey(name) || data.profiles.size() == MAX_PROFILES)
            throw new IOException("Invalid, duplicate or excess profile name");
        if (TerrainDiffusionConfigEditor.validate(settings) != null) throw new IOException("Invalid profile settings");
        var updated = new LinkedHashMap<>(data.profiles);
        updated.put(name, Map.copyOf(settings));
        commit(new Data(data.favorites, data.recent, Collections.unmodifiableMap(updated)));
    }

    public void deleteProfile(String name) throws IOException {
        if (!data.profiles.containsKey(name)) return;
        var updated = new LinkedHashMap<>(data.profiles);
        updated.remove(name);
        commit(new Data(data.favorites, data.recent, Collections.unmodifiableMap(updated)));
    }

    private void commit(Data updated) throws IOException {
        var target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "td-settings-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(updated));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            data = updated;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

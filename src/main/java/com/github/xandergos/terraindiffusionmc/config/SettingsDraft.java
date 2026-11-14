package com.github.xandergos.terraindiffusionmc.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class SettingsDraft {
    private final Map<String, String> original;
    private final Map<String, String> defaults;
    private final Map<String, String> values;
    private final ArrayDeque<Map<String, String>> undo = new ArrayDeque<>();
    private final LinkedHashSet<String> touched = new LinkedHashSet<>();
    private String lastEdit;
    private long lastEditNanos;

    public SettingsDraft(Map<String, String> original, Map<String, String> defaults) {
        this.original = Map.copyOf(original);
        this.defaults = Map.copyOf(defaults);
        values = new LinkedHashMap<>(original);
    }

    public String get(String key) { return values.get(key); }
    public Map<String, String> values() { return Map.copyOf(values); }
    public boolean changed(String key) { return !original.get(key).equals(values.get(key)); }
    public boolean canUndo() { return !undo.isEmpty(); }

    public List<String> changedKeys() {
        var result = new ArrayList<String>();
        for (String key : touched.reversed()) if (changed(key)) result.add(key);
        return List.copyOf(result);
    }

    public void edit(String key, String value, boolean continuous) {
        if (value.equals(values.get(key))) return;
        long now = System.nanoTime();
        // A drag or typing burst is one edit.
        if (!continuous || !key.equals(lastEdit) || now - lastEditNanos > 500_000_000L) {
            checkpoint(Map.of(key, values.get(key)));
        }
        lastEdit = continuous ? key : null;
        lastEditNanos = now;
        put(key, value);
    }

    public void apply(Map<String, String> updates) {
        var previous = new LinkedHashMap<String, String>();
        for (var entry : updates.entrySet()) {
            String key = entry.getKey(), old = values.get(key);
            if (old != null && !old.equals(entry.getValue())) {
                previous.put(key, old);
                put(key, entry.getValue());
            }
        }
        if (!previous.isEmpty()) checkpoint(previous);
        lastEdit = null;
    }

    public void reset(List<String> keys) {
        var selected = new LinkedHashMap<String, String>();
        for (String key : keys) selected.put(key, defaults.get(key));
        apply(selected);
    }

    public void undo() {
        if (!undo.isEmpty()) undo.pop().forEach(this::put);
        lastEdit = null;
    }

    private void checkpoint(Map<String, String> previous) {
        if (undo.size() == 64) undo.removeLast();
        undo.push(previous);
    }

    private void put(String key, String value) {
        values.put(key, value);
        touched.remove(key);
        touched.add(key);
    }
}

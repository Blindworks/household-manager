package com.household.manager.flowengine.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typisierter, null-sicherer Zugriff auf die freie config-Map einer Node.
 */
public record NodeConfig(Map<String, Object> values) {

    public static NodeConfig empty() {
        return new NodeConfig(Map.of());
    }

    public Optional<String> string(String key) {
        Object value = values.get(key);
        return value != null ? Optional.of(String.valueOf(value)) : Optional.empty();
    }

    public Optional<Integer> integer(String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return Optional.of(number.intValue());
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Optional.of(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public List<String> stringList(String key) {
        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return Collections.emptyList();
    }
}

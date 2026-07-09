package com.household.manager.flowengine;

import lombok.Builder;

import java.util.List;

/**
 * Beschreibt ein Konfig-Feld eines Node-Typs für das schema-getriebene Panel.
 * options ist nur bei type == ENUM gesetzt.
 */
@Builder
public record NodeFieldDescriptor(
        String key,
        String label,
        NodeFieldType type,
        boolean required,
        List<String> options) {

    public static NodeFieldDescriptor field(String key, String label, NodeFieldType type, boolean required) {
        return new NodeFieldDescriptor(key, label, type, required, List.of());
    }

    public static NodeFieldDescriptor enumField(String key, String label, boolean required, List<String> options) {
        return new NodeFieldDescriptor(key, label, NodeFieldType.ENUM, required, options);
    }
}

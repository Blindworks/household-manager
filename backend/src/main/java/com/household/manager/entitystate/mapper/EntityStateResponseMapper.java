package com.household.manager.entitystate.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.EntityStateResponse;
import com.household.manager.model.entity.EntityState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * Bildet {@link EntityState}-Entities auf die API-{@link EntityStateResponse} ab.
 * Kapselt insbesondere das Deserialisieren der als JSON-String gespeicherten Attribute.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EntityStateResponseMapper {

    private final ObjectMapper objectMapper;

    public EntityStateResponse toResponse(EntityState entity) {
        return toResponse(entity, Map.of());
    }

    /** @param tileVisibility explizite Kachel-Regeln der Entität (tileKey → visibility) */
    public EntityStateResponse toResponse(EntityState entity, Map<String, String> tileVisibility) {
        return EntityStateResponse.builder()
                .entityId(entity.getEntityId())
                .domain(entity.getDomain().name())
                .source(entity.getSource().name())
                .sourceRef(entity.getSourceRef())
                .friendlyName(entity.getFriendlyName())
                .customName(entity.getCustomName())
                .displayName(displayName(entity))
                .state(entity.getState())
                .attributes(parseAttributes(entity.getAttributes()))
                .tileVisibility(tileVisibility)
                .lastChanged(entity.getLastChanged())
                .lastUpdated(entity.getLastUpdated())
                .build();
    }

    /** Effektiver Anzeigename: Kurzname, falls gesetzt, sonst der Integrationsname. */
    public String displayName(EntityState entity) {
        String custom = entity.getCustomName();
        return custom != null && !custom.isBlank() ? custom : entity.getFriendlyName();
    }

    /** Deserialisiert den JSON-Attribut-String; leere/ungültige Werte ergeben eine leere Map. */
    public Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            log.warn("Failed to parse entity attributes: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }
}

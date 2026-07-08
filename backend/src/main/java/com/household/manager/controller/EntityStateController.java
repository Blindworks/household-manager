package com.household.manager.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.household.manager.dto.EntityStateResponse;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.EntityState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST-API für die generische Entity-/State-Schicht.
 */
@RestController
@RequestMapping("/v1/entities")
@Slf4j
public class EntityStateController {

    private final EntityStateService entityStateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EntityStateController(EntityStateService entityStateService) {
        this.entityStateService = entityStateService;
    }

    @GetMapping
    public List<EntityStateResponse> getEntities(
            @RequestParam(required = false) EntityDomain domain,
            @RequestParam(required = false) EntitySource source) {
        return entityStateService.find(domain, source).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{entityId}")
    public ResponseEntity<EntityStateResponse> getEntity(@PathVariable String entityId) {
        return entityStateService.getByEntityId(entityId)
                .map(entity -> ResponseEntity.ok(toResponse(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{entityId}")
    public ResponseEntity<Void> deleteEntity(@PathVariable String entityId) {
        boolean deleted = entityStateService.deleteByEntityId(entityId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private EntityStateResponse toResponse(EntityState entity) {
        return EntityStateResponse.builder()
                .entityId(entity.getEntityId())
                .domain(entity.getDomain().name())
                .source(entity.getSource().name())
                .sourceRef(entity.getSourceRef())
                .friendlyName(entity.getFriendlyName())
                .state(entity.getState())
                .attributes(parseAttributes(entity.getAttributes()))
                .lastChanged(entity.getLastChanged())
                .lastUpdated(entity.getLastUpdated())
                .build();
    }

    private Map<String, Object> parseAttributes(String json) {
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

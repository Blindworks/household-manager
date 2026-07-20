package com.household.manager.controller;

import com.household.manager.dto.EntityStateResponse;
import com.household.manager.dto.UpdateEntityCustomNameRequest;
import com.household.manager.dto.UpdateTileVisibilityRequest;
import com.household.manager.entitystate.DashboardTiles;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityTileVisibilityService;
import com.household.manager.entitystate.TileVisibility;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST-API für die generische Entity-/State-Schicht.
 */
@RestController
@RequestMapping("/v1/entities")
@RequiredArgsConstructor
@Slf4j
public class EntityStateController {

    private final EntityStateService entityStateService;
    private final EntityTileVisibilityService tileVisibilityService;
    private final EntityStateResponseMapper responseMapper;

    @GetMapping
    public List<EntityStateResponse> getEntities(
            @RequestParam(required = false) EntityDomain domain,
            @RequestParam(required = false) EntitySource source) {
        List<EntityState> entities = entityStateService.find(domain, source);
        Map<String, Map<String, String>> visibility = tileVisibilityService.visibilityByEntity(
                entities.stream().map(EntityState::getEntityId).toList());
        return entities.stream()
                .map(entity -> responseMapper.toResponse(
                        entity, visibility.getOrDefault(entity.getEntityId(), Map.of())))
                .toList();
    }

    @GetMapping("/{entityId}")
    public ResponseEntity<EntityStateResponse> getEntity(@PathVariable String entityId) {
        return entityStateService.getByEntityId(entityId)
                .map(entity -> ResponseEntity.ok(toResponseWithVisibility(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{entityId}")
    public ResponseEntity<Void> deleteEntity(@PathVariable String entityId) {
        boolean deleted = entityStateService.deleteByEntityId(entityId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PutMapping("/{entityId}/custom-name")
    public ResponseEntity<EntityStateResponse> setCustomName(
            @PathVariable String entityId,
            @Valid @RequestBody UpdateEntityCustomNameRequest request) {
        return entityStateService.setCustomName(entityId, request.getCustomName())
                .map(entity -> ResponseEntity.ok(toResponseWithVisibility(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Setzt die Sichtbarkeitsregel einer Entität für eine Dashboard-Kachel.
     * "AUTO" entfernt die Regel (Standardverhalten).
     */
    @PutMapping("/{entityId}/tiles/{tileKey}")
    public ResponseEntity<EntityStateResponse> setTileVisibility(
            @PathVariable String entityId,
            @PathVariable String tileKey,
            @Valid @RequestBody UpdateTileVisibilityRequest request) {
        if (!DashboardTiles.isKnown(tileKey)) {
            throw new IllegalArgumentException("Unknown tile key: " + tileKey);
        }
        TileVisibility visibility = TileVisibility.parse(request.getVisibility())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown visibility: " + request.getVisibility()));
        return entityStateService.getByEntityId(entityId)
                .map(entity -> {
                    tileVisibilityService.setVisibility(entityId, tileKey, visibility);
                    return ResponseEntity.ok(toResponseWithVisibility(entity));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private EntityStateResponse toResponseWithVisibility(EntityState entity) {
        Map<String, String> visibility = tileVisibilityService
                .visibilityByEntity(List.of(entity.getEntityId()))
                .getOrDefault(entity.getEntityId(), Map.of());
        return responseMapper.toResponse(entity, visibility);
    }
}

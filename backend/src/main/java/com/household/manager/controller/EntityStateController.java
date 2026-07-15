package com.household.manager.controller;

import com.household.manager.dto.EntityStateResponse;
import com.household.manager.dto.UpdateEntityCustomNameRequest;
import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST-API für die generische Entity-/State-Schicht.
 */
@RestController
@RequestMapping("/v1/entities")
@RequiredArgsConstructor
@Slf4j
public class EntityStateController {

    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;

    @GetMapping
    public List<EntityStateResponse> getEntities(
            @RequestParam(required = false) EntityDomain domain,
            @RequestParam(required = false) EntitySource source) {
        return entityStateService.find(domain, source).stream()
                .map(responseMapper::toResponse)
                .toList();
    }

    @GetMapping("/{entityId}")
    public ResponseEntity<EntityStateResponse> getEntity(@PathVariable String entityId) {
        return entityStateService.getByEntityId(entityId)
                .map(entity -> ResponseEntity.ok(responseMapper.toResponse(entity)))
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
                .map(entity -> ResponseEntity.ok(responseMapper.toResponse(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

package com.household.manager.controller;

import com.household.manager.dto.CreateManualEntityRequest;
import com.household.manager.dto.EntityStateResponse;
import com.household.manager.dto.ManualEntityStateRequest;
import com.household.manager.dto.RenameManualEntityRequest;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST-API zum Anlegen und Steuern vom Benutzer erstellter Boolean-Entitäten
 * (manuelle Helfer/Modi wie "Nachtmodus" oder "Haus abgeschlossen").
 */
@RestController
@RequestMapping("/v1/entities/manual")
@RequiredArgsConstructor
@Slf4j
public class ManualEntityController {

    private final ManualEntityService manualEntityService;
    private final EntityStateResponseMapper responseMapper;

    @PostMapping
    public ResponseEntity<EntityStateResponse> create(@Valid @RequestBody CreateManualEntityRequest request) {
        EntityStateResponse response = responseMapper.toResponse(
                manualEntityService.create(request.getName(), request.getState(), request.getIcon()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{entityId}/state")
    public EntityStateResponse setState(@PathVariable String entityId,
                                        @Valid @RequestBody ManualEntityStateRequest request) {
        return responseMapper.toResponse(manualEntityService.setState(entityId, request.getState()));
    }

    @PostMapping("/{entityId}/toggle")
    public EntityStateResponse toggle(@PathVariable String entityId) {
        return responseMapper.toResponse(manualEntityService.toggle(entityId));
    }

    @PutMapping("/{entityId}")
    public EntityStateResponse rename(@PathVariable String entityId,
                                      @Valid @RequestBody RenameManualEntityRequest request) {
        return responseMapper.toResponse(manualEntityService.rename(entityId, request.getName(), request.getIcon()));
    }

    @DeleteMapping("/{entityId}")
    public ResponseEntity<Void> delete(@PathVariable String entityId) {
        manualEntityService.delete(entityId);
        return ResponseEntity.noContent().build();
    }
}

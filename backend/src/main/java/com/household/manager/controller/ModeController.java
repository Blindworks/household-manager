package com.household.manager.controller;

import com.household.manager.dto.ModeResponse;
import com.household.manager.entitystate.HouseModeQueryService;
import com.household.manager.entitystate.ManualEntityService;
import com.household.manager.entitystate.mapper.ModeResponseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-API der Haus-Modi (Modus-Leiste im Dashboard). Das Umschalten delegiert
 * an {@link ManualEntityService}, damit Events für die Flow-Engine über den
 * gewohnten Weg publiziert werden.
 */
@RestController
@RequestMapping("/v1/modes")
@RequiredArgsConstructor
public class ModeController {

    private final HouseModeQueryService houseModeQueryService;
    private final ManualEntityService manualEntityService;
    private final ModeResponseMapper modeResponseMapper;

    @GetMapping
    public List<ModeResponse> listModes() {
        return houseModeQueryService.listModes();
    }

    @PostMapping("/{entityId}/toggle")
    public ModeResponse toggle(@PathVariable String entityId) {
        return modeResponseMapper.toResponse(manualEntityService.toggle(entityId));
    }
}

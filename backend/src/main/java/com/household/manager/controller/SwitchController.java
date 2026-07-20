package com.household.manager.controller;

import com.household.manager.dto.SwitchResponse;
import com.household.manager.entitystate.SwitchCommandService;
import com.household.manager.entitystate.SwitchQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST-API für die schaltbaren Entitäten der Schalter-Kachel.
 */
@RestController
@RequestMapping("/v1/switches")
@RequiredArgsConstructor
@Slf4j
public class SwitchController {

    private final SwitchQueryService switchQueryService;
    private final SwitchCommandService switchCommandService;

    /**
     * @param limit optionale Obergrenze; ohne Angabe werden alle Schalter geliefert
     * @param view  "tile" wendet die Kachel-Sichtbarkeitsregeln an; Standard "all"
     */
    @GetMapping
    public List<SwitchResponse> getSwitches(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "all") String view) {
        return switchQueryService.listSwitches(limit, "tile".equals(view));
    }

    @PostMapping("/{entityId}/toggle")
    public SwitchResponse toggle(@PathVariable String entityId) {
        log.info("POST /api/v1/switches/{}/toggle", entityId);
        return switchCommandService.toggle(entityId);
    }
}

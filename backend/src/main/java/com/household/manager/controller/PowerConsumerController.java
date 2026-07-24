package com.household.manager.controller;

import com.household.manager.dto.PowerConsumerResponse;
import com.household.manager.dto.PowerHistoryResponse;
import com.household.manager.entitystate.PowerConsumerQueryService;
import com.household.manager.entitystate.PowerHistoryService;
import com.household.manager.entitystate.PowerRange;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-API für die Verbraucher-Kachel (Stromverbraucher, größter zuerst)
 * und den Verbrauchsgraphen eines einzelnen Verbrauchers.
 */
@RestController
@RequestMapping("/v1/power-consumers")
@RequiredArgsConstructor
public class PowerConsumerController {

    private final PowerConsumerQueryService powerConsumerQueryService;
    private final PowerHistoryService powerHistoryService;

    /** @param limit optionale Obergrenze; ohne Angabe werden alle Verbraucher geliefert */
    @GetMapping
    public List<PowerConsumerResponse> getConsumers(
            @RequestParam(required = false) Integer limit) {
        return powerConsumerQueryService.listConsumers(limit);
    }

    /** @param range Zeitraum des Verlaufs; Standard 24 Stunden */
    @GetMapping("/{entityId}/history")
    public ResponseEntity<PowerHistoryResponse> getHistory(
            @PathVariable String entityId,
            @RequestParam(required = false, defaultValue = "DAY") PowerRange range) {
        return powerHistoryService.getHistory(entityId, range)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

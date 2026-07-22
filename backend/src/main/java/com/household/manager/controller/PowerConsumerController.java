package com.household.manager.controller;

import com.household.manager.dto.PowerConsumerResponse;
import com.household.manager.entitystate.PowerConsumerQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST-API für die Verbraucher-Kachel (Stromverbraucher, größter zuerst).
 */
@RestController
@RequestMapping("/v1/power-consumers")
@RequiredArgsConstructor
public class PowerConsumerController {

    private final PowerConsumerQueryService powerConsumerQueryService;

    /** @param limit optionale Obergrenze; ohne Angabe werden alle Verbraucher geliefert */
    @GetMapping
    public List<PowerConsumerResponse> getConsumers(
            @RequestParam(required = false) Integer limit) {
        return powerConsumerQueryService.listConsumers(limit);
    }
}

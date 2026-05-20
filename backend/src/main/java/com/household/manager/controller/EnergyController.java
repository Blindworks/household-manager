package com.household.manager.controller;

import com.household.manager.dto.EnergyLiveDto;
import com.household.manager.service.EnergyCalculationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/energy")
@RequiredArgsConstructor
public class EnergyController {

    private final EnergyCalculationService energyCalculationService;

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return energyCalculationService.subscribe();
    }

    @GetMapping("/current")
    public ResponseEntity<EnergyLiveDto> getCurrent() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(energyCalculationService.calculateCurrent());
    }
}

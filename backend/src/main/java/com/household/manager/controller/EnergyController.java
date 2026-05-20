package com.household.manager.controller;

import com.household.manager.dto.EnergyHistoryPointDto;
import com.household.manager.dto.EnergyLiveDto;
import com.household.manager.model.entity.EnergyReading;
import com.household.manager.repository.EnergyReadingRepository;
import com.household.manager.service.EnergyCalculationService;
import com.household.manager.service.EnergyMetricType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/energy")
@RequiredArgsConstructor
public class EnergyController {

    private final EnergyCalculationService energyCalculationService;
    private final EnergyReadingRepository energyReadingRepository;

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

    @GetMapping("/history")
    public ResponseEntity<List<EnergyHistoryPointDto>> getHistory(
            @RequestParam EnergyMetricType metric,
            @RequestParam(defaultValue = "24") int hours) {

        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusHours(Math.max(1, hours));

        List<EnergyReading> readings = energyReadingRepository
                .findByTimestampBetweenOrderByTimestampAsc(from, to);

        List<EnergyHistoryPointDto> points = readings.stream()
                .map(r -> EnergyHistoryPointDto.builder()
                        .timestamp(r.getTimestamp())
                        .value(metric.extract(r))
                        .build())
                .toList();

        return ResponseEntity.ok(points);
    }
}

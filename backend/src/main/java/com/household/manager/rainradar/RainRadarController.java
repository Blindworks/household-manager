package com.household.manager.rainradar;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Radarverlauf fuer den Dashboard-Dialog. Lesen ist ueber die generische {@code GET /v1/**}-Regel
 * KIOSK-erlaubt (Wandtablet), keine eigene Security-Zeile.
 */
@RestController
@RequestMapping("/v1/rain-radar")
@RequiredArgsConstructor
public class RainRadarController {

    private final RainRadarPollingService pollingService;

    @GetMapping("/forecast")
    public RainRadarForecastResponse forecast() {
        return pollingService.forecast();
    }
}

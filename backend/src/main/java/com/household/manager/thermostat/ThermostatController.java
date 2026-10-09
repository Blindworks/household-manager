package com.household.manager.thermostat;

import com.household.manager.service.SeriesRange;
import com.household.manager.thermostat.dto.BulkRequest;
import com.household.manager.thermostat.dto.HistoryResponse;
import com.household.manager.thermostat.dto.ModeRequest;
import com.household.manager.thermostat.dto.TargetRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Heizungs-API fuer die Tablet-Ansicht. Bewusst duenn: Validierung, Hub-Zugriff und Audit
 * leben in {@link ThermostatService}. Hub nicht erreichbar wird vom {@code GlobalExceptionHandler}
 * als 502 abgebildet, nie als 401.
 */
@RestController
@RequestMapping("/v1/thermostats")
@RequiredArgsConstructor
public class ThermostatController {

    private final ThermostatService thermostatService;
    private final ThermostatHistoryService historyService;

    @GetMapping
    public ResponseEntity<List<ThermostatDto>> list() {
        return ResponseEntity.ok(thermostatService.list());
    }

    @PutMapping("/{id}/target")
    public ResponseEntity<ThermostatDto> setTarget(@PathVariable Long id, @Valid @RequestBody TargetRequest request) {
        return ResponseEntity.ok(thermostatService.setTarget(id, request.targetTemp()));
    }

    @PutMapping("/{id}/mode")
    public ResponseEntity<ThermostatDto> setMode(@PathVariable Long id, @Valid @RequestBody ModeRequest request) {
        return ResponseEntity.ok(thermostatService.setMode(id, request.mode()));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<ThermostatDto>> bulk(@Valid @RequestBody BulkRequest request) {
        return ResponseEntity.ok(thermostatService.bulk(request.ids(), request.targetTemp()));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<HistoryResponse> history(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "DAY") SeriesRange range) {
        return ResponseEntity.ok(historyService.history(id, range));
    }
}

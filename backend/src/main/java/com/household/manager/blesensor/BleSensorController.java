package com.household.manager.blesensor;

import com.household.manager.blesensor.BleSensorDtos.ReadingsRequest;
import com.household.manager.blesensor.BleSensorDtos.SensorAdminResponse;
import com.household.manager.blesensor.BleSensorDtos.SensorUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Bluetooth-Thermometer: Sammelmeldung des Tablets (SERVICE) und Admin-Pflege (ADMIN).
 * Rollen stehen in SecurityConfig, nicht hier.
 */
@RestController
@RequestMapping("/v1/ble-sensors")
@RequiredArgsConstructor
public class BleSensorController {

    private final BleSensorIngestService ingestService;
    private final BleSensorAdminService adminService;

    @PostMapping("/readings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readings(@RequestBody ReadingsRequest request) {
        ingestService.ingest(request);
    }

    @GetMapping
    public List<SensorAdminResponse> list() {
        return adminService.list();
    }

    @PutMapping("/{id}")
    public SensorAdminResponse update(@PathVariable Long id, @RequestBody SensorUpdateRequest request) {
        return adminService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        adminService.delete(id);
    }
}

package com.household.manager.house;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Grundriss-Positionen der Geraete im Digitalen Zwilling. Lesen: alle Angemeldeten inkl.
 * KIOSK (generische GET-Regel). Setzen und Loeschen: ADMIN (eigene Matcher in
 * {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/v1/house/device-positions")
@RequiredArgsConstructor
public class HouseDevicePositionController {

    private final HouseDevicePositionService service;

    @GetMapping
    public List<HouseDevicePositionDtos.Response> list() {
        return service.list();
    }

    @PutMapping("/{deviceId}")
    public HouseDevicePositionDtos.Response set(@PathVariable String deviceId,
                                                @RequestBody(required = false) HouseDevicePositionDtos.Request request) {
        if (request == null) {
            throw new IllegalArgumentException("Koordinaten fehlen.");
        }
        return service.set(deviceId, request.x(), request.y(), request.rotation());
    }

    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> delete(@PathVariable String deviceId) {
        service.delete(deviceId);
        return ResponseEntity.noContent().build();
    }
}

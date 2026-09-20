package com.household.manager.charging;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** ADMIN-only ueber die Matcher-Reihenfolge in SecurityConfig (methodenloser Matcher vor GET /v1/**). */
@RestController
@RequestMapping("/v1/charging/settings")
@RequiredArgsConstructor
public class ChargingSettingsController {

    private final ChargingSettingsService settingsService;

    @GetMapping
    public ResponseEntity<ChargingSettings> get() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PutMapping
    public ResponseEntity<ChargingSettings> update(@RequestBody ChargingDtos.SettingsRequest request) {
        validate(request);
        settingsService.saveSettings(new ChargingSettings(request.homeLatitude(), request.homeLongitude(),
                request.radiusKm(), request.minPowerKw()));
        return ResponseEntity.ok(settingsService.getSettings());
    }

    /** Double.isFinite ist bei den einseitigen Koordinatenpruefungen tragend (NaN-Falle, siehe Tractive). */
    private void validate(ChargingDtos.SettingsRequest r) {
        boolean hasLat = r.homeLatitude() != null;
        boolean hasLon = r.homeLongitude() != null;
        if (hasLat != hasLon) {
            throw badRequest("Breiten- und Laengengrad muessen gemeinsam gesetzt oder gemeinsam leer sein.");
        }
        if (hasLat && (!Double.isFinite(r.homeLatitude()) || Math.abs(r.homeLatitude()) > 90)) {
            throw badRequest("Der Breitengrad muss zwischen -90 und 90 liegen.");
        }
        if (hasLon && (!Double.isFinite(r.homeLongitude()) || Math.abs(r.homeLongitude()) > 180)) {
            throw badRequest("Der Laengengrad muss zwischen -180 und 180 liegen.");
        }
        if (!Double.isFinite(r.radiusKm()) || r.radiusKm() < ChargingSettingsService.MIN_RADIUS_KM
                || r.radiusKm() > ChargingSettingsService.MAX_RADIUS_KM) {
            throw badRequest("Der Radius muss zwischen 1 und 50 km liegen.");
        }
        if (!Double.isFinite(r.minPowerKw()) || r.minPowerKw() < ChargingSettingsService.MIN_POWER_KW
                || r.minPowerKw() > ChargingSettingsService.MAX_POWER_KW) {
            throw badRequest("Die Mindestleistung muss zwischen 0 und 400 kW liegen.");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

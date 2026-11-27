package com.household.manager.tesla;

import com.household.manager.tesla.dto.TeslaSettingsRequest;
import com.household.manager.tesla.dto.TeslaSettingsResponse;
import com.household.manager.tesla.dto.TeslaStatusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Admin-Seite „Tesla“: Token, Fahrzeug, Intervall. ADMIN, auch Lesen ({@code SecurityConfig}). */
@RestController
@RequestMapping("/v1/tesla/settings")
@RequiredArgsConstructor
@Slf4j
public class TeslaAdminController {

    private final TeslaSettingsService settingsService;
    private final TeslaVehicleService vehicleService;

    @GetMapping
    public TeslaSettingsResponse get() {
        return toResponse(settingsService.current());
    }

    @PutMapping
    public TeslaSettingsResponse update(@RequestBody TeslaSettingsRequest request) {
        int interval = request.pollIntervalMinutes() != null
                ? request.pollIntervalMinutes() : TeslaSettingsService.DEFAULT_INTERVAL_MINUTES;
        TeslaSettings saved = settingsService.update(request.apiToken(), request.vin(), interval);
        vehicleService.onSettingsChanged();
        return toResponse(saved);
    }

    /** Fahrzeuge des Kontos; mit {@code token} im Body vor dem Speichern prüfbar. */
    @PostMapping("/vehicles")
    public List<TessieVehicle> vehicles(@RequestBody(required = false) Map<String, String> body) {
        return vehicleService.listVehicles(body == null ? null : body.get("apiToken"));
    }

    /** Jetzt abrufen (Prüfknopf der Admin-Seite); Fehler kommen als 400/502 zurück. */
    @PostMapping("/refresh")
    public TeslaStatusResponse refresh() {
        vehicleService.refresh();
        return vehicleService.status();
    }

    private static TeslaSettingsResponse toResponse(TeslaSettings settings) {
        return new TeslaSettingsResponse(settings.hasToken(), settings.vin(), settings.pollIntervalMinutes(),
                TeslaSettingsService.MIN_INTERVAL_MINUTES, TeslaSettingsService.MAX_INTERVAL_MINUTES);
    }
}

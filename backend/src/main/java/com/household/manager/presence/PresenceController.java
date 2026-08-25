package com.household.manager.presence;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Status-, Geraete- und Einstellungs-API der Anwesenheitserkennung. Bewusst
 * duenn — alle Logik steckt in den Services. Der Zugriff auf /settings ist
 * ueber die Matcher-Reihenfolge in {@code SecurityConfig} auf ADMIN
 * beschraenkt; /status bleibt KIOSK-lesbar (Dashboard-Kachel auf dem Tablet).
 */
@RestController
@RequestMapping("/v1/presence")
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceStatusService statusService;
    private final PresenceDeviceService deviceService;
    private final PresenceSettingsService settingsService;

    @GetMapping("/status")
    public ResponseEntity<PresenceDtos.StatusResponse> status() {
        return ResponseEntity.ok(statusService.getStatus());
    }

    @GetMapping("/devices")
    public ResponseEntity<List<PresenceDtos.DeviceAdminResponse>> listDevices() {
        return ResponseEntity.ok(deviceService.list());
    }

    @PostMapping("/devices")
    public ResponseEntity<PresenceDtos.DeviceAdminResponse> createDevice(
            @RequestBody PresenceDtos.DeviceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(deviceService.create(request));
    }

    @PutMapping("/devices/{id}")
    public ResponseEntity<PresenceDtos.DeviceAdminResponse> updateDevice(
            @PathVariable Long id, @RequestBody PresenceDtos.DeviceRequest request) {
        return ResponseEntity.ok(deviceService.update(id, request));
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable Long id) {
        deviceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    public ResponseEntity<PresenceDtos.SettingsDto> getSettings() {
        return ResponseEntity.ok(new PresenceDtos.SettingsDto(settingsService.getAwayGraceMinutes()));
    }

    @PutMapping("/settings")
    public ResponseEntity<PresenceDtos.SettingsDto> updateSettings(
            @RequestBody PresenceDtos.SettingsDto request) {
        Long minutes = request.awayGraceMinutes();
        if (minutes == null || minutes < 1
                || minutes > PresenceSettingsService.MAX_AWAY_GRACE_MINUTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Karenzzeit muss zwischen 1 und "
                            + PresenceSettingsService.MAX_AWAY_GRACE_MINUTES + " Minuten liegen.");
        }
        settingsService.saveAwayGraceMinutes(minutes);
        return ResponseEntity.ok(new PresenceDtos.SettingsDto(settingsService.getAwayGraceMinutes()));
    }
}

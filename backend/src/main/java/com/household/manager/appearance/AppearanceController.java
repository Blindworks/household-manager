package com.household.manager.appearance;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/v1/appearance — fuer alle Angemeldeten inkl. KIOSK (generische GET-Regel),
 * das Wandtablet muss das Design lesen koennen.
 * PUT /api/v1/appearance — ADMIN (eigener Matcher in {@code SecurityConfig}).
 * Ein unbekannter Enum-Text scheitert schon beim Deserialisieren mit 400.
 */
@RestController
@RequestMapping("/v1/appearance")
@RequiredArgsConstructor
public class AppearanceController {

    private final AppearanceSettingsService settingsService;

    @GetMapping
    public ResponseEntity<AppearanceSettingsDto> getSettings() {
        return ResponseEntity.ok(new AppearanceSettingsDto(settingsService.getDashboardTheme()));
    }

    @PutMapping
    public ResponseEntity<AppearanceSettingsDto> updateSettings(@RequestBody AppearanceSettingsDto request) {
        if (request.dashboardTheme() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Das Design fehlt.");
        }
        settingsService.saveDashboardTheme(request.dashboardTheme());
        return ResponseEntity.ok(new AppearanceSettingsDto(settingsService.getDashboardTheme()));
    }
}

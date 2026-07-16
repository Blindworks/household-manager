package com.household.manager.controller;

import com.household.manager.dto.WasteCollectionEventResponse;
import com.household.manager.dto.WasteCollectionSettings;
import com.household.manager.service.WasteCollectionService;
import com.household.manager.service.WasteCollectionSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Termine und Konfiguration der Muellabfuhr.
 * Basis-URL: /api/v1/waste-collection
 */
@RestController
@RequestMapping("/v1/waste-collection")
@RequiredArgsConstructor
@Slf4j
public class WasteCollectionController {

    private final WasteCollectionService collectionService;
    private final WasteCollectionSettingsService settingsService;

    /**
     * @param days optionales Fenster; ohne Angabe gilt {@code lookahead_days} aus den Settings
     */
    @GetMapping("/upcoming")
    public ResponseEntity<List<WasteCollectionEventResponse>> getUpcoming(
            @RequestParam(name = "days", required = false) Integer days) {
        int window = days != null ? days : settingsService.getLookaheadDays();
        return ResponseEntity.ok(collectionService.getUpcoming(window));
    }

    @GetMapping("/settings")
    public ResponseEntity<WasteCollectionSettings> getSettings() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PutMapping("/settings")
    public ResponseEntity<WasteCollectionSettings> updateSettings(
            @RequestBody WasteCollectionSettings settings) {
        validate(settings);
        settingsService.saveSettings(settings);
        return ResponseEntity.ok(settingsService.getSettings());
    }

    private void validate(WasteCollectionSettings settings) {
        validateIcsUrl(settings.getIcsUrl());

        if (settings.getLookaheadDays() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Vorschau muss mindestens 1 Tag umfassen.");
        }

        try {
            LocalTime.parse(settings.getReminderTime());
        } catch (NullPointerException | DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Uhrzeit muss im Format HH:mm angegeben werden.");
        }
    }

    /** Leer ist erlaubt (Feature dann inaktiv); sonst muss es eine http(s)-URL sein. */
    private void validateIcsUrl(String icsUrl) {
        if (icsUrl == null || icsUrl.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(icsUrl);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Kalender-URL ist keine gueltige URL.");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))
                || uri.getHost() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Die Kalender-URL muss mit http:// oder https:// beginnen.");
        }
    }
}

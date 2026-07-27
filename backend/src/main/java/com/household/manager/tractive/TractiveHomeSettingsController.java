package com.household.manager.tractive;

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
 * Pflege der Home-Definition im Admin-Bereich.
 *
 * <p>Der Zugriff ist ueber die Matcher-Reihenfolge in
 * {@code SecurityConfig} auf ADMIN beschraenkt – die generische Regel
 * {@code GET /v1/**} wuerde sonst auch dem Kiosk-Tablet das Lesen erlauben.
 */
@RestController
@RequestMapping("/v1/tractive/home-settings")
@RequiredArgsConstructor
public class TractiveHomeSettingsController {

    private final TractiveHomeSettingsService settingsService;

    @GetMapping
    public ResponseEntity<TractiveHomeSettings> get() {
        return ResponseEntity.ok(settingsService.getSettings());
    }

    @PutMapping
    public ResponseEntity<TractiveHomeSettings> update(@RequestBody TractiveHomeSettings settings) {
        validate(settings);
        settingsService.saveSettings(settings);
        return ResponseEntity.ok(settingsService.getSettings());
    }

    /**
     * Serverseitig, nicht nur im Formular: ein vertippter Breitengrad darf nicht in die
     * Datenbank, weil er dort still zu "nicht konfiguriert" wird und die Entitaet
     * verschwinden laesst.
     */
    private void validate(TractiveHomeSettings settings) {
        boolean hasLatitude = settings.homeLatitude() != null;
        boolean hasLongitude = settings.homeLongitude() != null;
        if (hasLatitude != hasLongitude) {
            throw badRequest("Breiten- und Laengengrad muessen gemeinsam gesetzt oder gemeinsam leer sein.");
        }
        if (hasLatitude && (!Double.isFinite(settings.homeLatitude()) || Math.abs(settings.homeLatitude()) > 90)) {
            throw badRequest("Der Breitengrad muss zwischen -90 und 90 liegen.");
        }
        if (hasLongitude && (!Double.isFinite(settings.homeLongitude()) || Math.abs(settings.homeLongitude()) > 180)) {
            throw badRequest("Der Laengengrad muss zwischen -180 und 180 liegen.");
        }
        if (!isPlausibleRadius(settings.homeRadiusMeters())) {
            throw badRequest("Der Home-Radius muss zwischen 0 und "
                    + (long) TractiveHomeSettingsService.MAX_RADIUS_METERS + " Metern liegen.");
        }
        if (!isPlausibleRadius(settings.homeArrivalRadiusMeters())) {
            throw badRequest("Der Ankunftsradius muss zwischen 0 und "
                    + (long) TractiveHomeSettingsService.MAX_RADIUS_METERS + " Metern liegen.");
        }
        if (settings.poweredOffAfterMinutes() <= 0) {
            throw badRequest("Die Stille-Schwelle muss groesser als 0 Minuten sein.");
        }
        if (settings.poweredOffMinBatteryPercent() < 0 || settings.poweredOffMinBatteryPercent() > 100) {
            throw badRequest("Der Mindest-Akkustand muss zwischen 0 und 100 Prozent liegen.");
        }
    }

    /**
     * Dieselbe Grenze, die {@link TractiveHomeSettingsService} beim Lesen anwendet – sonst
     * nimmt die API einen Wert mit 200 an, den der Service danach wortlos auf den Default
     * zurueckdreht.
     *
     * <p>Die {@code isFinite}-Pruefung ist hier streng genommen redundant: weil dies eine
     * BEIDSEITIGE Schranke ist, faellt {@code NaN} schon an {@code > 0} und {@code Infinity}
     * an der Obergrenze durch. Sie steht trotzdem da, damit die Absicht sichtbar bleibt und
     * das Entfernen der Obergrenze nicht stillschweigend ein Loch aufreisst. Bei den
     * EINSEITIGEN Koordinatenpruefungen oben ist sie dagegen tragend – dort ergibt
     * {@code Math.abs(NaN) > 90} false, und ein NaN kaeme ohne sie durch.
     */
    private boolean isPlausibleRadius(double meters) {
        return Double.isFinite(meters) && meters > 0 && meters <= TractiveHomeSettingsService.MAX_RADIUS_METERS;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

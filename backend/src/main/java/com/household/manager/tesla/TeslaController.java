package com.household.manager.tesla;

import com.household.manager.tesla.dto.TeslaCommandRequest;
import com.household.manager.tesla.dto.TeslaStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Dashboard-Kachel: Lesen KIOSK über die generische GET-Regel, Befehle MEMBER
 * ({@code anyRequest}; das Wandtablet-Konto ist MEMBER).
 */
@RestController
@RequestMapping("/v1/tesla/vehicle")
@RequiredArgsConstructor
public class TeslaController {

    private final TeslaVehicleService vehicleService;
    private final TeslaVehicleImageService imageService;

    /** Letzter Stand aus dem Speicher, fragt Tessie nicht. */
    @GetMapping
    public TeslaStatusResponse status() {
        return withImage(vehicleService.status());
    }

    /**
     * Fahrzeugbild (eigene Adresse oder Teslas Konfigurator), vom Backend geladen und
     * zwischengespeichert. 404 ohne Adresse, 502 wenn die Adresse kein Bild liefert —
     * die Kachel zeigt dann die Zeichnung.
     */
    @GetMapping("/image")
    public ResponseEntity<byte[]> image() {
        return imageService.image()
                .map(img -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(img.contentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)))
                        .body(img.bytes()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/commands")
    public TeslaStatusResponse command(@Valid @RequestBody TeslaCommandRequest request) {
        return withImage(vehicleService.execute(request.command()));
    }

    private TeslaStatusResponse withImage(TeslaStatusResponse status) {
        return status != null && status.configured() ? status.withImageVersion(imageService.version()) : status;
    }
}

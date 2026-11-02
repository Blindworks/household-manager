package com.household.manager.charging;

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
 * Lese-API fuer Tablet und Website plus Favoriten. Lesen KIOSK (generische GET-Regel),
 * /refresh steht in der KIOSK-POST-Whitelist, Favoriten sind MEMBER (anyRequest).
 */
@RestController
@RequestMapping("/v1/charging")
@RequiredArgsConstructor
public class ChargingController {

    private final ChargingQueryService queryService;
    private final ChargingPollingService pollingService;
    private final ChargingFavoriteService favoriteService;
    private final ChargingStationDetailsService detailsService;

    @GetMapping("/stations")
    public ResponseEntity<ChargingDtos.StationsResponse> stations() {
        return ResponseEntity.ok(queryService.stations());
    }

    /** Ladepunkte einer beliebigen Station auf Anfrage (60 s gecacht); KIOSK ueber die generische GET-Regel. */
    @GetMapping("/stations/{stationId}/charge-points")
    public ResponseEntity<List<ChargingDtos.ChargePointResponse>> chargePoints(@PathVariable String stationId) {
        rejectSupercharger(stationId);
        return ResponseEntity.ok(detailsService.chargePoints(stationId));
    }

    /** Vorhersage einer Favoritenstation (404 sonst); KIOSK ueber die generische GET-Regel. */
    @GetMapping("/stations/{stationId}/forecast")
    public ResponseEntity<ChargingDtos.ForecastResponse> forecast(@PathVariable String stationId) {
        rejectSupercharger(stationId);
        return ResponseEntity.ok(queryService.forecast(stationId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ChargingDtos.StationsResponse> refresh() {
        pollingService.refreshNow();
        return ResponseEntity.ok(queryService.stations());
    }

    @GetMapping("/favorites")
    public ResponseEntity<List<ChargingDtos.FavoriteResponse>> favorites() {
        return ResponseEntity.ok(favoriteService.list().stream()
                .map(f -> new ChargingDtos.FavoriteResponse(f.getStationId(), f.getDisplayName(), f.getOperator(),
                        f.getLat(), f.getLon()))
                .toList());
    }

    @PutMapping("/favorites/{stationId}")
    public ResponseEntity<ChargingDtos.StationsResponse> addFavorite(@PathVariable String stationId) {
        rejectSupercharger(stationId);
        favoriteService.add(stationId);
        return ResponseEntity.ok(queryService.stations());
    }

    /** Direktpreis je Favorit; MEMBER (der KIOSK-Matcher fuer Favoriten deckt nur ein Pfadsegment). */
    @PutMapping("/favorites/{stationId}/price")
    public ResponseEntity<ChargingDtos.StationsResponse> setDirectPrice(
            @PathVariable String stationId, @RequestBody ChargingDtos.DirectPriceRequest request) {
        rejectSupercharger(stationId);
        favoriteService.setDirectPrice(stationId, request.pricePerKwh(), request.note());
        return ResponseEntity.ok(queryService.stations());
    }

    @DeleteMapping("/favorites/{stationId}")
    public ResponseEntity<ChargingDtos.StationsResponse> removeFavorite(@PathVariable String stationId) {
        favoriteService.remove(stationId);
        return ResponseEntity.ok(queryService.stations());
    }

    /** Supercharger kennen nur den Standort; EnBW wuerde die Id ohnehin nicht finden. */
    private static void rejectSupercharger(String stationId) {
        if (stationId.startsWith(ChargingQueryService.SUPERCHARGER_ID_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supercharger haben keine Belegungsdaten");
        }
    }
}

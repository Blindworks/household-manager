package com.household.manager.charging;

import com.household.manager.audit.AuditService;
import com.household.manager.charging.forecast.ChargingForecastService;
import com.household.manager.charging.forecast.ChargingPatternService;
import com.household.manager.exception.ResourceNotFoundException;
import com.household.manager.model.entity.ChargingFavorite;
import com.household.manager.repository.ChargingFavoriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;

/**
 * Favorisierte Ladesaeulen. Name/Betreiber/Koordinaten kommen beim Favorisieren aus dem
 * aktuellen Snapshot, damit die Liste auch bei Quellenausfall lesbar bleibt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChargingFavoriteService {

    private static final int MAX_NOTE_LENGTH = 60;
    private static final double MAX_DIRECT_PRICE = 2.0;

    private final ChargingFavoriteRepository repository;
    private final ChargingOccupancyTracker tracker;
    private final ChargingSnapshot snapshot;
    private final AuditService auditService;
    private final Clock clock;
    private final ChargingForecastService forecastService;
    private final ChargingPatternService patternService;

    public List<ChargingFavorite> list() {
        return repository.findAllByOrderByCreatedAtAscIdAsc();
    }

    /** Nur eine Station aus dem aktuellen Umkreis-Stand ist favorisierbar (400 sonst). */
    @Transactional
    public void add(String stationId) {
        ChargingStation station = snapshot.station(stationId).orElseThrow(() ->
                new IllegalArgumentException("Die Station " + stationId
                        + " ist nicht in der aktuellen Umkreisliste und kann nicht favorisiert werden."));
        if (repository.existsByStationId(stationId)) {
            return;
        }
        repository.save(ChargingFavorite.builder()
                .stationId(stationId)
                .displayName(station.name())
                .operator(station.operator())
                .lat(station.lat())
                .lon(station.lon())
                .createdAt(clock.instant())
                .build());
        auditService.record("charging.favorite.add", stationId + " (" + station.name() + ")");
        log.info("Ladesaeule {} favorisiert", stationId);
    }

    /**
     * Setzt den von Hand gepflegten Direktpreis; {@code pricePerKwh == null} loescht Preis und Notiz.
     * Unbekannter Favorit: 404. Die Grenzen stehen hier verbindlich, das Frontend prueft nur zur Bedienhilfe.
     */
    @Transactional
    public void setDirectPrice(String stationId, Double pricePerKwh, String note) {
        BigDecimal price = validPrice(pricePerKwh);
        String cleanNote = price == null ? null : cleanNote(note);
        ChargingFavorite favorite = repository.findByStationId(stationId).orElseThrow(() ->
                new ResourceNotFoundException("Keine Favoritenstation: " + stationId));
        if (price == null) {
            favorite.setDirectPricePerKwh(null);
            favorite.setDirectPriceNote(null);
            repository.save(favorite);
            auditService.record("charging.favorite.price", stationId + " -> entfernt");
            return;
        }
        favorite.setDirectPricePerKwh(price);
        favorite.setDirectPriceNote(cleanNote);
        repository.save(favorite);
        auditService.record("charging.favorite.price", stationId + " -> " + price.toPlainString());
    }

    private static BigDecimal validPrice(Double input) {
        if (input == null) {
            return null;
        }
        double value = input;
        if (!Double.isFinite(value) || value <= 0 || value > MAX_DIRECT_PRICE) {
            throw new IllegalArgumentException("Der Preis muss groesser als 0 und hoechstens "
                    + MAX_DIRECT_PRICE + " EUR/kWh sein.");
        }
        BigDecimal exact = BigDecimal.valueOf(value);
        if (exact.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("Der Preis darf hoechstens drei Nachkommastellen haben.");
        }
        return exact.setScale(3, RoundingMode.UNNECESSARY);
    }

    private static String cleanNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("Die Notiz darf hoechstens " + MAX_NOTE_LENGTH + " Zeichen lang sein.");
        }
        return trimmed;
    }

    /** Entfernt Favorit und Belegungszeilen; die Entitaet markiert der Poller beim naechsten Lauf. */
    @Transactional
    public void remove(String stationId) {
        repository.findByStationId(stationId).ifPresent(favorite -> {
            repository.delete(favorite);
            tracker.forgetStation(stationId);
            forecastService.forget(stationId);
            patternService.evict(stationId);
            auditService.record("charging.favorite.remove", stationId + " (" + favorite.getDisplayName() + ")");
            log.info("Ladesaeule {} aus den Favoriten entfernt", stationId);
        });
    }
}

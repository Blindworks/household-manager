package com.household.manager.charging;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Letzter erfolgreicher Stand der Quelle, nur im Speicher. Bleibt bei einem Fehler
 * erhalten (Frontend zeigt "Stand von"), ist nach einem Neustart leer bis zum ersten Poll.
 */
@Component
public class ChargingSnapshot {

    private volatile Map<String, ChargingStation> areaStations = Map.of();
    private volatile Map<String, ChargingStationDetails> favoriteDetails = Map.of();
    private volatile Instant lastAreaPolledAt;
    private volatile Instant lastFavoritePolledAt;

    public void updateArea(List<ChargingStation> stations, Instant at) {
        Map<String, ChargingStation> byId = new LinkedHashMap<>();
        stations.forEach(s -> byId.put(s.stationId(), s));
        areaStations = Map.copyOf(byId);
        lastAreaPolledAt = at;
    }

    public void updateFavoriteDetails(Map<String, ChargingStationDetails> details, Instant at) {
        favoriteDetails = Map.copyOf(details);
        lastFavoritePolledAt = at;
    }

    public List<ChargingStation> areaStations() {
        return List.copyOf(areaStations.values());
    }

    public Optional<ChargingStation> station(String stationId) {
        return Optional.ofNullable(areaStations.get(stationId));
    }

    public Optional<ChargingStationDetails> details(String stationId) {
        return Optional.ofNullable(favoriteDetails.get(stationId));
    }

    /**
     * Freie und gesamte Ladepunkte einer Station: Favoriten-Details (minuetlich) vor dem Umkreis-Stand
     * (5 min). Einzige Definition fuer den Lade-Waechter; leer, solange die Station nirgends bekannt ist.
     */
    public Optional<Availability> availability(String stationId) {
        ChargingStationDetails d = favoriteDetails.get(stationId);
        if (d != null) {
            int free = (int) d.chargePoints().stream().filter(p -> p.status() == ChargePointStatus.FREE).count();
            return Optional.of(new Availability(free, d.chargePoints().size()));
        }
        ChargingStation s = areaStations.get(stationId);
        return s == null ? Optional.empty() : Optional.of(new Availability(s.free(), s.total()));
    }

    public record Availability(int free, int total) {
    }

    /** Letzter erfolgreicher Umkreis-Poll; null, solange nie einer gelang. */
    public Instant lastAreaPolledAt() {
        return lastAreaPolledAt;
    }

    /** Juengster erfolgreicher Poll beider Pfade; null, solange nie einer gelang. */
    public Instant lastPolledAt() {
        Instant a = lastAreaPolledAt;
        Instant f = lastFavoritePolledAt;
        if (a == null) {
            return f;
        }
        return f == null || a.isAfter(f) ? a : f;
    }
}

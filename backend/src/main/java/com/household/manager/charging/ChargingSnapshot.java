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

package com.household.manager.charging;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Die EnBW-Stationen der letzten Bereichssuche, nur im Speicher und nur 30 Minuten gueltig.
 * Sie machen eine Station favorisierbar, die ausserhalb des Umkreis-Stands liegt
 * ({@link ChargingFavoriteService#add}); eine neue Suche ersetzt die vorige vollstaendig.
 */
@Component
public class ChargingSearchResults {

    static final Duration VALIDITY = Duration.ofMinutes(30);

    private final Clock clock;
    private volatile Snapshot latest = new Snapshot(Map.of(), Instant.MIN);

    public ChargingSearchResults(Clock clock) {
        this.clock = clock;
    }

    public void remember(List<ChargingStation> stations) {
        Map<String, ChargingStation> byId = new LinkedHashMap<>();
        stations.forEach(s -> byId.put(s.stationId(), s));
        latest = new Snapshot(Map.copyOf(byId), clock.instant());
    }

    public Optional<ChargingStation> find(String stationId) {
        Snapshot current = latest;
        if (clock.instant().isAfter(current.rememberedAt().plus(VALIDITY))) {
            return Optional.empty();
        }
        return Optional.ofNullable(current.stations().get(stationId));
    }

    private record Snapshot(Map<String, ChargingStation> stations, Instant rememberedAt) {
    }
}

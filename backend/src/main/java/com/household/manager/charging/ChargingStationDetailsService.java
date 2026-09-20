package com.household.manager.charging;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ladepunkte einer beliebigen Station auf Anfrage (Antippen in Karte oder Liste). Die
 * Umkreisliste liefert nur Zaehler; nur Favoriten werden regelmaessig im Detail gepollt.
 * Damit ein neugieriges Tippen nicht jedes Mal EnBW fragt, werden Antworten 60 s gehalten.
 *
 * <p>Der Belegungsbeginn kommt hier ausschliesslich aus {@code state.updatedAt} der Quelle
 * ({@link ChargePoint#statusSince()}); ohne Zeitstempel bleibt er leer - der
 * {@link ChargingOccupancyTracker} fuehrt nur Favoriten, und ein geratener Beginn waere
 * genau die falsche Aussage.
 */
@Service
public class ChargingStationDetailsService {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private record Cached(Instant fetchedAt, List<ChargingDtos.ChargePointResponse> points) {
    }

    private final ChargingStationSource source;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ChargingStationDetailsService(ChargingStationSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    public List<ChargingDtos.ChargePointResponse> chargePoints(String stationId) {
        Instant now = clock.instant();
        Cached cached = cache.get(stationId);
        if (cached != null && Duration.between(cached.fetchedAt(), now).compareTo(CACHE_TTL) < 0) {
            return cached.points();
        }
        ChargingStationDetails details = source.stationDetails(stationId);
        List<ChargingDtos.ChargePointResponse> points = details.chargePoints().stream()
                .map(this::pointRow)
                .toList();
        cache.put(stationId, new Cached(now, points));
        return points;
    }

    private ChargingDtos.ChargePointResponse pointRow(ChargePoint point) {
        LocalDateTime since = point.status() == ChargePointStatus.OCCUPIED && point.statusSince() != null
                ? LocalDateTime.ofInstant(point.statusSince(), clock.getZone())
                : null;
        return new ChargingDtos.ChargePointResponse(point.chargePointId(), point.status(), point.maxPowerKw(),
                point.connector(), since, false, point.pricePerKwh());
    }
}

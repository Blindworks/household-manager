package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Liefert Spaziergaenge on-the-fly aus der Tractive-Positionshistorie.
 * Ergebnis wird kurz gecacht, damit wiederholtes Oeffnen des Dialogs
 * nicht die Cloud haemmert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractiveWalkService {

    static final int MAX_DAYS = 14;
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final TractiveApiClient apiClient;
    private final TractiveAuthService authService;
    private final TractiveHomeSettingsService homeSettingsService;

    private final Map<String, CachedWalks> cache = new ConcurrentHashMap<>();

    public List<TractiveWalkDto> getWalks(String trackerId, int days) {
        int clampedDays = Math.clamp(days, 1, MAX_DAYS);

        // Einmal lesen und damit weiterrechnen, damit eine Bewertung einen
        // konsistenten Satz Einstellungen sieht.
        TractiveHomeSettings settings = homeSettingsService.getSettings();
        if (!settings.hasHomeCoordinates()) {
            throw new IllegalStateException(
                    "Kein Zuhause konfiguriert. Bitte unter Admin → Hundetracker-Zuhause festlegen.");
        }

        String cacheKey = trackerId + ":" + clampedDays;
        CachedWalks cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt().isAfter(Instant.now().minus(CACHE_TTL))) {
            return cached.walks();
        }

        TractiveAuth auth = authService.getValidToken()
                .orElseThrow(() -> new TractiveAuthException("Nicht bei Tractive angemeldet."));

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(clampedDays));
        List<List<TractivePositionDto>> segments = apiClient.getPositionHistory(
                auth.getAccessToken(), auth.getUserId(), trackerId, from, to);

        GeoZone home = new GeoZone(settings.homeZoneName(),
                settings.homeLatitude(), settings.homeLongitude(), settings.homeRadiusMeters());
        List<TractiveWalkDto> walks = TractiveWalkDetector.detectWalks(
                segments.stream().flatMap(List::stream).toList(), home);

        cache.put(cacheKey, new CachedWalks(Instant.now(), walks));
        return walks;
    }

    private record CachedWalks(Instant fetchedAt, List<TractiveWalkDto> walks) {
    }
}

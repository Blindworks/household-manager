package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    /**
     * Die Cloud lehnt grosse Abfragefenster ab (Code 7500, Kategorie HISTORY,
     * "The requested time frame is invalid" — real beobachtet bei 7 Tagen);
     * die Tractive-Apps laden die Historie tageweise. Deshalb wird in
     * 24-h-Haeppchen abgerufen.
     */
    private static final Duration HISTORY_CHUNK = Duration.ofHours(24);
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
        List<TractivePositionDto> points = fetchPointsInChunks(auth, trackerId, from, to);

        GeoZone home = new GeoZone(settings.homeZoneName(),
                settings.homeLatitude(), settings.homeLongitude(), settings.homeRadiusMeters());
        List<TractiveWalkDto> walks = TractiveWalkDetector.detectWalks(points, home);

        cache.put(cacheKey, new CachedWalks(Instant.now(), walks));
        return walks;
    }

    /**
     * Einzelne fehlgeschlagene Haeppchen werden toleriert: im Basic-Abo reicht die
     * Historie nur 24 h zurueck, aeltere Fenster antworten dann mit einem Fehler —
     * der juengste Tag soll trotzdem sichtbar sein. Erst wenn ausnahmslos jedes
     * Haeppchen scheitert, ist die Cloud wirklich nicht erreichbar und der letzte
     * Fehler geht an den Aufrufer.
     */
    private List<TractivePositionDto> fetchPointsInChunks(TractiveAuth auth, String trackerId,
                                                          Instant from, Instant to) {
        List<TractivePositionDto> points = new ArrayList<>();
        TractiveException lastError = null;
        int failedChunks = 0;
        int totalChunks = 0;
        for (Instant chunkFrom = from; chunkFrom.isBefore(to); chunkFrom = chunkFrom.plus(HISTORY_CHUNK)) {
            Instant chunkTo = chunkFrom.plus(HISTORY_CHUNK).isBefore(to)
                    ? chunkFrom.plus(HISTORY_CHUNK) : to;
            totalChunks++;
            try {
                apiClient.getPositionHistory(auth.getAccessToken(), auth.getUserId(),
                                trackerId, chunkFrom, chunkTo)
                        .forEach(points::addAll);
            } catch (TractiveException ex) {
                failedChunks++;
                lastError = ex;
                log.warn("Tractive-Positionshistorie {}..{} nicht lesbar: {}",
                        chunkFrom, chunkTo, ex.getMessage());
            }
        }
        if (totalChunks > 0 && failedChunks == totalChunks) {
            throw lastError;
        }
        return points;
    }

    private record CachedWalks(Instant fetchedAt, List<TractiveWalkDto> walks) {
    }
}

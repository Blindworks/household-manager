package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Liefert Spaziergaenge on-the-fly aus der Tractive-Positionshistorie.
 *
 * <p>Zwei real beobachtete Grenzen der Cloud praegen den Abruf: grosse
 * Abfragefenster werden abgelehnt (Code 7500 HISTORY, ab wenigen Tagen) und
 * die Positions-Ressource ist rate-limitiert (HTTP 429, Code 4006). Deshalb
 * wird tageweise geholt, abgeschlossene Tage werden dauerhaft gecacht (sie
 * aendern sich nie mehr), und beim ersten 429 stoppen alle weiteren Aufrufe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TractiveWalkService {

    static final int MAX_DAYS = 14;
    /** Groesser lehnt die Cloud ab (Code 7500 HISTORY, real beobachtet bei 7 Tagen). */
    private static final Duration MAX_CHUNK = Duration.ofHours(24);
    /** Der angebrochene Tag aendert sich laufend und wird nur kurz gecacht. */
    private static final Duration CURRENT_DAY_TTL = Duration.ofMinutes(5);
    /** Nach einem 429 pausieren alle Cloud-Abrufe, sonst schaukelt sich das Limit hoch. */
    private static final Duration RATE_LIMIT_COOLDOWN = Duration.ofSeconds(60);
    /** Lokale Haushaltszeit — wie ueberall im Projekt (Kalender, Scheduler). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final TractiveApiClient apiClient;
    private final TractiveAuthService authService;
    private final TractiveHomeSettingsService homeSettingsService;

    private final Map<DayKey, CachedDay> dayCache = new ConcurrentHashMap<>();
    private volatile Instant rateLimitedUntil = Instant.EPOCH;

    public List<TractiveWalkDto> getWalks(String trackerId, int days) {
        int clampedDays = Math.clamp(days, 1, MAX_DAYS);

        // Einmal lesen und damit weiterrechnen, damit eine Bewertung einen
        // konsistenten Satz Einstellungen sieht.
        TractiveHomeSettings settings = homeSettingsService.getSettings();
        if (!settings.hasHomeCoordinates()) {
            throw new IllegalStateException(
                    "Kein Zuhause konfiguriert. Bitte unter Admin → Hundetracker-Zuhause festlegen.");
        }

        TractiveAuth auth = authService.getValidToken()
                .orElseThrow(() -> new TractiveAuthException("Nicht bei Tractive angemeldet."));

        LocalDate today = LocalDate.now(ZONE);
        List<TractivePositionDto> points = new ArrayList<>();
        boolean cloudBlocked = Instant.now().isBefore(rateLimitedUntil);
        int daysWithData = 0;
        TractiveException lastError = null;

        // Neueste zuerst: bricht das Rate-Limit mittendrin ab, fehlen nur die
        // aeltesten Tage — und der naechste Klick fuellt sie aus dem Cache heraus nach.
        for (int i = 0; i < clampedDays; i++) {
            LocalDate day = today.minusDays(i);
            boolean currentDay = i == 0;
            DayKey key = new DayKey(trackerId, day);
            CachedDay cached = dayCache.get(key);

            boolean cacheFresh = cached != null && (currentDay
                    ? cached.fetchedAt().isAfter(Instant.now().minus(CURRENT_DAY_TTL))
                    : cached.coversFullDay());
            if (cacheFresh) {
                points.addAll(cached.points());
                daysWithData++;
                continue;
            }
            if (cloudBlocked) {
                // Lieber ein veralteter Tagesstand als gar keiner.
                if (cached != null) {
                    points.addAll(cached.points());
                    daysWithData++;
                }
                continue;
            }

            Instant chunkFrom = day.atStartOfDay(ZONE).toInstant();
            Instant naturalEnd = currentDay
                    ? Instant.now() : day.plusDays(1).atStartOfDay(ZONE).toInstant();
            // Am 25-h-Umstellungstag wuerde der Kalendertag das Fenster sprengen;
            // die gekappte Stunde ist fuer Spaziergaenge verschmerzbar.
            Instant cap = chunkFrom.plus(MAX_CHUNK);
            Instant chunkTo = naturalEnd.isBefore(cap) ? naturalEnd : cap;
            try {
                List<TractivePositionDto> dayPoints = new ArrayList<>();
                apiClient.getPositionHistory(auth.getAccessToken(), auth.getUserId(),
                                trackerId, chunkFrom, chunkTo)
                        .forEach(dayPoints::addAll);
                dayCache.put(key, new CachedDay(Instant.now(), !currentDay, List.copyOf(dayPoints)));
                points.addAll(dayPoints);
                daysWithData++;
            } catch (TractiveRateLimitException ex) {
                cloudBlocked = true;
                rateLimitedUntil = Instant.now().plus(RATE_LIMIT_COOLDOWN);
                lastError = ex;
                log.warn("Tractive-Rate-Limit erreicht, restliche Tages-Haeppchen uebersprungen");
                if (cached != null) {
                    points.addAll(cached.points());
                    daysWithData++;
                }
            } catch (TractiveException ex) {
                lastError = ex;
                log.warn("Tractive-Positionshistorie fuer {} nicht lesbar: {}", day, ex.getMessage());
                if (cached != null) {
                    points.addAll(cached.points());
                    daysWithData++;
                }
            }
        }
        pruneOldDays(today);

        if (daysWithData == 0 && lastError != null) {
            if (lastError instanceof TractiveRateLimitException) {
                throw new TractiveException(
                        "Tractive-Rate-Limit erreicht — bitte in etwa einer Minute erneut versuchen.");
            }
            throw lastError;
        }

        GeoZone home = new GeoZone(settings.homeZoneName(),
                settings.homeLatitude(), settings.homeLongitude(), settings.homeRadiusMeters());
        return TractiveWalkDetector.detectWalks(points, home);
    }

    /** Haelt den Cache klein; aeltere Tage kann der Endpunkt ohnehin nie mehr anfragen. */
    private void pruneOldDays(LocalDate today) {
        LocalDate cutoff = today.minusDays(MAX_DAYS);
        dayCache.keySet().removeIf(key -> key.day().isBefore(cutoff));
    }

    private record DayKey(String trackerId, LocalDate day) {
    }

    /** {@code coversFullDay} unterscheidet den fertigen Tag vom angebrochenen. */
    private record CachedDay(Instant fetchedAt, boolean coversFullDay,
                             List<TractivePositionDto> points) {
    }
}

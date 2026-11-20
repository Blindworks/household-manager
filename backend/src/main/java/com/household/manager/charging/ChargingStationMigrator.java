package com.household.manager.charging;

import com.household.manager.model.entity.ChargingFavorite;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Erkennt einen Favoriten, den die Quelle dauerhaft nicht mehr kennt (404), und sucht seine
 * Nachfolgestation im Umkreis-Stand: gleicher Betreiber, hoechstens {@link #MAX_DISTANCE_METERS}
 * entfernt, noch kein Favorit. Genau ein Treffer wird uebernommen, sonst nur gewarnt — geraten
 * wird nie. Anlass: Mainova legte „Am Suedbahnhof 3“ unter neuer EnBW-Id an, der Favorit stand
 * danach grau mit „0/0 frei“ da und die Vorhersage zeigte „0 Tage“ (2026-11-20).
 *
 * <p>Schutz gegen Fehlalarm, etwa wenn die Quelle alles mit 404 beantwortet (falscher Host):
 * die 404 muss {@link #GRACE} lang anhalten, der Umkreis-Stand muss nach dem ersten 404 frisch
 * geholt worden sein, und die alte Id darf darin nicht mehr vorkommen. Zustand nur im Speicher,
 * nach einem Neustart beginnt die Wartezeit neu. Aufgerufen nur aus dem synchronisierten
 * Favoriten-Poll.
 */
@Component
@Slf4j
public class ChargingStationMigrator {

    static final Duration GRACE = Duration.ofMinutes(30);
    static final double MAX_DISTANCE_METERS = 75.0;

    private final ChargingSnapshot snapshot;
    private final ChargingStationMigrationService migrationService;
    private final Clock clock;

    private final Map<String, Instant> notFoundSince = new HashMap<>();
    /** Schon geloggte Warnungen je Favorit und Grund (einmal warnen, nicht minuetlich). */
    private final Set<String> warned = new HashSet<>();

    public ChargingStationMigrator(ChargingSnapshot snapshot, ChargingStationMigrationService migrationService,
                                   Clock clock) {
        this.snapshot = snapshot;
        this.migrationService = migrationService;
        this.clock = clock;
    }

    /** Der Favorit war lesbar: eine laufende Wartezeit verfaellt. */
    public void stationFound(String stationId) {
        notFoundSince.remove(stationId);
        forgetWarnings(stationId);
    }

    /**
     * Der Favorit lieferte 404. Liefert die neue Id, wenn er gerade umgezogen wurde; wirft nie.
     *
     * @param favoriteIds alle aktuellen Favoriten-Ids (ein Favorit kann nie Nachfolger sein)
     */
    public Optional<String> stationNotFound(ChargingFavorite favorite, Set<String> favoriteIds) {
        String oldId = favorite.getStationId();
        Instant now = clock.instant();
        Instant since = notFoundSince.computeIfAbsent(oldId, id -> now);
        if (Duration.between(since, now).compareTo(GRACE) < 0) {
            return Optional.empty();
        }
        Instant areaPolledAt = snapshot.lastAreaPolledAt();
        if (areaPolledAt == null || !areaPolledAt.isAfter(since)) {
            warnOnce(oldId, "Ladesaeulen-Favorit {} ({}) seit {} unbekannt (404), aber kein frischer Umkreis-Stand"
                    + " - keine Nachfolgersuche", oldId, favorite.getDisplayName(), since);
            return Optional.empty();
        }
        List<ChargingStation> area = snapshot.areaStations();
        if (area.stream().anyMatch(s -> s.stationId().equals(oldId))) {
            warnOnce(oldId, "Ladesaeulen-Favorit {} ({}) antwortet 404, steht aber noch im Umkreis - kein Umzug",
                    oldId, favorite.getDisplayName());
            return Optional.empty();
        }
        List<ChargingStation> candidates = successorCandidates(favorite, area, favoriteIds);
        if (candidates.size() != 1) {
            warnOnce(oldId, "Ladesaeulen-Favorit {} ({}) seit {} unbekannt (404), {} Nachfolger-Kandidaten {} - "
                            + "bitte von Hand neu favorisieren", oldId, favorite.getDisplayName(), since,
                    candidates.size(), candidates.stream().map(ChargingStation::stationId).toList());
            return Optional.empty();
        }
        ChargingStation successor = candidates.get(0);
        try {
            migrationService.migrate(oldId, successor);
        } catch (RuntimeException e) {
            warnOnce(oldId, "Ladesaeulen-Favorit {} ({}): Umzug auf {} nicht moeglich: {}", oldId,
                    favorite.getDisplayName(), successor.stationId(), e.getMessage());
            return Optional.empty();
        }
        notFoundSince.remove(oldId);
        forgetWarnings(oldId);
        return Optional.of(successor.stationId());
    }

    /** Stationen am selben Ort beim selben Betreiber; einzige Definition von „Nachfolger“. */
    static List<ChargingStation> successorCandidates(ChargingFavorite favorite, List<ChargingStation> area,
                                                     Set<String> favoriteIds) {
        String operator = normalize(favorite.getOperator());
        return area.stream()
                .filter(s -> !s.stationId().equals(favorite.getStationId()))
                .filter(s -> !favoriteIds.contains(s.stationId()))
                .filter(s -> ChargingAreaFilter.distanceMeters(favorite.getLat(), favorite.getLon(), s.lat(), s.lon())
                        <= MAX_DISTANCE_METERS)
                .filter(s -> operator == null || operator.equals(normalize(s.operator())))
                .toList();
    }

    private static String normalize(String operator) {
        if (operator == null || operator.isBlank()) {
            return null;
        }
        return operator.trim().toLowerCase(Locale.ROOT);
    }

    private void warnOnce(String stationId, String message, Object... args) {
        if (warned.add(stationId + "|" + message)) {
            log.warn(message, args);
        }
    }

    private void forgetWarnings(String stationId) {
        warned.removeIf(key -> key.startsWith(stationId + "|"));
    }
}

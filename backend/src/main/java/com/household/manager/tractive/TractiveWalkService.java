package com.household.manager.tractive;

import com.household.manager.model.entity.TractivePosition;
import com.household.manager.repository.TractivePositionRepository;
import com.household.manager.tractive.dto.TractivePositionDto;
import com.household.manager.tractive.dto.TractiveWalkDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Leitet Spaziergaenge aus der selbst mitgeschriebenen Positionshistorie ab.
 * <p>
 * Frueher holte diese Klasse die Positionen bei jedem Abruf aus der Cloud — in
 * Tages-Haeppchen, mit Cache, Rate-Limit-Behandlung und Teilergebnissen. Das ist
 * entfallen: die Cloud liefert beim Basic-Abo ohnehin nur rund 24 Stunden, und
 * der TractivePositionRecorder schreibt seither jeden Poll mit. Der Lesepfad ist
 * damit eine Bereichsabfrage plus der unveraenderte TractiveWalkDetector.
 * <p>
 * <b>Es wird keine Tractive-Anmeldung mehr gebraucht.</b> Gespeicherte Runden
 * bleiben sichtbar, auch wenn das Token abgelaufen ist — Tractive gibt kein
 * Refresh-Token aus, das passiert also regelmaessig.
 */
@Service
@RequiredArgsConstructor
public class TractiveWalkService {

    /**
     * Obergrenze des abfragbaren Zeitraums — reine Eingabevalidierung, kein
     * Cloud-Schutz mehr. Bewusst gross: die Historie wird nie aufgeraeumt, eine
     * kleinere Grenze wuerde sie an der API wieder abschneiden.
     */
    static final int MAX_DAYS = 365;

    /** Lokale Haushaltszeit — wie ueberall im Projekt (Kalender, Scheduler). */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final TractivePositionRepository repository;
    private final TractiveHomeSettingsService homeSettingsService;

    public List<TractiveWalkDto> getWalks(String trackerId, int days) {
        int clampedDays = Math.clamp(days, 1, MAX_DAYS);

        // Einmal lesen und damit weiterrechnen, damit eine Bewertung einen
        // konsistenten Satz Einstellungen sieht.
        TractiveHomeSettings settings = homeSettingsService.getSettings();
        if (!settings.hasHomeCoordinates()) {
            throw new IllegalStateException(
                    "Kein Zuhause konfiguriert. Bitte unter Admin → Hundetracker-Zuhause festlegen.");
        }

        Instant from = LocalDate.now(ZONE).minusDays(clampedDays - 1L)
                .atStartOfDay(ZONE).toInstant();
        List<TractivePositionDto> points = repository
                .findByTrackerIdAndPositionTimeGreaterThanEqualOrderByPositionTimeAsc(trackerId, from)
                .stream()
                .map(TractiveWalkService::toDto)
                .toList();

        GeoZone home = new GeoZone(settings.homeZoneName(),
                settings.homeLatitude(), settings.homeLongitude(), settings.homeRadiusMeters());
        return TractiveWalkDetector.detectWalks(points, home);
    }

    /**
     * Bildet eine gespeicherte Zeile auf das DTO ab, mit dem der Detektor arbeitet —
     * so bleibt die Erkennungslogik unveraendert und weiter unabhaengig testbar.
     */
    private static TractivePositionDto toDto(TractivePosition position) {
        return new TractivePositionDto(
                List.of(position.getLatitude(), position.getLongitude()),
                position.getAccuracy(),
                position.getSensorUsed(),
                position.getPositionTime().getEpochSecond());
    }
}

package com.household.manager.tractive;

import com.household.manager.tractive.dto.TractiveHardwareDto;
import com.household.manager.tractive.dto.TractivePositionDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Die einzige Definition von "zu Hause". Entity-Mapper und Haustier-API fragen
 * dieselbe Instanz, damit Dashboard-Kachel und Flow-Trigger nicht auseinanderlaufen.
 *
 * <p>Der Tracker wird zu Hause ausgeschaltet, und die Tractive-API kennt dafuer kein
 * Statusfeld – erkennbar ist es nur an einem ausbleibenden Positionsbericht. Weil
 * "Akku unterwegs leergelaufen" genauso aussieht, verlangt diese Deutung zwei
 * unabhaengige Belege: einen gesunden Akkustand und Heimnaehe im weiten Radius.
 *
 * <p>{@link Optional#empty()} bedeutet immer dasselbe: keine Aussage moeglich. Es wird
 * nie ein Zustand geraten – ein Alarm-Flow darf nicht bei jedem GPS-Aussetzer feuern.
 * Die beiden GRUENDE dafuer sind allerdings verschieden zu behandeln, siehe
 * {@link #isHomeConfigured()}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TractiveHomeResolver {

    private final TractiveHomeSettingsService settingsService;

    /** Die Warnung ueber fehlende Home-Koordinaten soll nicht jede Minute im Log stehen. */
    private final AtomicBoolean missingHomeWarned = new AtomicBoolean();

    /**
     * Die Warnung ueber einen Uhren-Versatz soll nicht mehrfach pro Minute und Tier im Log
     * stehen – seit die Haustier-API denselben Resolver bei jedem REST-Aufruf befragt, koennte
     * ein dauerhafter Versatz sonst mehrere WARN-Zeilen pro Minute pro Tier erzeugen.
     */
    private final AtomicBoolean clockSkewWarned = new AtomicBoolean();

    public Optional<HomeVerdict> resolve(TractivePetSnapshot snapshot, Instant now) {
        // Einmal lesen: innerhalb einer Bewertung darf sich die Definition nicht aendern.
        TractiveHomeSettings settings = settingsService.getSettings();

        if (!settings.hasHomeCoordinates()) {
            warnAboutMissingHomeOnce();
            return Optional.empty();
        }
        // Zuruecksetzen, damit ein spaeteres Entfernen der Koordinaten wieder auffaellt.
        missingHomeWarned.set(false);

        TractiveHardwareDto hardware = snapshot.hardware();
        if (hardware != null && hardware.isCharging()) {
            return Optional.of(HomeVerdict.charging());
        }

        TractivePositionDto position = snapshot.position();
        if (position == null || !position.hasCoordinates()) {
            return Optional.empty();
        }

        double distanceMeters = GeoZone.distanceMeters(
                settings.homeLatitude(), settings.homeLongitude(),
                position.latitude(), position.longitude());
        Long ageMinutes = positionAgeMinutes(position, now);
        boolean stale = ageMinutes != null && ageMinutes >= settings.poweredOffAfterMinutes();

        if (stale && looksPoweredOffAtHome(hardware, distanceMeters, settings)) {
            return Optional.of(HomeVerdict.poweredOff(distanceMeters, ageMinutes));
        }
        return Optional.of(HomeVerdict.fromPosition(
                distanceMeters <= settings.homeRadiusMeters(), stale, distanceMeters, ageMinutes));
    }

    /**
     * Ist ueberhaupt ein Zuhause hinterlegt? Trennt die beiden Gruende, aus denen
     * {@link #resolve} leer liefert: "keine Definition" (der Nutzer hat die Koordinaten
     * entfernt) und "keine Positionsdaten" (der Tracker ist still). Nur der erste Fall
     * darf die Entitaet auf {@code unavailable} setzen – Stille ist zu Hause der
     * Normalzustand und soll den letzten Wert behalten.
     */
    public boolean isHomeConfigured() {
        return settingsService.getSettings().hasHomeCoordinates();
    }

    private void warnAboutMissingHomeOnce() {
        if (missingHomeWarned.compareAndSet(false, true)) {
            log.warn("Kein Zuhause hinterlegt (Admin -> Hundetracker-Einstellungen) – "
                    + "die Entitaet 'zu Hause' wird nicht gemeldet.");
        }
    }

    /** {@code null}, wenn der Bericht keinen Zeitstempel hat – dann gilt er als frisch. */
    private Long positionAgeMinutes(TractivePositionDto position, Instant now) {
        Instant reportedAt = position.reportedAt();
        if (reportedAt == null) {
            return null;
        }
        long minutes = Duration.between(reportedAt, now).toMinutes();
        if (minutes < 0) {
            // Uhren-Versatz zur Tractive-Cloud. Auf 0 geklemmt gilt der Bericht als frisch,
            // was fail-safe ist – aber ein dauerhafter Versatz wuerde Regel 4 unerreichbar
            // machen und einen zu Hause ausgeschalteten Tracker fuer immer als "unterwegs"
            // melden. Deshalb sichtbar machen statt still schlucken.
            if (clockSkewWarned.compareAndSet(false, true)) {
                log.warn("Tractive-Positionsbericht liegt {} Minuten in der Zukunft – Uhren-Versatz?",
                        -minutes);
            }
            return 0L;
        }
        return minutes;
    }

    /**
     * Fail-safe: ohne Akkustand wird nicht auf "ausgeschaltet" geschlossen. Sonst wuerde
     * ein unterwegs verlorener Tracker als "zu Hause" gemeldet.
     */
    private boolean looksPoweredOffAtHome(TractiveHardwareDto hardware, double distanceMeters,
                                          TractiveHomeSettings settings) {
        if (hardware == null || hardware.batteryLevel() == null) {
            return false;
        }
        return hardware.batteryLevel() >= settings.poweredOffMinBatteryPercent()
                && distanceMeters <= settings.effectiveArrivalRadiusMeters();
    }
}

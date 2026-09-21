package com.household.manager.sun;

import com.household.manager.tractive.TractiveHomeSettings;
import com.household.manager.tractive.TractiveHomeSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.shredzone.commons.suncalc.SunPosition;
import org.shredzone.commons.suncalc.SunTimes;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Einzige Definition der Sonnenzeiten des Haushalts. Entitaet ({@code sensor.sun}),
 * Trigger-Node ({@code sun-trigger}) und Sonnenausdruecke in {@code time-condition}
 * fragen ausschliesslich diese Klasse, damit „Sonnenuntergang" nie zweimal
 * definiert ist. Nur dieses Paket importiert {@code commons-suncalc}.
 *
 * <p>Die Koordinaten sind das Hundetracker-Zuhause ({@link TractiveHomeSettingsService})
 * — bewusste Kopplung (Nutzerentscheidung 2026-09-21), bei jedem Aufruf frisch
 * gelesen, damit eine Aenderung im Admin beim naechsten Minutenlauf gilt.
 *
 * <p><b>Lesen wirft nie.</b> Ohne Koordinaten, bei einem Fehler der Einstellungen
 * oder wenn die Bibliothek fuer ein Ereignis {@code null} liefert (Polarfall), kommt
 * {@link Optional#empty()} — die Aufrufer laufen minuetlich bzw. im Flow-Executor,
 * eine Ausnahme hier duerfte sie nicht lahmlegen.
 */
@Service
@Slf4j
public class SunTimesService {

    private final TractiveHomeSettingsService homeSettings;
    private final Clock clock;

    public SunTimesService(TractiveHomeSettingsService homeSettings, Clock clock) {
        this.homeSettings = homeSettings;
        this.clock = clock;
    }

    /** Die vier Sonnenzeiten des Kalendertages {@code date} in Haushaltszeit. */
    public Optional<SunDayTimes> timesFor(LocalDate date) {
        Optional<TractiveHomeSettings> home = configuredHome();
        if (home.isEmpty()) {
            return Optional.empty();
        }
        try {
            ZoneId zone = clock.getZone();
            SunTimes visual = compute(date, home.get(), SunTimes.Twilight.VISUAL, zone);
            SunTimes civil = compute(date, home.get(), SunTimes.Twilight.CIVIL, zone);
            if (visual.getRise() == null || visual.getSet() == null
                    || civil.getRise() == null || civil.getSet() == null) {
                log.warn("Sonnenzeiten fuer {} unvollstaendig (Polarfall?) – keine Sonnenzeiten", date);
                return Optional.empty();
            }
            return Optional.of(new SunDayTimes(date, civil.getRise(), visual.getRise(), visual.getSet(), civil.getSet()));
        } catch (RuntimeException ex) {
            log.warn("Sonnenzeiten fuer {} nicht berechenbar: {}", date, ex.getMessage());
            return Optional.empty();
        }
    }

    /** Sonnenhoehe in Grad ueber dem Horizont (negativ = unter dem Horizont). */
    public Optional<Double> elevationAt(ZonedDateTime moment) {
        Optional<TractiveHomeSettings> home = configuredHome();
        if (home.isEmpty()) {
            return Optional.empty();
        }
        try {
            SunPosition position = SunPosition.compute()
                    .on(moment)
                    .at(home.get().homeLatitude(), home.get().homeLongitude())
                    .execute();
            return Optional.of(position.getAltitude());
        } catch (RuntimeException ex) {
            log.warn("Sonnenhoehe nicht berechenbar: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TractiveHomeSettings> configuredHome() {
        try {
            TractiveHomeSettings settings = homeSettings.getSettings();
            return settings.hasHomeCoordinates() ? Optional.of(settings) : Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Zuhause-Koordinaten nicht lesbar – keine Sonnenzeiten: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static SunTimes compute(LocalDate date, TractiveHomeSettings home, SunTimes.Twilight twilight, ZoneId zone) {
        return SunTimes.compute()
                .timezone(zone)
                .on(date)
                .at(home.homeLatitude(), home.homeLongitude())
                .twilight(twilight)
                .oneDay()
                .execute();
    }
}

package com.household.manager.sun;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Spiegelt den Sonnenstand als Entitaet {@code sensor.sun} in den Entity-State-Layer:
 * State = Phase ({@code day}/{@code dusk}/{@code night}/{@code dawn}), Attribute die
 * heutigen Sonnenzeiten, die naechsten Auf-/Untergaenge und die Sonnenhoehe.
 *
 * <p>Laeuft minuetlich; der Entity-State-Layer feuert {@code EntityStateChangedEvent}
 * nur bei Wertaenderung, also viermal am Tag — die uebrigen Laeufe kosten nichts.
 *
 * <p>Ohne konfiguriertes Zuhause wird die Entitaet {@code unavailable} <b>mit
 * erhaltenen Attributen</b> (Muster Zigbee/Blink: {@code EntityStateWriter.upsert}
 * ueberschreibt sie sonst komplett) — nie eine geratene Phase. Sie wird auch dann
 * angelegt, damit im Entity-Katalog sichtbar ist, dass etwas fehlt.
 *
 * <p>Bewusst kein {@code deviceClass} (siehe Blink-Absatz in CLAUDE.md).
 */
@Component
@Slf4j
public class SunEntityPublisher {

    static final String ENTITY_ID = "sensor.sun";
    static final String FRIENDLY_NAME = "Sonne";
    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ISO_LOCAL_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final SunTimesService sunTimes;
    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;
    private final Clock clock;

    public SunEntityPublisher(SunTimesService sunTimes,
                              EntityStateService entityStateService,
                              EntityStateResponseMapper responseMapper,
                              Clock clock) {
        this.sunTimes = sunTimes;
        this.entityStateService = entityStateService;
        this.responseMapper = responseMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${sun.publish-interval-ms:60000}",
               initialDelayString = "${sun.publish-initial-delay-ms:15000}")
    public void publish() {
        try {
            publishNow();
        } catch (RuntimeException ex) {
            log.warn("Sonnenstand konnte nicht gemeldet werden: {}", ex.getMessage());
        }
    }

    private void publishNow() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        Optional<SunDayTimes> today = sunTimes.timesFor(now.toLocalDate());
        if (today.isEmpty()) {
            markUnavailable();
            return;
        }
        SunDayTimes times = today.get();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("dawn", HH_MM.format(times.dawn()));
        attributes.put("sunrise", HH_MM.format(times.sunrise()));
        attributes.put("sunset", HH_MM.format(times.sunset()));
        attributes.put("dusk", HH_MM.format(times.dusk()));
        Supplier<Optional<SunDayTimes>> tomorrow = memoize(() -> sunTimes.timesFor(times.date().plusDays(1)));
        nextOf(SunEvent.SUNRISE, times, now, tomorrow).ifPresent(v -> attributes.put("nextSunrise", v));
        nextOf(SunEvent.SUNSET, times, now, tomorrow).ifPresent(v -> attributes.put("nextSunset", v));
        sunTimes.elevationAt(now).ifPresent(e -> attributes.put("elevation", Math.round(e * 10.0) / 10.0));
        report(times.phaseAt(now).key(), attributes);
    }

    /**
     * Das naechste Vorkommen nach jetzt: heute, wenn es noch bevorsteht, sonst morgen.
     *
     * <p>{@code tomorrow} wird je Lauf hoechstens einmal berechnet (lazy, nur wenn
     * tatsaechlich ein heutiges Ereignis schon vorbei ist) — sonst kostet jeder Aufruf
     * dieser Methode einen eigenen Settings-Read plus Astronomie-Berechnung fuer denselben
     * Tag. So stammen {@code nextSunrise} und {@code nextSunset} zudem aus demselben
     * Koordinatensatz, falls sich das Zuhause zwischen den beiden Aufrufen aendert.
     *
     * <p>{@code isAfter(now)} statt {@code !isBefore(now)} passt bewusst zur halboffenen
     * Fensterregel aus {@link SunDayTimes#phaseAt}: genau zum Sonnenuntergang ist der State
     * schon {@code dusk}, also zeigt {@code nextSunset} in diesem Moment folgerichtig
     * schon auf morgen.
     */
    private Optional<String> nextOf(SunEvent event, SunDayTimes today, ZonedDateTime now,
                                     Supplier<Optional<SunDayTimes>> tomorrow) {
        ZonedDateTime todayAt = today.timeOf(event);
        if (todayAt.isAfter(now)) {
            return Optional.of(ISO_LOCAL_SECONDS.format(todayAt.toLocalDateTime()));
        }
        return tomorrow.get().map(t -> ISO_LOCAL_SECONDS.format(t.timeOf(event).toLocalDateTime()));
    }

    /** Memoisiert einen Supplier fuer die Dauer eines einzelnen Laufs — kein Caching darueber hinaus. */
    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private boolean computed;
            private T value;

            @Override
            public T get() {
                if (!computed) {
                    value = delegate.get();
                    computed = true;
                }
                return value;
            }
        };
    }

    private void markUnavailable() {
        Optional<EntityState> existing = entityStateService.getByEntityId(ENTITY_ID);
        if (existing.isPresent() && STATE_UNAVAILABLE.equals(existing.get().getState())) {
            return;
        }
        Map<String, Object> kept = existing
                .map(e -> responseMapper.parseAttributes(e.getAttributes()))
                .orElse(Map.of());
        report(STATE_UNAVAILABLE, kept);
    }

    private void report(String state, Map<String, Object> attributes) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(ENTITY_ID)
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.SUN)
                .sourceRef("sun")
                .friendlyName(FRIENDLY_NAME)
                .state(state)
                .attributes(attributes)
                .build());
    }
}

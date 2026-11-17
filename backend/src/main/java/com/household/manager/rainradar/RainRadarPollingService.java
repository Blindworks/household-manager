package com.household.manager.rainradar;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.model.entity.EntityState;
import com.household.manager.systemlog.SystemJobMonitor;
import com.household.manager.tractive.TractiveHomeSettings;
import com.household.manager.tractive.TractiveHomeSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pollt alle 5 Minuten das Regenradar fuer das Zuhause und meldet drei Entitaeten:
 * {@code binary_sensor.rain_radar_raining} (es regnet), {@code binary_sensor.rain_radar_soon}
 * (es regnet oder Regen binnen {@code soonMinutes}) und {@code sensor.rain_radar_intensity} (mm/h).
 *
 * <p>Koordinaten sind das Hundetracker-Zuhause (wie beim Sonnenstand). Ohne Zuhause oder
 * abgeschaltet ⇒ {@code reportInactive}. Ein einzelner Fehlabruf aendert nichts (der letzte
 * Wert bleibt, sonst feuerten Flows nach jedem Aussetzer erneut); erst nach
 * {@code failuresBeforeUnavailable} Fehlabrufen in Folge werden die Entitaeten
 * {@code unavailable} mit erhaltenen Attributen. Eine Messung aelter als
 * {@code maxMeasurementAgeMinutes} zaehlt als Fehlabruf. Wirft nie.
 *
 * <p>Bewusst kein {@code deviceClass} (siehe Blink-Absatz in CLAUDE.md).
 */
@Service
@Slf4j
public class RainRadarPollingService {

    static final String RAINING_ENTITY_ID = "binary_sensor.rain_radar_raining";
    static final String SOON_ENTITY_ID = "binary_sensor.rain_radar_soon";
    static final String INTENSITY_ENTITY_ID = "sensor.rain_radar_intensity";
    private static final List<String> ENTITY_IDS = List.of(RAINING_ENTITY_ID, SOON_ENTITY_ID, INTENSITY_ENTITY_ID);
    private static final String STATE_UNAVAILABLE = "unavailable";
    private static final String SOURCE_REF = "home";
    private static final DateTimeFormatter ISO_LOCAL_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RainRadarClient client;
    private final RainRadarProperties properties;
    private final TractiveHomeSettingsService homeSettings;
    private final EntityStateService entityStateService;
    private final EntityStateResponseMapper responseMapper;
    private final SystemJobMonitor jobMonitor;
    private final Clock clock;

    private int consecutiveFailures;

    public RainRadarPollingService(RainRadarClient client, RainRadarProperties properties,
                                   TractiveHomeSettingsService homeSettings, EntityStateService entityStateService,
                                   EntityStateResponseMapper responseMapper, SystemJobMonitor jobMonitor, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.homeSettings = homeSettings;
        this.entityStateService = entityStateService;
        this.responseMapper = responseMapper;
        this.jobMonitor = jobMonitor;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${rain-radar.poll-interval-ms:300000}",
               initialDelayString = "${rain-radar.initial-delay-ms:30000}")
    public synchronized void poll() {
        try {
            pollNow();
        } catch (RuntimeException ex) {
            log.warn("Regenradar konnte nicht gemeldet werden: {}", ex.getMessage());
            jobMonitor.reportFailure("Regenradar: " + ex.getMessage());
        }
    }

    private void pollNow() {
        if (!properties.isEnabled()) {
            jobMonitor.reportInactive("Regenradar deaktiviert");
            return;
        }
        Optional<TractiveHomeSettings> home = configuredHome();
        if (home.isEmpty()) {
            jobMonitor.reportInactive("Kein Zuhause hinterlegt (Admin → Hundetracker-Zuhause)");
            markUnavailable();
            return;
        }
        Instant now = clock.instant();
        Optional<RainRadarReading> reading;
        try {
            List<RainRadarFrame> frames = client.fetch(home.get().homeLatitude(), home.get().homeLongitude(), now);
            reading = RainRadarEvaluator.evaluate(frames, now,
                    properties.getThresholdMmPerHour(), properties.getSoonMinutes());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception ex) {
            recordFailure("Abruf gescheitert: " + ex.getMessage());
            return;
        }
        if (reading.isEmpty()) {
            recordFailure("Keine gemessenen Radarbilder in der Antwort");
            return;
        }
        Duration age = Duration.between(reading.get().measuredAt(), now);
        if (age.toMinutes() > properties.getMaxMeasurementAgeMinutes()) {
            recordFailure("Juengste Radarmessung ist " + age.toMinutes() + " Minuten alt");
            return;
        }
        consecutiveFailures = 0;
        report(reading.get());
    }

    private void recordFailure(String reason) {
        consecutiveFailures++;
        log.warn("Regenradar: {} ({} in Folge)", reason, consecutiveFailures);
        jobMonitor.reportFailure(reason);
        if (consecutiveFailures >= properties.getFailuresBeforeUnavailable()) {
            markUnavailable();
        }
    }

    private Optional<TractiveHomeSettings> configuredHome() {
        try {
            TractiveHomeSettings settings = homeSettings.getSettings();
            return settings.hasHomeCoordinates() ? Optional.of(settings) : Optional.empty();
        } catch (RuntimeException ex) {
            log.warn("Zuhause-Koordinaten nicht lesbar – kein Regenradar: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private void report(RainRadarReading reading) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("measuredAt", localTime(reading.measuredAt()));
        attributes.put("mmPerHour", round(reading.mmPerHour()));
        attributes.put("peakNextHourMmPerHour", round(reading.peakNextHourMmPerHour()));
        if (reading.expectedRainAt() != null) {
            attributes.put("expectedRainAt", localTime(reading.expectedRainAt()));
            attributes.put("minutesUntilRain", reading.minutesUntilRain());
        }
        attributes.put("thresholdMmPerHour", properties.getThresholdMmPerHour());
        attributes.put("source", "DWD-Radar via Bright Sky");

        reportState(RAINING_ENTITY_ID, EntityDomain.BINARY_SENSOR, "Regen am Haus",
                reading.raining() ? "on" : "off", attributes);
        reportState(SOON_ENTITY_ID, EntityDomain.BINARY_SENSOR, "Regen in Kürze",
                reading.rainSoon() ? "on" : "off", attributes);
        Map<String, Object> intensityAttributes = new LinkedHashMap<>(attributes);
        intensityAttributes.put("unit", "mm/h");
        reportState(INTENSITY_ENTITY_ID, EntityDomain.SENSOR, "Regenintensität",
                String.valueOf(round(reading.mmPerHour())), intensityAttributes);
    }

    private void markUnavailable() {
        for (String entityId : ENTITY_IDS) {
            Optional<EntityState> existing = entityStateService.getByEntityId(entityId);
            if (existing.isEmpty() || STATE_UNAVAILABLE.equals(existing.get().getState())) {
                continue;
            }
            Map<String, Object> kept = responseMapper.parseAttributes(existing.get().getAttributes());
            reportState(entityId, existing.get().getDomain(), existing.get().getFriendlyName(), STATE_UNAVAILABLE, kept);
        }
    }

    private void reportState(String entityId, EntityDomain domain, String name, String state,
                             Map<String, Object> attributes) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(entityId)
                .domain(domain)
                .source(EntitySource.RAIN_RADAR)
                .sourceRef(SOURCE_REF)
                .friendlyName(name)
                .state(state)
                .attributes(attributes)
                .build());
    }

    private String localTime(Instant instant) {
        return ISO_LOCAL_SECONDS.format(LocalDateTime.ofInstant(instant, clock.getZone()));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}

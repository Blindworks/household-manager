package com.household.manager.meross.service;

import com.household.manager.entitystate.EntityDomain;
import com.household.manager.entitystate.EntityIds;
import com.household.manager.entitystate.EntitySource;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.entitystate.EntityStateUpdate;
import com.household.manager.entitystate.PowerHistoryRecorder;
import com.household.manager.entitystate.PowerSensorReachability;
import com.household.manager.entitystate.mapper.EntityStateResponseMapper;
import com.household.manager.meross.config.MerossElectricityPollingProperties;
import com.household.manager.meross.dto.MerossElectricityReading;
import com.household.manager.meross.dto.MerossPlugPollResult;
import com.household.manager.model.entity.EntityState;
import com.household.manager.systemlog.SystemJobMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pollt den Momentanverbrauch aller messfähigen Meross-Steckdosen und spiegelt ihn
 * als {@code sensor.meross_<uuid>_power} in die Entity-State-Schicht (Basis für die
 * Verbraucher-Kachel, die Watt-Anzeige der Schalter und Flow-Trigger wie
 * „Waschmaschine fertig"). Neue Steckdosen erscheinen ohne Konfiguration.
 * <p>
 * <b>Ausfall:</b> eine nicht erreichbare Steckdose meldet bewusst kein
 * {@code unavailable} — ein Zustandswechsel ließe flankengetriggerte Flows feuern.
 * Der Leistungswert bleibt stehen, der Sensor bekommt das Attribut
 * {@code reachable: false} ({@link PowerSensorReachability}), die Historie eine Lücke,
 * und {@code binary_sensor.meross_<uuid>_online} geht auf {@code off}. Meldet die
 * Cloud das Gerät als offline, gilt das sofort; scheitert nur der Abruf, erst nach
 * {@value #FAILURES_BEFORE_UNREACHABLE} Läufen in Folge.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MerossElectricityPollingService {

    static final int FAILURES_BEFORE_UNREACHABLE = 3;

    private final MerossElectricityPollingProperties properties;
    private final MerossDeviceService merossDeviceService;
    private final EntityStateService entityStateService;
    private final SystemJobMonitor jobMonitor;
    private final EntityStateResponseMapper responseMapper;
    private final PowerHistoryRecorder powerHistoryRecorder;
    private final Clock clock;

    /** Fehlgeschlagene Abrufe in Folge je Gerät; nur im Speicher. */
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    @Scheduled(fixedDelayString = "${meross.electricity.polling.interval-ms:60000}",
            initialDelayString = "${meross.electricity.polling.initial-delay-ms:20000}")
    public void poll() {
        if (!properties.isEnabled()) {
            jobMonitor.reportInactive("Meross-Verbrauchs-Polling deaktiviert");
            return;
        }
        if (!merossDeviceService.isConfigured()) {
            jobMonitor.reportInactive("Meross nicht konfiguriert");
            return;
        }
        Optional<MerossPlugPollResult> result = readAllQuietly();
        if (result.isEmpty()) {
            return;
        }
        Map<String, EntityState> powerSensors = loadPowerSensors();
        for (MerossElectricityReading reading : result.get().readings()) {
            reportReachable(reading, powerSensors.get(powerEntityId(reading.deviceId())));
        }
        for (MerossPlugPollResult.UnreachablePlug plug : result.get().unreachable()) {
            handleUnreachable(plug, powerSensors.get(powerEntityId(plug.deviceId())));
        }
    }

    private Optional<MerossPlugPollResult> readAllQuietly() {
        try {
            return Optional.of(merossDeviceService.readElectricityOfAllPlugs());
        } catch (Exception ex) {
            // Bewusst kein 'unavailable'-Report: ein transienter Lesefehler soll keinen
            // Zustandsübergang erzeugen, sonst feuern flankengetriggerte Flows fehl.
            log.warn("Meross electricity poll failed: {}", ex.getMessage());
            // Einzelne offline Steckdosen fängt der Service ab; hier scheitert nur Login/Geräteliste.
            jobMonitor.reportFailure(ex.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, EntityState> loadPowerSensors() {
        try {
            return entityStateService.find(EntityDomain.SENSOR, EntitySource.MEROSS).stream()
                    .collect(Collectors.toMap(EntityState::getEntityId, Function.identity(), (a, b) -> a));
        } catch (Exception ex) {
            log.warn("Meross power sensors could not be loaded: {}", ex.getMessage());
            return Map.of();
        }
    }

    private void reportReachable(MerossElectricityReading reading, EntityState previous) {
        consecutiveFailures.remove(reading.deviceId());
        String watts = reading.powerWatts().toPlainString();
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("unit", "W");
        attributes.put("deviceClass", "power");
        attributes.put(PowerSensorReachability.ATTRIBUTE, true);
        if (reading.voltageVolts() != null) {
            attributes.put("voltage", reading.voltageVolts());
        }
        if (reading.currentAmps() != null) {
            attributes.put("current", reading.currentAmps());
        }
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(powerEntityId(reading.deviceId()))
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.MEROSS)
                .sourceRef(reading.deviceId())
                .friendlyName(reading.deviceName() + " Leistung")
                .state(watts)
                .attributes(attributes)
                .build());
        reportOnline(reading.deviceId(), reading.deviceName(), true);

        // Die Lücke der Historie schließen: bei unverändertem Wert feuert kein
        // Zustandswechsel, der Recorder sähe die Rückkehr sonst nie.
        if (previous != null && isUnreachable(previous) && watts.equals(previous.getState())) {
            powerHistoryRecorder.recordPoint(previous.getEntityId(), now(), reading.powerWatts().doubleValue());
        }
    }

    private void handleUnreachable(MerossPlugPollResult.UnreachablePlug plug, EntityState powerSensor) {
        if (powerSensor == null) {
            // Nie gemessen: keine Energiemessung bekannt, nichts zu melden.
            return;
        }
        if (plug.reason() == MerossPlugPollResult.Reason.READ_FAILED) {
            int failures = consecutiveFailures.merge(plug.deviceId(), 1, Integer::sum);
            if (failures < FAILURES_BEFORE_UNREACHABLE) {
                return;
            }
        }
        reportOnline(plug.deviceId(), plug.deviceName(), false);
        if (isUnreachable(powerSensor)) {
            return;
        }
        LocalDateTime now = now();
        Map<String, Object> attributes = new HashMap<>(responseMapper.parseAttributes(powerSensor.getAttributes()));
        attributes.put(PowerSensorReachability.ATTRIBUTE, false);
        attributes.put(PowerSensorReachability.SINCE_ATTRIBUTE, now.toString());
        // Zustand bleibt stehen: kein Zustandswechsel, also kein Flow-Trigger.
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(powerSensor.getEntityId())
                .domain(EntityDomain.SENSOR)
                .source(EntitySource.MEROSS)
                .sourceRef(plug.deviceId())
                .friendlyName(powerSensor.getFriendlyName())
                .state(powerSensor.getState())
                .attributes(attributes)
                .build());
        powerHistoryRecorder.recordPoint(powerSensor.getEntityId(), now, null);
        log.info("Meross plug unreachable ({}): {}", plug.reason(), plug.deviceName());
    }

    private void reportOnline(String deviceId, String deviceName, boolean online) {
        entityStateService.reportState(EntityStateUpdate.builder()
                .entityId(EntityIds.build(EntityDomain.BINARY_SENSOR, EntitySource.MEROSS, deviceId, "online"))
                .domain(EntityDomain.BINARY_SENSOR)
                .source(EntitySource.MEROSS)
                .sourceRef(deviceId)
                .friendlyName(deviceName + " erreichbar")
                .state(online ? "on" : "off")
                .attributes(Map.of("deviceClass", "connectivity"))
                .build());
    }

    private boolean isUnreachable(EntityState entity) {
        return PowerSensorReachability.isUnreachable(responseMapper.parseAttributes(entity.getAttributes()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    static String powerEntityId(String deviceId) {
        return EntityIds.build(EntityDomain.SENSOR, EntitySource.MEROSS, deviceId, "power");
    }
}

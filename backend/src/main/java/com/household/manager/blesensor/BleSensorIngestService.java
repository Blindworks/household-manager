package com.household.manager.blesensor;

import com.household.manager.blesensor.BleSensorDtos.ReadingItem;
import com.household.manager.blesensor.BleSensorDtos.ReadingsRequest;
import com.household.manager.entitystate.EntityStateService;
import com.household.manager.model.entity.BleSensor;
import com.household.manager.repository.BleSensorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Verarbeitet die Sammelmeldung des Tablets. Strukturfehler (fehlende Liste, zu viele Eintraege,
 * ungueltige MAC-Syntax) lehnen die ganze Meldung ab (400); ein einzelner unplausibler Wert wird
 * uebersprungen und geloggt, die uebrigen Eintraege laufen weiter (Muster Blink-Bewegungs-Webhook).
 * Nur AKTIVE Sensoren bekommen Historie und Entitaeten.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BleSensorIngestService {

    static final int MAX_READINGS = 50;
    private static final BigDecimal MIN_TEMP = new BigDecimal("-40");
    private static final BigDecimal MAX_TEMP = new BigDecimal("100");
    private static final BigDecimal MIN_HUM = BigDecimal.ZERO;
    private static final BigDecimal MAX_HUM = new BigDecimal("100");
    private static final int MAX_MV = 5000;

    private final BleSensorRepository repository;
    private final BleSensorReadingRecorder recorder;
    private final BleSensorEntityMapper mapper;
    private final EntityStateService entityStateService;
    private final Clock clock;

    public void ingest(ReadingsRequest request) {
        validateStructure(request);
        LocalDateTime now = LocalDateTime.now(clock);
        String tabletId = request.tabletId().trim();
        for (ReadingItem item : request.readings()) {
            String mac = BleMacs.normalize(item.mac());   // Syntax ist schon geprueft
            try {
                ingestOne(tabletId, mac, item, now);
            } catch (Exception ex) {
                log.warn("BLE-Meldung fuer {} nicht verarbeitet: {}", mac, ex.getMessage(), ex);
            }
        }
    }

    private void ingestOne(String tabletId, String mac, ReadingItem item, LocalDateTime now) {
        if (!plausible(item)) {
            log.warn("BLE-Meldung fuer {} uebersprungen, unplausible Werte: {}", mac, item);
            return;
        }
        long age = item.ageSeconds() == null ? 0 : Math.max(0, item.ageSeconds());
        LocalDateTime measuredAt = now.minusSeconds(age);

        BleSensor sensor = repository.findByMac(mac).orElseGet(() -> BleSensor.builder()
                .mac(mac).active(false).createdAt(now).build());
        sensor.setTabletId(tabletId);
        sensor.setLastSeenAt(measuredAt);
        sensor.setLastTemperature(toColumnScale(item.temperature()));
        sensor.setLastHumidity(toColumnScale(item.humidity()));
        sensor.setLastBatteryMv(item.batteryMillivolts());
        sensor.setLastRssi(item.rssi());
        BleSensor saved = repository.save(sensor);

        if (!saved.isActive()) {
            return;
        }
        recorder.record(saved, measuredAt);
        mapper.updates(saved).forEach(entityStateService::reportState);
    }

    private static boolean plausible(ReadingItem item) {
        if (!sane(item.temperature()) || !sane(item.humidity())) {
            return false;
        }
        if (item.temperature() == null && item.humidity() == null) {
            return false;
        }
        if (item.temperature() != null
                && (item.temperature().compareTo(MIN_TEMP) < 0 || item.temperature().compareTo(MAX_TEMP) > 0)) {
            return false;
        }
        if (item.humidity() != null
                && (item.humidity().compareTo(MIN_HUM) < 0 || item.humidity().compareTo(MAX_HUM) > 0)) {
            return false;
        }
        return item.batteryMillivolts() == null || (item.batteryMillivolts() >= 0 && item.batteryMillivolts() <= MAX_MV);
    }

    private static void validateStructure(ReadingsRequest request) {
        if (request == null || request.tabletId() == null || request.tabletId().isBlank()) {
            throw new IllegalArgumentException("tabletId fehlt.");
        }
        if (request.readings() == null) {
            throw new IllegalArgumentException("readings fehlt.");
        }
        if (request.readings().size() > MAX_READINGS) {
            throw new IllegalArgumentException("Hoechstens " + MAX_READINGS + " Sensoren je Meldung.");
        }
        for (ReadingItem item : request.readings()) {
            if (item == null) {
                throw new IllegalArgumentException("Leerer Eintrag in readings.");
            }
            BleMacs.normalize(item.mac());   // wirft IllegalArgumentException bei ungueltiger Syntax
        }
    }

    /** Schutz vor BigDecimal-Exponenten-Bomben (1e999999999 liesse setScale/compareTo explodieren). */
    private static boolean sane(BigDecimal value) {
        return value == null || (Math.abs(value.scale()) <= 6 && value.precision() <= 12);
    }

    /** Spaltenmass DECIMAL(5,2): sonst weicht der Speicherwert (21.5625) vom DB-Wert (21.56) ab. */
    private static BigDecimal toColumnScale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}

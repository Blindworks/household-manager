package com.household.manager.entitystate;

import com.household.manager.model.entity.EntityPowerHistory;
import com.household.manager.repository.EntityPowerHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Haelt die Verbrauchshistorie klein: verdichtet aeltere Messpunkte und wirft
 * abgelaufene weg. Vorbild ist der ShellyReadingAggregationJob; anders als dort
 * laden wir nur das jeweils betroffene Zeitfenster statt aller alten Zeilen.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PowerHistoryAggregationJob {

    /** Juenger als das bleibt in voller Aufloesung. */
    private static final int RAW_MINUTES = 10;
    /** Ab hier wird auf Stundenwerte verdichtet. */
    private static final int MINUTE_RESOLUTION_DAYS = 2;
    /** Aelteres wird geloescht (laengster anzeigbarer Zeitraum). */
    private static final int RETENTION_DAYS = 30;

    private final EntityPowerHistoryRepository repository;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void aggregate() {
        LocalDateTime now = LocalDateTime.now();

        int minutes = compact(now.minusDays(MINUTE_RESOLUTION_DAYS), now.minusMinutes(RAW_MINUTES),
                ChronoUnit.MINUTES);
        int hours = compact(now.minusDays(RETENTION_DAYS), now.minusDays(MINUTE_RESOLUTION_DAYS),
                ChronoUnit.HOURS);

        repository.deleteByMeasuredAtBefore(now.minusDays(RETENTION_DAYS));

        if (minutes > 0 || hours > 0) {
            log.debug("Verbrauchshistorie verdichtet: {} Minuten-, {} Stunden-Buckets", minutes, hours);
        }
    }

    /** Fasst alle Punkte eines (Entitaet, Zeitfenster)-Buckets zu einem Mittelwert zusammen. */
    private int compact(LocalDateTime from, LocalDateTime to, ChronoUnit unit) {
        List<EntityPowerHistory> points = repository.findByMeasuredAtBetween(from, to);
        if (points.isEmpty()) {
            return 0;
        }

        Map<String, List<EntityPowerHistory>> buckets = points.stream()
                .collect(Collectors.groupingBy(point ->
                        point.getEntityId() + "|" + point.getMeasuredAt().truncatedTo(unit)));

        int compacted = 0;
        for (List<EntityPowerHistory> bucket : buckets.values()) {
            if (bucket.size() <= 1) {
                continue;
            }
            repository.deleteAllByIdIn(bucket.stream().map(EntityPowerHistory::getId).toList());
            repository.save(EntityPowerHistory.builder()
                    .entityId(bucket.get(0).getEntityId())
                    .measuredAt(bucket.get(0).getMeasuredAt().truncatedTo(unit))
                    .powerWatts(averageWatts(bucket))
                    .build());
            compacted++;
        }
        return compacted;
    }

    /**
     * Mittelwert der echten Messwerte. Enthaelt ein Bucket ausschliesslich Luecken,
     * bleibt es eine Luecke — sonst wuerden wir eine Null-Leistung erfinden, die
     * so nie gemessen wurde.
     */
    private Double averageWatts(List<EntityPowerHistory> bucket) {
        OptionalDouble average = bucket.stream()
                .map(EntityPowerHistory::getPowerWatts)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average();
        return average.isPresent() ? average.getAsDouble() : null;
    }
}

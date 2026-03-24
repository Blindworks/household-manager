package com.household.manager.shelly;

import com.household.manager.model.entity.ShellyReading;
import com.household.manager.repository.ShellyReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class ShellyReadingAggregationJob {

    private final ShellyReadingRepository repository;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void aggregate() {
        int minutes = aggregateByUnit(LocalDateTime.now().minusMinutes(10), ChronoUnit.MINUTES);
        int hours = aggregateByUnit(LocalDateTime.now().minusDays(2), ChronoUnit.HOURS);

        if (minutes > 0 || hours > 0) {
            log.debug("Aggregation: {} Minuten-Buckets, {} Stunden-Buckets verdichtet", minutes, hours);
        }
    }

    private int aggregateByUnit(LocalDateTime cutoff, ChronoUnit unit) {
        List<ShellyReading> readings = repository.findByTimestampBefore(cutoff);
        if (readings.isEmpty()) {
            return 0;
        }

        Map<String, List<ShellyReading>> groups = readings.stream()
                .collect(Collectors.groupingBy(r ->
                        r.getDeviceName() + "|" + r.getTimestamp().truncatedTo(unit)
                ));

        int aggregated = 0;
        for (List<ShellyReading> group : groups.values()) {
            if (group.size() <= 1) {
                continue;
            }

            double avgPower = group.stream()
                    .filter(r -> r.getPower() != null)
                    .mapToDouble(ShellyReading::getPower)
                    .average().orElse(0.0);
            double avgVoltage = group.stream()
                    .filter(r -> r.getVoltage() != null)
                    .mapToDouble(ShellyReading::getVoltage)
                    .average().orElse(0.0);
            double avgCurrent = group.stream()
                    .filter(r -> r.getCurrentA() != null)
                    .mapToDouble(ShellyReading::getCurrentA)
                    .average().orElse(0.0);
            double lastEnergy = group.stream()
                    .filter(r -> r.getTotalEnergy() != null)
                    .max(Comparator.comparing(ShellyReading::getTimestamp))
                    .map(ShellyReading::getTotalEnergy)
                    .orElse(0.0);

            LocalDateTime bucket = group.get(0).getTimestamp().truncatedTo(unit);
            String deviceName = group.get(0).getDeviceName();

            List<Long> ids = group.stream().map(ShellyReading::getId).toList();
            repository.deleteAllByIdIn(ids);

            repository.save(ShellyReading.builder()
                    .deviceName(deviceName)
                    .timestamp(bucket)
                    .power(avgPower)
                    .voltage(avgVoltage)
                    .currentA(avgCurrent)
                    .totalEnergy(lastEnergy)
                    .build());

            aggregated++;
        }

        return aggregated;
    }
}

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
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        List<ShellyReading> oldReadings = repository.findByTimestampBefore(cutoff);

        if (oldReadings.isEmpty()) {
            return;
        }

        Map<String, List<ShellyReading>> groups = oldReadings.stream()
                .collect(Collectors.groupingBy(r ->
                        r.getDeviceName() + "|" + r.getTimestamp().truncatedTo(ChronoUnit.MINUTES)
                ));

        int aggregated = 0;
        for (Map.Entry<String, List<ShellyReading>> entry : groups.entrySet()) {
            List<ShellyReading> group = entry.getValue();
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

            LocalDateTime bucket = group.get(0).getTimestamp().truncatedTo(ChronoUnit.MINUTES);
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

        if (aggregated > 0) {
            log.debug("Aggregation abgeschlossen: {} Minuten-Buckets verdichtet", aggregated);
        }
    }
}

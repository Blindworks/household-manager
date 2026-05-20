package com.household.manager.service;

import com.household.manager.ankersolix.AnkerSolixService;
import com.household.manager.ankersolix.dto.AnkerSolixLiveDto;
import com.household.manager.dto.EnergyLiveDto;
import com.household.manager.model.entity.EnergyReading;
import com.household.manager.repository.EnergyReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class EnergyPersistenceService {

    private final EnergyCalculationService energyCalculationService;
    private final AnkerSolixService ankerSolixService;
    private final EnergyReadingRepository repository;

    @Scheduled(fixedDelayString = "${energy.persistence.interval-ms:60000}",
               initialDelayString = "${energy.persistence.initial-delay-ms:15000}")
    public void persistSnapshot() {
        try {
            EnergyLiveDto energy = energyCalculationService.calculateCurrent();
            AnkerSolixLiveDto anker = fetchAnkerSafely();

            EnergyReading reading = EnergyReading.builder()
                    .timestamp(LocalDateTime.now())
                    .bkwAltW(toDouble(energy.getBkwAltW()))
                    .bkwNeuW(toDouble(energy.getBkwNeuW()))
                    .pvTotalW(toDouble(energy.getPvTotalW()))
                    .hausverbrauchW(toDouble(energy.getHausverbrauchW()))
                    .gridW(toDouble(energy.getGridW()))
                    .ankerPvW(anker != null ? anker.getPvPowerW() : null)
                    .akkuPercent(anker != null ? (double) anker.getBatteryPercent() : null)
                    .akkuPowerW(anker != null ? anker.getBatteryPowerW() : null)
                    .build();

            repository.save(reading);
        } catch (Exception ex) {
            log.warn("Energy snapshot persistence failed: {}", ex.getMessage());
        }
    }

    private AnkerSolixLiveDto fetchAnkerSafely() {
        try {
            return ankerSolixService.getLiveData();
        } catch (Exception ex) {
            log.debug("Anker live data fetch failed: {}", ex.getMessage());
            return null;
        }
    }

    private Double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }
}

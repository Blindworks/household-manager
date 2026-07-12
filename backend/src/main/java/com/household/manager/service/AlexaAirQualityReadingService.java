package com.household.manager.service;

import com.household.manager.dto.AlexaAirQualityReadingResponse;
import com.household.manager.model.entity.AlexaAirQualityReading;
import com.household.manager.repository.AlexaAirQualityReadingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Liest persistierte Amazon-Air-Quality-Messwerte.
 */
@Service
@RequiredArgsConstructor
public class AlexaAirQualityReadingService {

    private final AlexaAirQualityReadingRepository repository;

    @Transactional(readOnly = true)
    public List<AlexaAirQualityReadingResponse> getAllReadings() {
        return repository.findAllByOrderByReadingTimeAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AlexaAirQualityReadingResponse> getLatestPerDevice() {
        return repository.findDistinctApplianceIds().stream()
                .map(repository::findTopByApplianceIdOrderByReadingTimeDesc)
                .flatMap(Optional::stream)
                .map(this::toResponse)
                .toList();
    }

    private AlexaAirQualityReadingResponse toResponse(AlexaAirQualityReading reading) {
        return AlexaAirQualityReadingResponse.builder()
                .id(reading.getId())
                .applianceId(reading.getApplianceId())
                .deviceName(reading.getDeviceName())
                .readingTime(reading.getReadingTime())
                .iaq(reading.getIaq())
                .pm25(reading.getPm25())
                .voc(reading.getVoc())
                .co(reading.getCo())
                .temperature(reading.getTemperature())
                .humidity(reading.getHumidity())
                .build();
    }
}

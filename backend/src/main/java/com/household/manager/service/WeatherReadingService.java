package com.household.manager.service;

import com.household.manager.dto.WeatherReadingHistoryResponse;
import com.household.manager.model.entity.WeatherReading;
import com.household.manager.repository.WeatherReadingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** Liest persistierte Wetter-Snapshots und mappt sie auf History-DTOs. */
@Service
@RequiredArgsConstructor
public class WeatherReadingService {

    private final WeatherReadingRepository repository;

    public List<WeatherReadingHistoryResponse> getAllReadings() {
        return repository.findAllByOrderByReadingTimeAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    private WeatherReadingHistoryResponse toResponse(WeatherReading reading) {
        return WeatherReadingHistoryResponse.builder()
                .id(reading.getId())
                .readingTime(reading.getReadingTime())
                .temperature(reading.getTemperature())
                .precipitation(reading.getPrecipitation())
                .windSpeed(reading.getWindSpeed())
                .windDirection(reading.getWindDirection())
                .humidity(reading.getHumidity())
                .pressure(reading.getPressure())
                .icon(reading.getIcon())
                .build();
    }
}
